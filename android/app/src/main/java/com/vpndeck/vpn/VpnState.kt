package com.vpndeck.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide VPN status the UI, tile and notification observe. */
object VpnState {
    enum class Health { Unknown, Checking, Healthy, Recovering }
    private val _health = MutableStateFlow(Health.Unknown)
    val health: StateFlow<Health> = _health.asStateFlow()
    val apiResult = MutableStateFlow("Ещё не проверен")
    val healthCheckedAt = MutableStateFlow(0L)

    internal fun setHealth(health: Health) {
        _health.value = health
        healthCheckedAt.value = System.currentTimeMillis()
    }

    enum class Status { Stopped, Starting, Running, Stopping }

    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Last error shown to the user; cleared on the next successful start. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Wall-clock ms when the tunnel came up, for the "connected for" timer. */
    private val _since = MutableStateFlow(0L)
    val since: StateFlow<Long> = _since.asStateFlow()

    internal fun set(status: Status) {
        _status.value = status
        if (status == Status.Stopped) _health.value = Health.Unknown
        if (status == Status.Running) {
            _health.value = Health.Checking
            _since.value = System.currentTimeMillis()
            _error.value = null
        }
    }

    internal fun fail(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }
}
