package com.vpndeck.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Any reachable independent endpoint is enough; a blocked probe must not cause reloads. */
object TunnelProbe {
    internal val endpoints = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.msftconnecttest.com/connecttest.txt",
    )

    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        firstReachable(endpoints) { address ->
            currentCoroutineContext().ensureActive()
            val conn = URL(address).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                if (address.endsWith("connecttest.txt")) {
                    conn.responseCode == 200 && conn.inputStream.bufferedReader().use { it.readText().trim() } == "Microsoft Connect Test"
                } else {
                    conn.responseCode == 204
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    internal suspend fun firstReachable(addresses: List<String>, probe: suspend (String) -> Boolean): Boolean {
        for (address in addresses) {
            try {
                if (probe(address)) return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: java.io.IOException) {
                // Try another provider.
            }
        }
        return false
    }
}
