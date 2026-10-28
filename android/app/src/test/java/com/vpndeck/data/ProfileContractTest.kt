package com.vpndeck.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Parses the same file the server's TestProfileMatchesContract checks its output against. */
class ProfileContractTest {
    private fun fixture(): String {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val f = File(dir, "server/testdata/profile.json")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("server/testdata/profile.json not found above ${System.getProperty("user.dir")}")
    }

    @Test fun parsesWhatTheServerSends() {
        val p = Profile.parse(fixture())
        assertEquals("family", p.account)
        assertEquals(21600L, p.refreshIntervalSec)
        val s = p.servers.single()
        assertEquals("vless-reality-443", s.tag)
        assertEquals("203.0.113.7", s.host)
        assertEquals(443, s.port)
        assertEquals("11111111-2222-3333-4444-555555555555", s.uuid)
        assertEquals("xtls-rprx-vision", s.flow)
        assertEquals("www.example.com", s.serverName)
        assertEquals("Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0Z2FycGx5", s.publicKey)
        assertEquals("abcd", s.shortId)
        assertEquals("chrome", s.fingerprint)
    }

    @Test fun optionalFieldsHaveDefaults() {
        val p = Profile.parse(
            """{"version":1,"servers":[{"host":"h","port":1,"uuid":"u","server_name":"s","public_key":"k"}],"refresh_interval":5}""",
        )
        val s = p.servers.single()
        assertEquals("", s.flow)
        assertEquals("", s.shortId)
        assertEquals("chrome", s.fingerprint)
        assertEquals(600L, p.refreshIntervalSec) // never hammer the server
    }

    @Test fun rejectsUnknownVersionAndEmptyProfiles() {
        assertThrows(IllegalArgumentException::class.java) { Profile.parse(fixture().replace("\"version\": 1", "\"version\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { Profile.parse("""{"version":1,"servers":[]}""") }
    }
}
