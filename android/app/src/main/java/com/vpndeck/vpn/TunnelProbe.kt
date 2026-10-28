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
    /** An HTTPS check; [expectedBody] (for 200 answers) proves it wasn't a captive portal. */
    internal data class Endpoint(val url: String, val expectedBody: String? = null)

    internal val endpoints = listOf(
        Endpoint("https://www.gstatic.com/generate_204"),
        Endpoint("https://cp.cloudflare.com/generate_204"),
        // Not msftconnecttest.com: it answers HTTPS with someone else's certificate.
        Endpoint("https://detectportal.firefox.com/success.txt", expectedBody = "success"),
    )

    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        firstReachable(endpoints) { endpoint ->
            currentCoroutineContext().ensureActive()
            val conn = URL(endpoint.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4_000
                conn.readTimeout = 4_000
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                val expected = endpoint.expectedBody
                if (expected == null) {
                    conn.responseCode == 204
                } else {
                    conn.responseCode == 200 && conn.inputStream.bufferedReader().use { it.readText().trim() } == expected
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    internal suspend fun <T> firstReachable(addresses: List<T>, probe: suspend (T) -> Boolean): Boolean {
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
