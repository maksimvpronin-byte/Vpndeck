package com.vpndeck.update

import org.junit.Assert.*
import org.junit.Test

class UpdaterVersionTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("0.1.10", "0.1.9"))
        assertTrue(Updater.isNewer("0.2.0", "0.1.9"))
        assertFalse(Updater.isNewer("0.1.6", "0.1.6"))
        assertFalse(Updater.isNewer("0.1.5", "0.1.6"))
    }

    @Test fun testBuildsSortBelowTheNextRelease() {
        // -PvpndeckVersion=0.1.5.9 is how the updater gets tested against a published 0.1.6.
        assertTrue(Updater.isNewer("0.1.6", "0.1.5.9"))
        assertFalse(Updater.isNewer("0.1.5", "0.1.5.9"))
    }
}
