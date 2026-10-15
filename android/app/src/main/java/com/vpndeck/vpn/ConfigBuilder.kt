package com.vpndeck.vpn

import com.vpndeck.data.Profile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sing-box config. Only the selected apps are routed into the TUN
 * by Android itself (VpnService.Builder.addAllowedApplication via
 * include_package), so everything that reaches sing-box goes to the proxy.
 *
 * Validated against sing-box 1.14.3 with `sing-box check` — keep that in mind
 * when bumping the core version.
 */
object ConfigBuilder {
    fun build(profile: Profile, includePackages: Collection<String>, logFile: String, logLevel: String): String {
        val server = profile.servers.first()
        return JSONObject()
            .put("log", JSONObject().put("level", logLevel).put("output", logFile).put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            // Resolve through the tunnel so the carrier sees neither queries nor answers.
                            .put(JSONObject().put("type", "https").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                            .put(JSONObject().put("type", "local").put("tag", "local")),
                    )
                    .put("final", "remote")
                    .put("strategy", "ipv4_only"),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        // IPv6 is captured too, then rejected below: otherwise selected
                        // apps could reach IPv6 hosts directly, bypassing the VPN.
                        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                        .put("mtu", 9000)
                        .put("auto_route", true)
                        .put("strict_route", true)
                        .put("stack", "mixed")
                        .put("include_package", JSONArray(includePackages.sorted())),
                ),
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "proxy")
                            .put("server", server.host)
                            .put("server_port", server.port)
                            .put("uuid", server.uuid)
                            .apply { if (server.flow.isNotEmpty()) put("flow", server.flow) }
                            // Probe idle connections well before carrier NAT forgets them
                            // (sing-box defaults to 5 minutes).
                            .put("tcp_keep_alive", "60s")
                            .put("tcp_keep_alive_interval", "30s")
                            .put(
                                "tls",
                                JSONObject()
                                    .put("enabled", true)
                                    .put("server_name", server.serverName)
                                    .put("utls", JSONObject().put("enabled", true).put("fingerprint", server.fingerprint))
                                    .put(
                                        "reality",
                                        JSONObject()
                                            .put("enabled", true)
                                            .put("public_key", server.publicKey)
                                            .put("short_id", server.shortId),
                                    ),
                            ),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            .put(JSONObject().put("ip_version", 6).put("action", "reject"))
                            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
                    )
                    .put("final", "proxy")
                    .put("auto_detect_interface", true)
                    .put("default_domain_resolver", "local"),
            )
            .toString()
    }
}
