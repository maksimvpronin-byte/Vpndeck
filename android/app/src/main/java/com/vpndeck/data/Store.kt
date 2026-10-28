package com.vpndeck.data

import android.os.Build
import java.util.UUID
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the app persists. Small enough for SharedPreferences. */
object Store {
    private lateinit var prefs: SharedPreferences

    private val _selectedApps = MutableStateFlow<Set<String>>(emptySet())
    val selectedApps: StateFlow<Set<String>> = _selectedApps.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("vpndeck", Context.MODE_PRIVATE)
        _selectedApps.value = prefs.getStringSet(K_APPS, emptySet())!!.toSet()
        _session.value = readSession()
    }

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)

    /** Random ID of this installation; never sent as a credential. */
    val deviceId: String
        @Synchronized get() = prefs.getString(K_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit(commit = true) { putString(K_DEVICE_ID, id) }
        }

    /**
     * The ID this phone uses with [account]: normally [deviceId]. The server ties a device
     * to one account, so signing in to another account on the same phone gets its own ID
     * ([newDeviceIdFor]) — and switching back reuses the old one.
     */
    @Synchronized
    fun deviceIdFor(account: String): String = prefs.getString(K_DEVICE_ID + ":" + account, null) ?: deviceId

    @Synchronized
    fun newDeviceIdFor(account: String): String = UUID.randomUUID().toString().also { id ->
        prefs.edit(commit = true) { putString(K_DEVICE_ID + ":" + account, id) }
    }

    // --- Session: where the server is, how we trust it, and who we are ---

    data class Session(
        val host: String,
        val port: Int,
        val spkiPin: String,
        val account: String,
        val token: String,
    )

    private fun readSession(): Session? {
        val host = prefs.getString(K_HOST, null) ?: return null
        val pin = prefs.getString(K_PIN, null) ?: return null
        val token = prefs.getString(K_TOKEN, null) ?: return null
        return Session(host, prefs.getInt(K_PORT, ApiClient.DEFAULT_PORT), pin, prefs.getString(K_ACCOUNT, "")!!, token)
    }

    @Synchronized
    fun saveLogin(session: Session, profile: Profile) {
        prefs.edit(commit = true) {
            putString(K_HOST, session.host)
            putInt(K_PORT, session.port)
            putString(K_PIN, session.spkiPin)
            putString(K_ACCOUNT, session.account)
            putString(K_TOKEN, session.token)
            putString(K_PROFILE, profile.raw)
            putLong(K_PROFILE_AT, System.currentTimeMillis())
        }
        _session.value = session
    }

    @Synchronized
    fun saveSessionIfCurrent(expected: Session, token: String, profile: Profile): Boolean {
        if (_session.value != expected) return false
        saveLogin(expected.copy(token = token), profile)
        return true
    }

    /** Forget the account but keep the server address, pin and app list for the next login. */
    @Synchronized
    fun logout() {
        prefs.edit(commit = true) {
            remove(K_TOKEN)
            remove(K_PROFILE)
            remove(K_PROFILE_AT)
            putBoolean(K_WANT_CONNECTED, false)
        }
        _session.value = null
    }

    val lastHost: String? get() = prefs.getString(K_HOST, null)
    val lastPort: Int get() = prefs.getInt(K_PORT, ApiClient.DEFAULT_PORT)
    val lastAccount: String? get() = prefs.getString(K_ACCOUNT, null)

    /** The pin is only trusted for the host it was made with. */
    fun pinFor(host: String, port: Int): String? =
        if (prefs.getString(K_HOST, null) == host && prefs.getInt(K_PORT, -1) == port) prefs.getString(K_PIN, null) else null

    // --- Cached profile, so the VPN can start even if the API is unreachable ---

    val profile: Profile?
        get() = prefs.getString(K_PROFILE, null)?.let { runCatching { Profile.parse(it) }.getOrNull() }

    val profileFetchedAt: Long get() = prefs.getLong(K_PROFILE_AT, 0)

    fun saveProfile(profile: Profile) {
        prefs.edit(commit = true) {
            putString(K_PROFILE, profile.raw)
            putLong(K_PROFILE_AT, System.currentTimeMillis())
        }
    }

    // --- Per-app selection ---

    fun setAppSelected(pkg: String, selected: Boolean) {
        val next = _selectedApps.value.toMutableSet().apply { if (selected) add(pkg) else remove(pkg) }
        prefs.edit { putStringSet(K_APPS, next) }
        _selectedApps.value = next
    }

    // --- Whether the user wants the VPN on (survives reboots and app updates) ---

    var wantConnected: Boolean
        get() = prefs.getBoolean(K_WANT_CONNECTED, false)
        set(value) = prefs.edit { putBoolean(K_WANT_CONNECTED, value) }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(K_START_ON_BOOT, true)
        set(value) = prefs.edit { putBoolean(K_START_ON_BOOT, value) }

    // --- Self-update bookkeeping ---

    var lastUpdateCheck: Long
        get() = prefs.getLong(K_UPDATE_CHECK, 0)
        set(value) = prefs.edit { putLong(K_UPDATE_CHECK, value) }

    /** The newer release last found on GitHub (JSON), so the update card survives a process restart. */
    var availableUpdate: String?
        get() = prefs.getString(K_UPDATE_AVAILABLE, null)
        set(value) = prefs.edit { if (value == null) remove(K_UPDATE_AVAILABLE) else putString(K_UPDATE_AVAILABLE, value) }

    /** Version we already notified about, so the notification isn't repeated. */
    var notifiedUpdate: String?
        get() = prefs.getString(K_UPDATE_NOTIFIED, null)
        set(value) = prefs.edit { putString(K_UPDATE_NOTIFIED, value) }

    private const val K_DEVICE_ID = "device_id"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_PIN = "spki_pin"
    private const val K_ACCOUNT = "account"
    private const val K_TOKEN = "token"
    private const val K_PROFILE = "profile"
    private const val K_PROFILE_AT = "profile_at"
    private const val K_APPS = "selected_apps"
    private const val K_WANT_CONNECTED = "want_connected"
    private const val K_START_ON_BOOT = "start_on_boot"
    private const val K_UPDATE_CHECK = "update_check_at"
    private const val K_UPDATE_NOTIFIED = "update_notified"
    private const val K_UPDATE_AVAILABLE = "update_available"
}
