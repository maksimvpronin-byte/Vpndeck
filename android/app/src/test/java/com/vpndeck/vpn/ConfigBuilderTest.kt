package com.vpndeck.vpn

import com.vpndeck.data.Profile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigBuilderTest {
    private fun profile(flow: String = "xtls-rprx-vision") = Profile(
        account = "family",
        servers = listOf(
            Profile.VlessServer(
                tag = "t", host = "203.0.113.7", port = 443, uuid = "uuid-1", flow = flow,
                serverName = "www.example.com", publicKey = "pubkey", shortId = "abcd", fingerprint = "chrome",
            ),
        ),
        refreshIntervalSec = 21600,
        raw = "{}",
    )

    private fun build(flow: String = "xtls-rprx-vision", apps: List<String> = listOf("org.telegram", "com.vpndeck", "com.google.youtube")) =
        JSONObject(ConfigBuilder.build(profile(flow), apps, "/tmp/box.log", "warn"))

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test fun onlyTheGivenAppsEnterTheTunnel() {
        val tun = build().getJSONArray("inbounds").objects().single()
        assertEquals("tun", tun.getString("type"))
        assertEquals(listOf("com.google.youtube", "com.vpndeck", "org.telegram"), tun.getJSONArray("include_package").strings())
    }

    @Test fun proxyUsesTheProfile() {
        val config = build()
        val proxy = config.getJSONArray("outbounds").objects().first { it.getString("tag") == "proxy" }
        assertEquals("vless", proxy.getString("type"))
        assertEquals("203.0.113.7", proxy.getString("server"))
        assertEquals(443, proxy.getInt("server_port"))
        assertEquals("uuid-1", proxy.getString("uuid"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))
        val tls = proxy.getJSONObject("tls")
        assertEquals("www.example.com", tls.getString("server_name"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("pubkey", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("abcd", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
    }

    @Test fun emptyFlowIsOmitted() {
        val proxy = build(flow = "").getJSONArray("outbounds").objects().first { it.getString("tag") == "proxy" }
        assertFalse(proxy.has("flow"))
    }

    @Test fun ipv6IsRejectedNotLeakedAndDnsStaysInTheTunnel() {
        val config = build()
        val rules = config.getJSONObject("route").getJSONArray("rules").objects()
        assertTrue(rules.any { it.optInt("ip_version") == 6 && it.optString("action") == "reject" })
        val dns = config.getJSONObject("dns")
        val remote = dns.getJSONArray("servers").objects().first { it.getString("tag") == dns.getString("final") }
        assertEquals("proxy", remote.getString("detour"))
    }
}
