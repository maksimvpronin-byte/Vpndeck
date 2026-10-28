package com.vpndeck.vpn

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TunnelProbeTest {
    @Test fun oneBlockedProviderDoesNotTriggerRecovery() = runTest {
        val visited = mutableListOf<String>()
        assertTrue(TunnelProbe.firstReachable(listOf("blocked", "working", "unused")) {
            visited += it
            if (it == "blocked") throw IOException("blocked")
            true
        })
        assertEquals(listOf("blocked", "working"), visited)
    }

    @Test fun allProvidersMustFailBeforeRecovery() = runTest {
        assertFalse(TunnelProbe.firstReachable(listOf("a", "b", "c")) { false })
    }

    @Test fun endpointsAreHttpsOnDistinctHosts() {
        val urls = TunnelProbe.endpoints.map { URI(it.url) }
        assertTrue(urls.all { it.scheme == "https" })
        assertEquals(urls.size, urls.map { it.host }.toSet().size)
    }
}
