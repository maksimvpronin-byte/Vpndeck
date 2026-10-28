package com.vpndeck.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vpndeck.BuildConfig
import com.vpndeck.R
import com.vpndeck.VpnDeckApp
import com.vpndeck.data.Store
import com.vpndeck.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases. The release's tag is the version ("v0.1.3"),
 * the APK assets are named vpndeck-<version>-<abi>-release.apk.
 *
 * Trust: Android only installs an update signed with the same key as the
 * installed app, and we check that ourselves before handing the file over —
 * a tampered release on GitHub can't replace the app.
 */
object Updater {
    data class Release(val version: String, val url: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class NeedsPermission(val release: Release) : State
        data class Installing(val release: Release) : State
        /** Android wants the user to confirm; [confirm] opens its dialog. */
        data class NeedsConfirmation(val release: Release, val confirm: Intent) : State
        data class Failed(val release: Release?, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private const val TIMEOUT_MS = 20_000
    private const val CONFIRM_NOTIFICATION_ID = 3

    /** Whether MainActivity is on screen: only then may we open the install dialog ourselves. */
    @Volatile
    var uiVisible = false

    /** Returns the newer release, if any. Without [force] hits GitHub at most every 6 hours. */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current is State.Downloading || current is State.Installing || current is State.NeedsConfirmation) {
            return@withContext null
        }
        if (!force && System.currentTimeMillis() - Store.lastUpdateCheck < CHECK_EVERY_MS) {
            if (current is State.Available) return@withContext current.release
            // A release found before the process restarted must not vanish for 6 hours.
            val known = if (current == State.Idle) remembered() else null
            if (known != null) _state.value = State.Available(known)
            return@withContext known
        }
        _state.value = State.Checking
        try {
            val release = fetchLatest()
            Store.lastUpdateCheck = System.currentTimeMillis()
            remember(release)
            _state.value = if (release != null) State.Available(release) else State.UpToDate
            release
        } catch (e: Exception) {
            Log.w(VpnDeckApp.TAG, "update check failed: ${e.message}")
            _state.value = if (force) State.Failed(null, "Не удалось связаться с GitHub") else State.Idle
            null
        }
    }

    private fun remember(release: Release?) {
        Store.availableUpdate = release?.let {
            JSONObject().put("version", it.version).put("url", it.url).put("size", it.size).put("notes", it.notes).toString()
        }
    }

    private fun remembered(): Release? {
        val raw = Store.availableUpdate ?: return null
        val release = runCatching {
            val o = JSONObject(raw)
            Release(o.getString("version"), o.getString("url"), o.getLong("size"), o.optString("notes"))
        }.getOrNull()
        return release?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        val json = try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "VpnDeck/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode != 200) error("GitHub HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.getJSONArray("assets")
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
        // Prefer the APK for this phone's CPU, fall back to the universal one.
        val wanted = Build.SUPPORTED_ABIS.map { "vpndeck-$version-$it-release.apk" } + "vpndeck-$version-universal-release.apk"
        val asset = wanted.firstNotNullOfOrNull { byName[it] } ?: return null
        return Release(version, asset.getString("browser_download_url"), asset.getLong("size"), json.optString("body"))
    }

    /** Downloads (if needed), verifies and starts installation. Call from the UI. */
    suspend fun install(context: Context, release: Release) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "update-${release.version}.apk")
            if (!file.exists() || file.length() != release.size) download(release, file)
            verify(context, file)
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.value = State.NeedsPermission(release)
                return@withContext
            }
            _state.value = State.Installing(release)
            commitSession(context, file)
        } catch (e: Exception) {
            Log.e(VpnDeckApp.TAG, "update failed", e)
            _state.value = State.Failed(release, e.message ?: "Ошибка обновления")
        }
    }

    private fun download(release: Release, target: File) {
        _state.value = State.Downloading(release, 0)
        val tmp = File(target.path + ".part")
        target.parentFile?.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
        val conn = URL(release.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "VpnDeck/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode != 200) error("Загрузка: HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = (done * 100 / release.size.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = State.Downloading(release, percent)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() != release.size) error("Файл скачался не полностью")
        if (!tmp.renameTo(target)) error("Не удалось сохранить файл")
    }

    /** Same package, newer version, same signing key as the installed app. */
    private fun verify(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: error("Файл обновления повреждён")
        val installed = pm.getPackageInfo(context.packageName, flags)
        requireTrustedUpdate(
            archive.packageName, context.packageName,
            versionCode(archive), versionCode(installed), signers(archive), signers(installed),
        )
    }

    private fun versionCode(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") p.signatures
        }
        return sigs.orEmpty().map { it.toCharsString() }.toSet().also { require(it.isNotEmpty()) { "Нет подписи" } }
    }

    private fun commitSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // On Android 12+ the system may skip the confirmation for apps updating themselves.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        // Sessions left over from an earlier attempt (e.g. the process died while Android waited
        // for confirmation) would only pile up.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("vpndeck.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /**
     * Android needs the user's OK. It can only be asked from the foreground: when the
     * user left during the download, opening the dialog would be silently blocked, so
     * the card offers a button and a notification brings the user back.
     */
    internal fun onUserActionRequired(context: Context, confirm: Intent) {
        val release = pendingRelease()
        if (release == null) {
            // The process restarted meanwhile; nothing to show in the card, try directly.
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        _state.value = State.NeedsConfirmation(release, confirm)
        if (uiVisible) {
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            notifyConfirm(context, release.version)
        }
    }

    private fun notifyConfirm(context: Context, version: String) {
        val open = PendingIntent.getActivity(
            context, CONFIRM_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, VpnDeckApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.update_confirm_title))
            .setContentText(context.getString(R.string.update_confirm_text, version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService<NotificationManager>()?.notify(CONFIRM_NOTIFICATION_ID, n) }
    }

    /** Called when the user taps "Установить" in the card. */
    fun confirm(context: Context) {
        val st = _state.value as? State.NeedsConfirmation ?: return
        context.getSystemService<NotificationManager>()?.cancel(CONFIRM_NOTIFICATION_ID)
        runCatching { context.startActivity(st.confirm) }
            .onFailure { _state.value = State.Failed(st.release, "Не удалось открыть установку: ${it.message}") }
    }

    private fun pendingRelease(): Release? = when (val s = _state.value) {
        is State.Installing -> s.release
        is State.NeedsConfirmation -> s.release
        is State.Available -> s.release
        else -> null
    }

    internal fun onInstallFailed(message: String) {
        _state.value = State.Failed(pendingRelease(), message)
    }

    internal fun onInstallCancelled() {
        when (val s = _state.value) {
            is State.Installing -> _state.value = State.Available(s.release)
            is State.NeedsConfirmation -> _state.value = State.Available(s.release)
            else -> Unit
        }
    }

    fun permissionReturned() {
        (_state.value as? State.NeedsPermission)?.let { _state.value = State.Available(it.release) }
    }

    /** "0.1.10" > "0.1.9"; a 4th component (test builds) sorts below the next patch. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
