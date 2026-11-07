package com.hako.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class SecurityTest {
    @Test fun pinHashIsSaltedAndVerifiable() {
        val s1 = PinHash.newSalt(); val s2 = PinHash.newSalt()
        assertNotEquals(s1, s2)
        val h1 = PinHash.hash("4821", s1)
        assertNotEquals("aynı PIN farklı tuzla farklı özet vermeli", h1, PinHash.hash("4821", s2))
        assertFalse("özet PIN'i içermemeli", h1.contains("4821"))
        assertTrue(PinHash.matches("4821", s1, h1))
        assertFalse(PinHash.matches("4822", s1, h1))
    }

    @Test fun pinLockoutGrows() {
        assertEquals(0L, PinHash.lockoutMs(4))
        assertEquals(30_000L, PinHash.lockoutMs(5))
        assertEquals(60_000L, PinHash.lockoutMs(6))
        assertEquals(15 * 60_000L, PinHash.lockoutMs(50))
    }

    @Test fun urlCheckAcceptsHttpOnly() {
        assertEquals("http://ornek.com:8080", UrlCheck.normalize(" ornek.com:8080 "))
        assertEquals("https://a.b/get.php?x=1", UrlCheck.normalize("https://a.b/get.php?x=1"))
        assertNull(UrlCheck.normalize("file:///sdcard/a.m3u"))
        assertNull(UrlCheck.normalize("content://x/y"))
        assertNull(UrlCheck.normalize("javascript:alert(1)"))
        assertNull(UrlCheck.normalize("http://a b.com"))
        assertNull(UrlCheck.normalize(""))
    }

    private fun post(port: Int, body: String): Pair<Int, Map<String, List<String>>> {
        val c = URL("http://127.0.0.1:$port/add").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes() }
        return code to c.headerFields.filterKeys { it != null }
    }

    @Test fun localServerRateLimitsWrongCodes() {
        var submits = 0
        val srv = LocalServer("1234") { submits++; true to "ok" }
        srv.start()
        try {
            repeat(5) { assertEquals(403, post(srv.port, "k=0000").first) }
            // 5 hatadan sonra doğru kod bile 60 sn reddedilir
            assertEquals(403, post(srv.port, "k=1234&type=m3u&url=http://a").first)
            assertEquals(0, submits)
            // Güvenlik başlıkları
            val (_, h) = post(srv.port, "k=0000")
            assertEquals("DENY", h["X-Frame-Options"]?.first())
            assertTrue(h["Content-Security-Policy"]!!.first().contains("frame-ancestors 'none'"))
        } finally { srv.stop() }
    }
}
