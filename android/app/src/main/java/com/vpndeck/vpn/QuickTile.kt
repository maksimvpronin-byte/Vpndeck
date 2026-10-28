package com.vpndeck.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vpndeck.R
import com.vpndeck.data.Store
import com.vpndeck.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick-settings toggle in the notification shade. */
class QuickTile : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnState.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onClick() {
        when (VpnState.status.value) {
            VpnState.Status.Running, VpnState.Status.Starting -> VpnDeckService.stop(this)
            VpnState.Status.Stopping -> Unit
            VpnState.Status.Stopped -> {
                if (Store.session.value == null || VpnService.prepare(this) != null) openApp() else VpnDeckService.start(this)
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: VpnState.Status) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            // Connecting counts as on, so a tap can cancel a connection that hangs.
            VpnState.Status.Running, VpnState.Status.Starting -> Tile.STATE_ACTIVE
            VpnState.Status.Stopped -> Tile.STATE_INACTIVE
            VpnState.Status.Stopping -> Tile.STATE_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (status == VpnState.Status.Starting) getString(R.string.status_starting) else null
        }
        tile.updateTile()
    }
}
