package com.hako.player

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Cihaz üzerinde çalışan küçük HTTP sunucusu.
 * Telefon aynı ağdan http://<cihaz-ip>:8686 adresine girip liste bilgilerini gönderir.
 * Yetkisiz eklemeyi önlemek için ekranda gösterilen 4 haneli kod gerekir.
 */
class LocalServer(
    val code: String,
    /** Arka plan iş parçacığında çağrılır. Dönüş: (başarılı mı, mesaj) */
    private val onSubmit: (Map<String, String>) -> Pair<Boolean, String>,
) {
    private var ss: ServerSocket? = null
    val port: Int get() = ss?.localPort ?: 0

    fun start() {
        if (ss != null) return
        val s = try { ServerSocket(8686) } catch (e: Exception) { ServerSocket(0) }
        ss = s
        thread(isDaemon = true, name = "hako-http") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { handle(c) } catch (_: Exception) { } }
            }
        }
    }

    fun stop() {
        try { ss?.close() } catch (_: Exception) { }
        ss = null
    }

    private fun handle(sock: Socket): Unit = sock.use { c ->
        c.soTimeout = 30_000
        val inp = BufferedInputStream(c.getInputStream())
        val reqLine = readLine(inp) ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val query = parseForm(parts[1].substringAfter('?', ""))
        var len = 0
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (len in 1..65_536) {
            val buf = ByteArray(len); var off = 0
            while (off < len) { val r = inp.read(buf, off, len - off); if (r < 0) break; off += r }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""

        val (status, html) = when {
            method == "GET" && (path == "/" || path == "/index.html") -> 200 to formPage(query["k"] ?: "")
            method == "POST" && path == "/add" -> {
                val f = parseForm(body)
                if (f["k"]?.trim() != code) 403 to resultPage(false, "Kod hatalı. TV ekranındaki 4 haneli kodu girin.")
                else {
                    val (ok, msg) = try { onSubmit(f) } catch (e: Exception) { false to (e.message ?: "Hata") }
                    200 to resultPage(ok, msg)
                }
            }
            else -> 404 to resultPage(false, "Sayfa bulunamadı")
        }
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        c.getOutputStream().run { write(head.toByteArray()); write(bytes); flush() }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
        val k = it.substringBefore('='); val v = it.substringAfter('=')
        dec(k) to dec(v)
    }

    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Cihazın yerel ağ IPv4 adresi (Wi-Fi veya Ethernet) */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }

        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
        }
    }

    private val css = """
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        *{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:#0b0f1a;color:#e8ecf8;
        display:flex;justify-content:center;padding:16px}.box{width:100%;max-width:440px;background:#121829;
        border:1px solid #243055;border-radius:16px;padding:22px}h1{margin:0 0 4px;font-size:26px}
        p{color:#8b95b5;margin:4px 0 14px}label{display:block;font-size:13px;color:#8b95b5;margin:12px 0 4px}
        input{width:100%;padding:13px;border-radius:10px;border:1px solid #243055;background:#0b0f1a;color:#fff;font-size:16px}
        .tabs{display:flex;gap:8px;margin:12px 0}.tabs label{flex:1;margin:0;text-align:center;padding:11px;
        border:1px solid #243055;border-radius:10px;color:#e8ecf8;cursor:pointer}.tabs input{display:none}
        .tabs label:has(input:checked){background:#3d7bff;border-color:#3d7bff}
        button,.btn{display:block;width:100%;margin-top:18px;padding:14px;border:0;border-radius:10px;font-size:16px;
        font-weight:600;color:#fff;background:linear-gradient(90deg,#3d7bff,#7a5cff);text-align:center;text-decoration:none}
        .ok{color:#4ade80}.err{color:#ff5c7a}</style>"""

    private fun formPage(k: String) = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
        <title>HakoPlayer</title>$css</head><body><form class="box" method="post" action="/add">
        <h1>HakoPlayer</h1><p>IPTV listenizi TV'ye gönderin</p>
        <div class="tabs">
          <label><input type="radio" name="type" value="xtream" checked onchange="sw()">Xtream Codes</label>
          <label><input type="radio" name="type" value="m3u" onchange="sw()">M3U URL</label>
        </div>
        <label>Liste adı</label><input name="name" placeholder="Ev" value="Listem">
        <div id="x">
          <label>Sunucu</label><input name="server" inputmode="url" autocapitalize="off" placeholder="http://ornek.com:8080">
          <label>Kullanıcı adı</label><input name="username" autocapitalize="off" autocorrect="off">
          <label>Şifre</label><input name="password" type="password">
        </div>
        <div id="m" style="display:none">
          <label>M3U adresi</label><input name="url" inputmode="url" autocapitalize="off" placeholder="http://.../get.php?...">
        </div>
        <label>TV ekranındaki kod</label><input name="k" inputmode="numeric" maxlength="4" value="${esc(k)}" required>
        <button type="submit">TV'ye Gönder</button></form>
        <script>function sw(){var x=document.querySelector('input[value=xtream]').checked;
        document.getElementById('x').style.display=x?'':'none';document.getElementById('m').style.display=x?'none':'';}</script>
        </body></html>"""

    private fun resultPage(ok: Boolean, msg: String) = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
        <title>HakoPlayer</title>$css</head><body><div class="box"><h1 class="${if (ok) "ok" else "err"}">
        ${if (ok) "✓ Başarılı" else "✕ Hata"}</h1><p>${esc(msg)}</p>
        <a class="btn" href="javascript:history.back()">${if (ok) "Başka liste ekle" else "Geri dön"}</a></div></body></html>"""
}
