package com.vpndeck.vpn

import com.vpndeck.data.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep cancellation and authentication failures out of the offline-cache path. */
internal suspend fun <T> refreshOrCached(
    fetch: suspend () -> T,
    cached: () -> T?,
    save: (T) -> Unit,
    isCurrent: () -> Boolean,
    rejected: (Exception) -> Unit,
    unavailable: () -> Unit,
): T? = try {
    val fresh = fetch()
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("Session changed")
    save(fresh)
    fresh
} catch (e: CancellationException) {
    throw e
} catch (e: ApiClient.ApiException.Unauthorized) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("Session changed")
    rejected(e)
    null
} catch (e: ApiClient.ApiException.PinMismatch) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("Session changed")
    rejected(e)
    null
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("Session changed")
    unavailable()
    cached()
}

internal suspend fun <T> startWhenWanted(
    fetch: suspend () -> T,
    wanted: () -> Boolean,
    open: (T) -> Unit,
) {
    val profile = fetch()
    currentCoroutineContext().ensureActive()
    if (!wanted()) throw CancellationException("Connection cancelled")
    open(profile)
}
