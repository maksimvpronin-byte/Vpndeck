package com.vpndeck.update

import org.junit.Test

class UpdateIdentityTest {
    @Test fun signedNewerReleaseIsAccepted() {
        requireTrustedUpdate("com.vpndeck", "com.vpndeck", 106, 105, setOf("release"), setOf("release"))
    }

    @Test(expected = IllegalArgumentException::class) fun foreignSignatureIsRejected() {
        requireTrustedUpdate("com.vpndeck", "com.vpndeck", 106, 105, setOf("foreign"), setOf("release"))
    }

    @Test(expected = IllegalArgumentException::class) fun unsignedArchiveIsRejected() {
        requireTrustedUpdate("com.vpndeck", "com.vpndeck", 106, 105, emptySet(), emptySet())
    }

    @Test(expected = IllegalArgumentException::class) fun downgradeIsRejected() {
        requireTrustedUpdate("com.vpndeck", "com.vpndeck", 104, 105, setOf("release"), setOf("release"))
    }

    @Test(expected = IllegalArgumentException::class) fun otherPackageIsRejected() {
        requireTrustedUpdate("other.app", "com.vpndeck", 106, 105, setOf("release"), setOf("release"))
    }
}
