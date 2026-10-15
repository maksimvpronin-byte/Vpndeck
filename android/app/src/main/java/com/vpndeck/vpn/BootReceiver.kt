package com.vpndeck.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vpndeck.data.Store

/** Brings the VPN back after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Store.wantConnected || !Store.startOnBoot || Store.session.value == null) return
        if (VpnService.prepare(context) != null) return // permission was revoked; needs the UI
        VpnDeckService.start(context)
    }
}
