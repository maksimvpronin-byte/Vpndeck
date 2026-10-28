package com.vpndeck.vpn

import com.vpndeck.data.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Fetches fresh parameters, keeping cancellation and authentication failures
 * out of the offline-cache path.
 *
 * Returns null when the session was replaced meanwhile (logout, or a parallel
 * refresh that stored a new token): that answer belongs to nobody. This is a
 * plain result on purpose — a CancellationException thrown from inside the
 * watchdog or the refresh loop would silently end that loop.
 */
internal suspend fun <T> refreshOrCached(
    fetch: suspend () -> T,
    cached: () -> T?,
    /** Stores the answer; false if the session changed in the meantime. */
    save: (T) -> Boolean,
    isCurrent: () -> Boolean,
    rejected: (Exception) -> Unit,
    unavailable: () -> Unit,
): T? {
    val fresh = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        if (rejectsSession(e)) {
            rejected(e)
            return null
        }
        unavailable()
        return cached()
    }
    currentCoroutineContext().ensureActive()
    if (!isCurrent() || !save(fresh)) return null
    return fresh
}

/** The server answered and said no: a cached profile must not paper over that. */
internal fun rejectsSession(e: Exception) =
    e is ApiClient.ApiException.Unauthorized || e is ApiClient.ApiException.PinMismatch

/** Opens the tunnel with [fetch]'s result unless the user changed their mind meanwhile. */
internal suspend fun <T> startWhenWanted(
    fetch: suspend () -> T,
    wanted: () -> Boolean,
    open: (T) -> Unit,
): Boolean {
    val profile = fetch()
    currentCoroutineContext().ensureActive()
    if (!wanted()) return false
    open(profile)
    return true
}
