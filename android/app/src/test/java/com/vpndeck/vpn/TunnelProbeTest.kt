package com.vpndeck.vpn

import java.io.IOException
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
}
