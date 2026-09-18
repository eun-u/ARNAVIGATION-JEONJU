package kr.co.navi.mobility.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionTest {
    @Test fun `accept internet origin and legacy local USB origin`() {
        assertEquals("https://field.example.com", ServerConnection(" https://field.example.com/ ", "a".repeat(32)).url)
        assertEquals("http://127.0.0.1:18018", ServerConnection("http://127.0.0.1:18018/").url)
    }

    @Test fun `reject credentials in URL paths queries invalid ports and unsupported protocols`() {
        listOf("https://user:pass@example.com", "https://example.com/api", "https://example.com?token=a",
            "https://example.com#x", "https://example.com:0", "https://example.com:65536",
            "file:///private", "https:///missing", "http://a b", "example.com").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { ServerConnection(url) }
        }
    }

    @Test fun `access code cannot travel over cleartext or inject headers`() {
        assertThrows(IllegalArgumentException::class.java) { ServerConnection("http://example.com", "a".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { ServerConnection("https://example.com", "a".repeat(32)+"\r\nInjected: x") }
    }
}
