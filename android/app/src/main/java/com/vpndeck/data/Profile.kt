package com.vpndeck.data

import org.json.JSONObject

/** Mirrors the server's /api/v1/profile response (server/xray.go, server/main.go). */
data class Profile(
    val account: String,
    val servers: List<VlessServer>,
    val refreshIntervalSec: Long,
    val raw: String,
) {
    data class VlessServer(
        val tag: String,
        val host: String,
        val port: Int,
        val uuid: String,
        val flow: String,
        val serverName: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
    )

    companion object {
        fun parse(raw: String): Profile {
            val o = JSONObject(raw)
            require(o.getInt("version") == 1) { "unsupported profile version" }
            val arr = o.getJSONArray("servers")
            val servers = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                VlessServer(
                    tag = s.optString("tag", "server$i"),
                    host = s.getString("host"),
                    port = s.getInt("port"),
                    uuid = s.getString("uuid"),
                    flow = s.optString("flow", ""),
                    serverName = s.getString("server_name"),
                    publicKey = s.getString("public_key"),
                    shortId = s.optString("short_id", ""),
                    fingerprint = s.optString("fingerprint", "chrome"),
                )
            }
            require(servers.isNotEmpty()) { "profile has no servers" }
            return Profile(
                account = o.optString("account", ""),
                servers = servers,
                refreshIntervalSec = o.optLong("refresh_interval", 6 * 3600L).coerceAtLeast(600L),
                raw = raw,
            )
        }
    }
}
