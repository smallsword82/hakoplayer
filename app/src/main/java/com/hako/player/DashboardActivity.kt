package com.hako.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ana sayfa: açılışta son liste otomatik yüklenir, büyük kutucuklarla bölümlere geçilir */
class DashboardActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var tvStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateClock(); handler.postDelayed(this, 15_000) }
    }
    private var loadedFor: String? = null
    private var countedHidden: Set<String> = emptySet()
    private var loadJob: Job? = null
    private var triedAuto = false
    private var openedAdd = false
    private var autoPlayed = false
    private var connectJob: Job? = null
    /** Liste yüklenirken basılan bölüm; yükleme bitince açılır */
    private var pendingSection: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_dashboard)
        store = Store(this)
        tvStatus = findViewById(R.id.tvStatus)

        mapOf(R.id.tileLive to "LIVE", R.id.tileMovie to "MOVIE", R.id.tileSeries to "SERIES",
            R.id.chipFav to "FAV", R.id.chipRecent to "RECENT").forEach { (id, sec) ->
            findViewById<View>(id).setOnClickListener { openSection(sec) }
        }
        findViewById<View>(R.id.chipLists).setOnClickListener { startActivity(Intent(this, PlaylistActivity::class.java)) }
        findViewById<View>(R.id.chipSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.chipReload).setOnClickListener {
            val src = Session.source ?: return@setOnClickListener
            src.clearCache(); loadedFor = null; loadCounts()
            Toast.makeText(this, "Liste sunucudan yenileniyor…", Toast.LENGTH_SHORT).show()
        }
        // Kumandayla üzerine gelince kutucuk büyüsün
        listOf(R.id.tileLive, R.id.tileMovie, R.id.tileSeries).forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Vazgeçmek için Geri tuşu yeterli; tek düğme
                AlertDialog.Builder(this@DashboardActivity).setTitle("Çıkış")
                    .setMessage("HakoPlayer kapatılsın mı?")
                    .setPositiveButton("Çık") { _, _ -> finishAffinity() }
                    .show()
            }
        })
        findViewById<View>(R.id.tileLive).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        val src = Session.source
        val pl = Session.playlist
        when {
            // Ayarlardan kategori gizlendiyse sayılar yeniden hesaplanır (liste önbellekten, hızlı)
            src != null && pl != null -> if (loadedFor != pl.id || countedHidden != Settings.hiddenCats(pl.id)) loadCounts() else updateHeader()
            else -> {
                // Son kullanılan yoksa (ör. eski sürümden kalan listeler) kayıtlı ilk liste
                val last = store.lastPlaylist() ?: store.playlists().firstOrNull()
                when {
                    last != null && Settings.autoLoad && !triedAuto -> { triedAuto = true; connect(last) }
                    store.playlists().isEmpty() && !openedAdd -> {
                        openedAdd = true
                        startActivity(Intent(this, PlaylistActivity::class.java))
                    }
                    else -> {
                        setCounts("—", "—", "—")
                        tvStatus.text = if (store.playlists().isEmpty()) "Henüz liste yok. 📋 Listeler'den ekleyin."
                        else "📋 Listeler'den bir liste seçin."
                        updateHeader()
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun connect(p: Playlist) {
        if (connectJob?.isActive == true) return
        tvStatus.text = "\"${p.name}\" yükleniyor…"
        setCounts("…", "…", "…")
        connectJob = lifecycleScope.launch {
            try {
                Session.connect(store, p)
                loadCounts()
                pendingSection?.let { pendingSection = null; openSection(it) }
            } catch (e: Exception) {
                tvStatus.text = "Bağlanılamadı: ${e.message ?: "hata"} — 📋 Listeler'den kontrol edin"
                setCounts("—", "—", "—")
                if (pendingSection != null) {
                    pendingSection = null
                    Toast.makeText(this@DashboardActivity, "\"${p.name}\" listesine bağlanılamadı", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Üç bölümü paralel yükler, sayıları kutucuklara yazar */
    private fun loadCounts() {
        val src = Session.source ?: return
        val pl = Session.playlist ?: return
        loadedFor = pl.id
        countedHidden = Settings.hiddenCats(pl.id)
        updateHeader()
        tvStatus.text = "Liste yükleniyor…"
        setCounts("…", "…", "…")
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val ids = mapOf(Kind.LIVE to R.id.countLive, Kind.MOVIE to R.id.countMovie, Kind.SERIES to R.id.countSeries)
            suspend fun loadOne(k: Kind): Boolean = run {
                    try {
                        val cats = src.categories(k)
                        Session.rememberCats(k, cats)
                        // Gizlenen kategorilerdeki öğeler sayılmaz
                        val n = ((cats.firstOrNull()?.count ?: 0) - Parental.hiddenItemCount(pl.id, k, cats)).coerceAtLeast(0)
                        findViewById<TextView>(ids.getValue(k)).text = when (k) {
                            Kind.LIVE -> "$n kanal"; Kind.MOVIE -> "$n film"; else -> "$n dizi"
                        }
                        if (k == Kind.LIVE) maybeAutoPlay(src)
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        findViewById<TextView>(ids.getValue(k)).text = "yüklenemedi"
                        false
                    }
            }
            // Önce Canlı TV (en çok kullanılan) tam bant genişliğiyle, ardından Film ve Dizi paralel
            val liveOk = loadOne(Kind.LIVE)
            val rest = listOf(Kind.MOVIE, Kind.SERIES).map { k -> async { loadOne(k) } }
            val ok = listOf(liveOk) + rest.map { it.await() }
            tvStatus.text = if (ok.all { it }) "" else "Bazı bölümler yüklenemedi. ⟳ Yenile ile tekrar deneyin."
            updateHeader()
        }
    }

    private suspend fun maybeAutoPlay(src: Source) {
        if (autoPlayed || !Settings.autoPlayLast) return
        autoPlayed = true
        val pl = Session.playlist ?: return
        val last = store.recents(pl.id).firstOrNull { it.kind == Kind.LIVE } ?: return
        if (Parental.isItemLocked(pl.id, last) || Parental.isItemInHiddenCat(pl.id, last)) return
        val live = src.items(Kind.LIVE)
        val idx = live.indexOfFirst { it.id == last.id }
        if (idx < 0) return
        val q = live.filter { it.categoryId == last.categoryId }
        Session.queue = q
        Session.queueIndex = q.indexOfFirst { it.id == last.id }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun openSection(sec: String) {
        if (Session.source == null) {
            pendingSection = sec
            // Liste zaten yükleniyorsa bitince bölüm kendiliğinden açılır
            if (connectJob?.isActive == true) {
                tvStatus.text = "Liste yükleniyor, birazdan açılacak…"
                return
            }
            // Kayıtlı liste varsa ona bağlan; yoksa liste ekleme ekranına git
            val p = store.lastPlaylist() ?: store.playlists().firstOrNull()
            if (p != null) connect(p)
            else {
                pendingSection = null
                startActivity(Intent(this, PlaylistActivity::class.java))
            }
            return
        }
        startActivity(Intent(this, HomeActivity::class.java).putExtra("section", sec))
    }

    private fun setCounts(a: String, b: String, c: String) {
        findViewById<TextView>(R.id.countLive).text = a
        findViewById<TextView>(R.id.countMovie).text = b
        findViewById<TextView>(R.id.countSeries).text = c
    }

    private fun updateHeader() {
        val tv = findViewById<TextView>(R.id.tvPlaylist)
        val pl = Session.playlist
        tv.text = pl?.name ?: ""
        val src = Session.source ?: return
        lifecycleScope.launch {
            try {
                // accountInfo: "Ad\nDurum\nBitiş\nBağlantı" — başlıkta iki satır göster
                val lines = src.accountInfo().lines()
                tv.text = lines.first() + "\n" + lines.drop(1).filter { it.startsWith("Bitiş") || it.contains("kayıt") }.joinToString("  •  ")
            } catch (_: Exception) { }
        }
    }

    private fun updateClock() {
        findViewById<TextView>(R.id.tvClock).text = Settings.timeFormat().format(Date())
        findViewById<TextView>(R.id.tvDate).text = SimpleDateFormat("EEEE, d MMMM", Locale("tr")).format(Date())
    }
}
