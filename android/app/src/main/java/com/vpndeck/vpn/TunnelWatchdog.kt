package com.vpndeck.vpn

import android.util.Log
import com.vpndeck.VpnDeckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notices when the tunnel stops carrying traffic while the VPN looks "on" —
 * e.g. the server moved to another port or camouflage, or DPI started eating
 * the handshake — and asks the service to fetch fresh parameters and reconnect.
 *
 * The probe is one tiny HTTPS request from our own process, which is routed
 * through the tunnel like the user's apps. It runs every few minutes and
 * shortly after a network change, never in Doze.
 */
class TunnelWatchdog(
    private val isIdle: () -> Boolean,
    /** Fetches parameters over the real network and reloads; false if the auth server was unreachable. */
    private val recover: suspend () -> Boolean,
    private val onHealthChanged: (healthy: Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var healthy = true

    fun start() {
        scope.launch { loop() }
    }

    /** Re-check soon, e.g. after Wi-Fi <-> mobile. */
    fun kick() {
        kicks.trySend(Unit)
    }

    fun stop() {
        scope.cancel()
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val wait = if (failures == 0) INTERVAL_MS else (RETRY_BASE_MS shl (failures - 1)).coerceAtMost(RETRY_MAX_MS)
            withTimeoutOrNull(wait) {
                kicks.receive()
                delay(AFTER_KICK_MS) // let the new network settle and the core rebind
            }
            // One network switch arrives as several callbacks; they've all been answered by this check.
            while (kicks.tryReceive().isSuccess) Unit
            if (isIdle()) continue

            if (probe() || run { delay(CONFIRM_MS); probe() }) {
                failures = 0
                setHealthy(true)
                continue
            }

            Log.w(VpnDeckApp.TAG, "watchdog: tunnel not passing traffic, refreshing parameters")
            setHealthy(false)
            if (!recover()) {
                // Auth server unreachable too: most likely no internet at all. Don't churn.
                failures = (failures + 1).coerceAtMost(MAX_STEPS)
                continue
            }
            delay(AFTER_RELOAD_MS)
            if (probe()) {
                Log.i(VpnDeckApp.TAG, "watchdog: tunnel recovered")
                failures = 0
                setHealthy(true)
            } else {
                failures = (failures + 1).coerceAtMost(MAX_STEPS)
            }
        }
    }

    private fun setHealthy(value: Boolean) {
        if (healthy == value) return
        healthy = value
        onHealthChanged(value)
    }

    private fun probe(): Boolean = try {
        val conn = URL(PROBE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.responseCode == 204
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val PROBE_URL = "https://www.gstatic.com/generate_204"
        const val PROBE_TIMEOUT_MS = 8_000
        const val INTERVAL_MS = 4 * 60_000L
        const val AFTER_KICK_MS = 5_000L
        const val CONFIRM_MS = 5_000L
        const val AFTER_RELOAD_MS = 10_000L
        const val RETRY_BASE_MS = 60_000L
        const val RETRY_MAX_MS = 15 * 60_000L
        const val MAX_STEPS = 5
    }
}
