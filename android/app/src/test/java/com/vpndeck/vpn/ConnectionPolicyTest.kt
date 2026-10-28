package com.vpndeck.vpn

import com.vpndeck.data.ApiClient
import java.io.IOException
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
        assertFalse(startWhenWanted({ "profile" }, { false }) { opened = true })
        assertFalse(opened)
    }

    @Test fun networkFailureCanUseOfflineCache() = runTest {
        val result = refreshOrCached(
            fetch = { throw IOException("offline") }, cached = { "cached" },
            save = { fail("Offline response must not overwrite cache"); false }, isCurrent = { true },
            rejected = { fail("Network outage must keep session") }, unavailable = {},
        )
        assertEquals("cached", result)
    }

    @Test fun unauthorizedNeverUsesCacheAndRejectsSession() = runTest {
        var rejected = false
        val result = refreshOrCached<String>(
            fetch = { throw ApiClient.ApiException.Unauthorized() },
            cached = { fail("Revoked credentials must not use cached profile"); "cached" },
            save = { fail("No profile may be saved"); false }, isCurrent = { true },
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
            save = { true }, isCurrent = { true }, rejected = { rejected = true }, unavailable = {},
        ))
        assertTrue(rejected)
    }

    @Test fun responseForOldSessionIsDiscardedWithoutCancelling() = runTest {
        var saved = false
        val result = refreshOrCached(
            fetch = { "old profile" }, cached = { "cache" }, save = { saved = true; true },
            isCurrent = { false }, rejected = {}, unavailable = {},
        )
        assertNull(result)
        assertFalse(saved)
    }

    @Test fun sessionReplacedDuringSaveIsDiscarded() = runTest {
        // A parallel refresh stored a new token first: this answer is dropped, the caller carries on.
        assertNull(refreshOrCached(
            fetch = { "profile" }, cached = { "cache" }, save = { false },
            isCurrent = { true }, rejected = {}, unavailable = {},
        ))
    }

    @Test fun loopSurvivesReplacedSession() = runTest {
        // The watchdog and the refresh loop call this repeatedly; a replaced session must not end them.
        var rounds = 0
        repeat(3) {
            refreshOrCached(
                fetch = { "profile" }, cached = { null }, save = { false },
                isCurrent = { false }, rejected = {}, unavailable = {},
            )
            rounds++
        }
        assertEquals(3, rounds)
    }
}
