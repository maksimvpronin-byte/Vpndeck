package com.vpndeck.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.vpndeck.VpnDeckApp

/** Result of the PackageInstaller session started by [Updater]. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm (always on the first self-update).
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { Updater.onUserActionRequired(context, it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(VpnDeckApp.TAG, "update installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onInstallCancelled()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                Log.w(VpnDeckApp.TAG, "update install failed: $msg")
                Updater.onInstallFailed("Установка не удалась: $msg")
            }
        }
    }
}
