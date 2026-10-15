package com.vpndeck

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.vpndeck.data.Store
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale

class VpnDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Store.init(this)

        getSystemService<NotificationManager>()!!.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW),
        )

        runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
        val workDir = (getExternalFilesDir(null) ?: filesDir)
        Libbox.setup(
            SetupOptions().also {
                it.basePath = filesDir.path
                it.workingPath = workDir.path
                it.tempPath = cacheDir.path
                // Same Go runtime workaround the official client applies
                // (https://github.com/golang/go/issues/68760).
                it.fixAndroidStack = BuildConfig.DEBUG ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                it.logMaxLines = 500
                it.debug = BuildConfig.DEBUG
                it.crashReportSource = "Application"
                it.appVersion = BuildConfig.VERSION_CODE.toString()
                it.appMarketingVersion = BuildConfig.VERSION_NAME
            },
        )
        Log.i(TAG, "libbox ${Libbox.version()} ready")
    }

    companion object {
        const val TAG = "VpnDeck"
        const val CHANNEL_SERVICE = "service"
        lateinit var app: VpnDeckApp
            private set
        val connectivity: ConnectivityManager by lazy { app.getSystemService()!! }
    }
}
