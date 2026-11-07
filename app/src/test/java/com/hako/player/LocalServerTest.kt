package com.hako.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalServerTest {
    private var received: Map<String, String>? = null
    private lateinit var srv: LocalServer

    @Before fun setUp() {
        srv = LocalServer("4321") { f -> received = f; true to "eklendi" }
        srv.start()
    }

    @After fun tearDown() = srv.stop()

    private fun req(path: String, body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${srv.port}$path").openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test fun formPageHasCodePrefilled() {
        val (code, html) = req("/?k=4321")
        assertEquals(200, code)
        assertTrue(html.contains("value=\"4321\""))
        assertTrue(html.contains("name=\"password\""))
    }

    @Test fun wrongCodeRejected() {
        val (code, _) = req("/add", "k=0000&type=xtream&server=x&username=a&password=b")
        assertEquals(403, code)
        assertEquals(null, received)
    }

    @Test fun correctCodeDeliversDecodedFields() {
        val (code, html) = req("/add",
            "k=4321&type=xtream&name=Ev+TV&server=http%3A%2F%2Fornek.com%3A8080&username=ali&password=%C5%9Fifre%26")
        assertEquals(200, code)
        assertTrue(html.contains("eklendi"))
        val f = received!!
        assertEquals("Ev TV", f["name"])
        assertEquals("http://ornek.com:8080", f["server"])
        assertEquals("ali", f["username"])
        assertEquals("şifre&", f["password"])
    }
}
