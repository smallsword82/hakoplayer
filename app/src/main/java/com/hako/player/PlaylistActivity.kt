package com.hako.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.content.res.Configuration

class PlaylistActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var progress: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var btnAdd: Button
    private var server: LocalServer? = null
    private var resumed = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_playlist)
        store = Store(this)
        progress = findViewById(R.id.progress)
        tvError = findViewById(R.id.tvError)
        btnAdd = findViewById(R.id.btnAdd)

        val etName = findViewById<EditText>(R.id.etName)
        val etServer = findViewById<EditText>(R.id.etServer)
        val etUser = findViewById<EditText>(R.id.etUser)
        val etPass = findViewById<EditText>(R.id.etPass)
        val etUrl = findViewById<EditText>(R.id.etUrl)
        val group = findViewById<RadioGroup>(R.id.typeGroup)

        group.setOnCheckedChangeListener { _, id ->
            val x = id == R.id.rbXtream
            etServer.visibility = if (x) View.VISIBLE else View.GONE
            etUser.visibility = etServer.visibility
            etPass.visibility = etServer.visibility
            etUrl.visibility = if (x) View.GONE else View.VISIBLE
        }

        btnAdd.setOnClickListener {
            val name = etName.text.toString().trim().ifEmpty { "Listem" }
            val id = System.currentTimeMillis().toString()
            val p = if (group.checkedRadioButtonId == R.id.rbXtream) {
                if (etServer.text.isBlank() || etUser.text.isBlank() || etPass.text.isBlank()) {
                    tvError.text = "Sunucu, kullanıcı adı ve şifre gerekli"; return@setOnClickListener
                }
                val server = UrlCheck.normalize(etServer.text.toString())
                    ?: run { tvError.text = "Geçersiz sunucu adresi (http:// veya https:// olmalı)"; return@setOnClickListener }
                Playlist(id, name, "xtream", server, etUser.text.toString().trim(), etPass.text.toString().trim())
            } else {
                if (etUrl.text.isBlank()) { tvError.text = "M3U adresi gerekli"; return@setOnClickListener }
                val url = UrlCheck.normalize(etUrl.text.toString())
                    ?: run { tvError.text = "Geçersiz M3U adresi (http:// veya https:// olmalı)"; return@setOnClickListener }
                Playlist(id, name, "m3u", url = url)
            }
            connect(p, save = true)
        }

        findViewById<View>(R.id.btnFreeTr).setOnClickListener { addFreeTr() }

        // Yatay ekranda (TV) form ve QR yan yana
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            findViewById<LinearLayout>(R.id.row).orientation = LinearLayout.HORIZONTAL
            findViewById<View>(R.id.formPanel).layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
            findViewById<View>(R.id.qrPanel).layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 32 }
        }
        server = LocalServer((1000..9999).random().toString(), ::onRemoteSubmit).also { srv ->
            // Çok fazla hatalı denemede kod yenilenir; QR ve ekrandaki kod da güncellensin
            srv.onCodeChanged = { runOnUiThread { if (resumed) startServer() } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renderSaved()
        startServer()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        server?.stop()
    }

    private fun startServer() {
        val srv = server ?: return
        val ivQr = findViewById<ImageView>(R.id.ivQr)
        val tvUrl = findViewById<TextView>(R.id.tvServerUrl)
        val tvCode = findViewById<TextView>(R.id.tvCode)
        val ip = LocalServer.localIp()
        if (ip == null) {
            ivQr.setImageDrawable(null)
            tvUrl.text = "Ağ bağlantısı bulunamadı"
            tvCode.text = ""
            return
        }
        try { srv.start() } catch (e: Exception) {
            tvUrl.text = "Sunucu başlatılamadı: ${e.message}"; return
        }
        val url = "http://$ip:${srv.port}"
        ivQr.setImageBitmap(LocalServer.qr("$url/?k=${srv.code}"))
        tvUrl.text = url
        tvCode.text = "Kod: ${srv.code}"
    }

    /** Telefondan gelen form (sunucu iş parçacığında çalışır) */
    private fun onRemoteSubmit(f: Map<String, String>): Pair<Boolean, String> {
        val name = f["name"].orEmpty().trim().ifEmpty { "Listem" }
        val id = System.currentTimeMillis().toString()
        val p = if (f["type"] == "m3u") {
            if (f["url"].isNullOrBlank()) return false to "M3U adresi gerekli"
            val url = UrlCheck.normalize(f["url"]!!) ?: return false to "Geçersiz M3U adresi (http:// veya https:// olmalı)"
            Playlist(id, name, "m3u", url = url)
        } else {
            val server = f["server"].orEmpty().trim()
            val user = f["username"].orEmpty().trim()
            val pass = f["password"].orEmpty().trim()
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty())
                return false to "Sunucu, kullanıcı adı ve şifre gerekli"
            val srvUrl = UrlCheck.normalize(server) ?: return false to "Geçersiz sunucu adresi (http:// veya https:// olmalı)"
            if (user.length > 256 || pass.length > 256) return false to "Kullanıcı adı / şifre çok uzun"
            val pl = Playlist(id, name.take(64), "xtream", srvUrl, user, pass)
            // Kaydetmeden önce bilgileri doğrula, sonucu telefona bildir
            try {
                runBlocking { withTimeout(25_000) { XtreamSource(pl).login() } }
            } catch (e: Exception) {
                return false to "Sunucuya bağlanılamadı: ${e.message ?: "zaman aşımı"}"
            }
            pl
        }
        store.savePlaylist(p)
        runOnUiThread {
            renderSaved()
            Toast.makeText(this, "📱 Telefondan liste eklendi: ${p.name}", Toast.LENGTH_LONG).show()
            if (resumed) connect(p, save = false)
        }
        return true to "\"${p.name}\" listesi TV'ye eklendi ve açılıyor."
    }

    private fun renderSaved() {
        val box = findViewById<LinearLayout>(R.id.savedList)
        box.removeAllViews()
        val list = store.playlists()
        findViewById<View>(R.id.savedTitle).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (p in list) {
            val tv = TextView(this, null, 0, R.style.NavBtn).apply {
                val active = Session.playlist?.id == p.id
                text = "${if (active) "✅" else "▶"}  ${p.name}   (${if (p.type == "xtream") "Xtream" else "M3U"})"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 }
                setOnClickListener { connect(p, save = false) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@PlaylistActivity)
                        .setTitle(p.name).setMessage("Bu liste silinsin mi?")
                        .setPositiveButton("Sil") { _, _ -> store.deletePlaylist(p.id); renderSaved() }
                        .setNegativeButton("Vazgeç", null).show()
                    true
                }
            }
            box.addView(tv)
        }
        layoutFreeBox(list)
        if (list.isNotEmpty()) box.getChildAt(0).requestFocus()
        else findViewById<View>(R.id.btnFreeTr).requestFocus()
    }

    /**
     * Hiç liste yokken (ilk açılış) ücretsiz liste kutusu üstte ve belirgin durur.
     * Liste varsa formun altına iner; Türkiye listesi zaten kayıtlıysa "aç" olarak görünür.
     */
    private fun layoutFreeBox(list: List<Playlist>) {
        val panel = findViewById<LinearLayout>(R.id.formPanel)
        val freeBox = findViewById<View>(R.id.freeBox)
        // Liste yoksa en üstte; varsa kayıtlı listelerin hemen altında (yeni liste formunun üstünde)
        val anchor = findViewById<View>(if (list.isEmpty()) R.id.savedTitle else R.id.savedList)
        panel.removeView(freeBox)
        panel.addView(freeBox, panel.indexOfChild(anchor) + if (list.isEmpty()) 0 else 1)
        val saved = FreeLists.findTr(list) != null
        findViewById<TextView>(R.id.btnFreeTr).text =
            if (saved) "📺 Ücretsiz Türk kanallarını aç" else "📺 Ücretsiz Türk kanalları ekle"
        findViewById<View>(R.id.tvFreeHint).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** iptv-org Türkiye listesi: zaten kayıtlıysa onu açar, değilse onay alıp ekler */
    private fun addFreeTr() {
        FreeLists.findTr(store.playlists())?.let { connect(it, save = false); return }
        AlertDialog.Builder(this)
            .setTitle("Ücretsiz Türk kanalları")
            .setMessage(FreeLists.DISCLAIMER)
            .setPositiveButton("Ekle ve aç") { _, _ ->
                connect(Playlist(System.currentTimeMillis().toString(), FreeLists.TR_NAME, "m3u", url = FreeLists.TR_URL), save = true)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun connect(p: Playlist, save: Boolean) {
        tvError.text = ""
        progress.visibility = View.VISIBLE
        btnAdd.isEnabled = false
        findViewById<View>(R.id.btnFreeTr).isEnabled = false
        lifecycleScope.launch {
            try {
                Session.connect(store, p)
                if (save) store.savePlaylist(p)
                // Ana sayfaya dön; orada liste otomatik yüklenir
                finish()
            } catch (e: Exception) {
                tvError.text = "Bağlanılamadı: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                progress.visibility = View.GONE
                btnAdd.isEnabled = true
                findViewById<View>(R.id.btnFreeTr).isEnabled = true
            }
        }
    }
}
