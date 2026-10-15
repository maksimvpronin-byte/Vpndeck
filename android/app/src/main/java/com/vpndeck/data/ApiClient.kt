package com.vpndeck.data

import android.util.Base64
import com.vpndeck.vpn.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Talks to vpndeck-server. The server uses a self-signed certificate, so trust
 * works like SSH: the first connection shows the key fingerprint, the user
 * accepts it, and from then on only that exact key is accepted.
 */
object ApiClient {
    const val DEFAULT_PORT = 8448
    private const val TIMEOUT_MS = 15_000

    sealed class ApiException(message: String) : IOException(message) {
        class InvalidCredentials : ApiException("Неверный логин или пароль")
        class TooManyAttempts : ApiException("Слишком много попыток. Подождите 15 минут")
        class Unauthorized : ApiException("Сессия недействительна, войдите заново")
        class PinMismatch : ApiException("Ключ сервера изменился! Возможен перехват соединения")
        class Server(code: Int) : ApiException("Ошибка сервера ($code)")
    }

    /** Connects without trusting anything and returns the server key fingerprint. Sends no data. */
    suspend fun probeFingerprint(host: String, port: Int): String = withContext(Dispatchers.IO) {
        var seen: String? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                seen = fingerprint(chain[0])
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
            NetworkMonitor.defaultNetwork?.bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.startHandshake()
        }
        seen ?: throw IOException("Сервер не прислал сертификат")
    }

    data class LoginResult(val token: String, val profile: Profile)

    suspend fun login(host: String, port: Int, pin: String, username: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("username", username).put("password", password).toString()
            val (code, text) = request(host, port, pin, "POST", "/api/v1/login", body, token = null)
            when (code) {
                200 -> {
                    val o = JSONObject(text)
                    LoginResult(o.getString("token"), Profile.parse(o.getJSONObject("profile").toString()))
                }
                401 -> throw ApiException.InvalidCredentials()
                429 -> throw ApiException.TooManyAttempts()
                else -> throw ApiException.Server(code)
            }
        }

    suspend fun fetchProfile(session: Store.Session): Profile = withContext(Dispatchers.IO) {
        val (code, text) = request(session.host, session.port, session.spkiPin, "GET", "/api/v1/profile", null, session.token)
        when (code) {
            200 -> Profile.parse(text)
            401 -> throw ApiException.Unauthorized()
            else -> throw ApiException.Server(code)
        }
    }

    private fun request(host: String, port: Int, pin: String, method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val url = URL("https", hostForUrl(host), port, path)
        // While the VPN is up our own UID is routed into the tunnel; talk to the auth
        // server over the real network so a broken tunnel can still be repaired by a refresh.
        val conn = (NetworkMonitor.defaultNetwork?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        try {
            conn.sslSocketFactory = pinnedContext(pin).socketFactory
            // The pinned key is the trust anchor; the name on a self-signed cert proves nothing extra.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = method
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            if (e.cause is PinMismatchCause || e.cause?.cause is PinMismatchCause) throw ApiException.PinMismatch()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private class PinMismatchCause : CertificateException("server key does not match the pinned key")

    private fun pinnedContext(pin: String): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !MessageDigest.isEqual(fingerprint(chain[0]).toByteArray(), pin.toByteArray())) {
                    throw PinMismatchCause()
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    /** Same format the server prints: "SHA256:" + unpadded base64 of SHA-256(SubjectPublicKeyInfo). */
    fun fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hostForUrl(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** "1.2.3.4", "1.2.3.4:8448", "vpn.example.com" -> host and port. */
    fun parseAddress(input: String): Pair<String, Int>? {
        val s = input.trim().removePrefix("https://").removeSuffix("/")
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val port = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: DEFAULT_PORT
            return s.substring(1, end) to port
        }
        val parts = s.split(':')
        return when (parts.size) {
            1 -> s to DEFAULT_PORT
            2 -> parts[0] to (parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
            else -> s to DEFAULT_PORT // bare IPv6
        }
    }
}
