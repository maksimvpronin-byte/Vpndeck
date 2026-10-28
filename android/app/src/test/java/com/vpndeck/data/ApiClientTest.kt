package com.vpndeck.data

import org.junit.Assert.*
import org.junit.Test

class ApiClientTest {
    @Test fun parsesServerAddresses() {
        assertEquals("1.2.3.4" to 8448, ApiClient.parseAddress("1.2.3.4"))
        assertEquals("1.2.3.4" to 9000, ApiClient.parseAddress(" 1.2.3.4:9000 "))
        assertEquals("vpn.example.com" to 8448, ApiClient.parseAddress("https://vpn.example.com/"))
        assertEquals("vpn.example.com" to 8448, ApiClient.parseAddress("http://vpn.example.com/api"))
        assertEquals("::1" to 8448, ApiClient.parseAddress("[::1]"))
        assertEquals("2001:db8::1" to 9000, ApiClient.parseAddress("[2001:db8::1]:9000"))
        assertEquals("2001:db8::1" to 8448, ApiClient.parseAddress("2001:db8::1"))
    }

    @Test fun rejectsBadAddresses() {
        for (bad in listOf("", "  ", "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:abc", ":8448", "[::1]:99999", "[::1", "[]")) {
            assertNull("'$bad'", ApiClient.parseAddress(bad))
        }
    }

    @Test fun readsRefusalReasons() {
        assertEquals("device_revoked", ApiClient.errorCode("""{"error":"device_revoked"}"""))
        assertEquals("", ApiClient.errorCode("""{"token":"x"}"""))
        assertEquals("", ApiClient.errorCode(""))
        assertEquals("", ApiClient.errorCode("<html>"))
    }
}
