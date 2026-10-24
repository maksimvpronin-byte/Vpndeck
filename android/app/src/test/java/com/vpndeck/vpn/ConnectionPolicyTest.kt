package com.vpndeck.vpn

import com.vpndeck.data.ApiClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionPolicyTest {
    @Test fun cancellingDuringFetchNeverOpensTunnel() = runTest {
        val response = CompletableDeferred<String>()
        var opened = false
        val start = launch { startWhenWanted({ response.await() }, { true }) { opened = true } }
        runCurrent()
        start.cancel()
        response.complete("profile")
        start.join()
        assertFalse(opened)
    }

    @Test fun stopFlagIsCheckedAfterFetch() = runTest {
        var opened = false
        try {
            startWhenWanted({ "profile" }, { false }) { opened = true }
            fail("Stopped connection was started")
        } catch (_: CancellationException) { }
        assertFalse(opened)
    }

    @Test fun networkFailureCanUseOfflineCache() = runTest {
        val result = refreshOrCached(
            fetch = { throw IOException("offline") }, cached = { "cached" },
            save = { fail("Offline response must not overwrite cache") }, isCurrent = { true },
            rejected = { fail("Network outage must keep session") }, unavailable = {},
        )
        assertEquals("cached", result)
    }

    @Test fun unauthorizedNeverUsesCacheAndRejectsSession() = runTest {
        var rejected = false
        val result = refreshOrCached<String>(
            fetch = { throw ApiClient.ApiException.Unauthorized() },
            cached = { fail("Revoked credentials must not use cached profile"); "cached" },
            save = { fail("No profile may be saved") }, isCurrent = { true },
            rejected = { rejected = true }, unavailable = { fail("401 is not an outage") },
        )
        assertNull(result)
        assertTrue(rejected)
    }

    @Test fun changedKeyNeverUsesCache() = runTest {
        var rejected = false
        assertNull(refreshOrCached<String>(
            fetch = { throw ApiClient.ApiException.PinMismatch() },
            cached = { fail("Changed key must not use cached profile"); "cached" },
            save = {}, isCurrent = { true }, rejected = { rejected = true }, unavailable = {},
        ))
        assertTrue(rejected)
    }

    @Test fun responseForOldSessionIsDiscarded() = runTest {
        var saved = false
        try {
            refreshOrCached(
                fetch = { "old profile" }, cached = { "cache" }, save = { saved = true },
                isCurrent = { false }, rejected = {}, unavailable = {},
            )
            fail("Stale response accepted")
        } catch (_: CancellationException) { }
        assertFalse(saved)
    }
}
