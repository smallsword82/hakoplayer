package com.hako.player

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import java.util.Date
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {
    private enum class Section { LIVE, MOVIE, SERIES, FAV, RECENT }

    private lateinit var store: Store
    private lateinit var src: Source
    private lateinit var pl: Playlist
    private lateinit var rvCats: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var etSearch: EditText
    private val catAdapter = CategoryAdapter(
        isLocked = { it.id != "*" && it.id in lockedIds },
        onLong = { toggleCategoryLock(it) },
    ) { selectCategory(it) }
    /** Bu bölümde kilitli kategori id'leri (PIN girilince boşalır) */
    private var lockedIds: Set<String> = emptySet()
    private var sectionCats: List<Category> = emptyList()
    private lateinit var listAdapter: ItemAdapter
    private lateinit var gridAdapter: ItemAdapter

    private var section = Section.LIVE
    private var allItems: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var currentCat: Category? = null
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    /** Kategoriye göre önceden gruplanmış öğeler (her tıklamada tüm listeyi taramamak için) */
    private var byCat: Map<String, List<Item>> = emptyMap()
    /** Favori anahtarları bellekte; her satır çiziminde diskten okunmaz */
    private val favKeys = HashSet<String>()
    // Canlı TV önizleme (yalnızca yatay düzende var; dikeyde null)
    private var previewPanel: View? = null
    private var previewView: PlayerView? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewItem: Item? = null
    private var previewRetries = 0
    private var previewEpgJob: Job? = null
    private var resumePreview: Runnable? = null
    private var focusCatsOnLoad = false
    /** Sağda canlı önizleme paneli gösterilen bölümler */
    private val previewSections = setOf(Section.LIVE, Section.FAV, Section.RECENT)
    private val navIds = mapOf(
        Section.LIVE to R.id.navLive, Section.MOVIE to R.id.navMovie, Section.SERIES to R.id.navSeries,
        Section.FAV to R.id.navFav, Section.RECENT to R.id.navRecent,
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = Session.source; val p = Session.playlist
        if (s == null || p == null) { finish(); return }
        src = s; pl = p
        setContentView(R.layout.activity_home)
        store = Store(this)

        rvCats = findViewById(R.id.rvCategories)
        rvItems = findViewById(R.id.rvItems)
        tvTitle = findViewById(R.id.tvTitle)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)
        etSearch = findViewById(R.id.etSearch)

        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        catAdapter.horizontal = portrait
        rvCats.layoutManager = LinearLayoutManager(this,
            if (portrait) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false)
        rvCats.adapter = catAdapter
        rvItems.setItemViewCacheSize(24)
        // Satır güncellenirken yeni görünüm oluşturulmasın (kumanda odağı yerinde kalsın)
        (rvCats.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        (rvItems.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        reloadFavKeys()
        val fav: (Item) -> Boolean = { it.key in favKeys }
        listAdapter = ItemAdapter(false, fav, ::open, ::toggleFav)
        gridAdapter = ItemAdapter(true, fav, ::open, ::toggleFav)

        navIds.forEach { (sec, id) -> findViewById<View>(id).setOnClickListener { showSection(sec) } }
        findViewById<View>(R.id.navBack).setOnClickListener { finish() } // ana sayfaya dön
        findViewById<View>(R.id.navReload).setOnClickListener {
            src.clearCache(); showSection(section)
            Toast.makeText(this, "Liste yenileniyor…", Toast.LENGTH_SHORT).show()
        }

        previewPanel = findViewById(R.id.previewPanel)
        previewView = findViewById(R.id.previewView)
        findViewById<View?>(R.id.previewBox)?.let { box ->
            // 16:9 önizleme alanı
            box.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val h = (r - l) * 9 / 16
                if (h > 0 && v.layoutParams.height != h) v.post { v.layoutParams = v.layoutParams.apply { height = h } }
            }
        }
        findViewById<View?>(R.id.btnFullscreen)?.setOnClickListener { previewItem?.let { goFullscreen(it) } }
        findViewById<View?>(R.id.btnPreviewFav)?.setOnClickListener {
            previewItem?.let { toggleFav(it, shown.indexOfFirst { s -> s.key == it.key }); updatePreviewFav() }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = applyFilter(debounce = true)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            findViewById<TextView>(R.id.tvAccount).text = try { src.accountInfo() } catch (e: Exception) { pl.name }
        }
        val start = intent.getStringExtra("section")?.let { runCatching { Section.valueOf(it) }.getOrNull() } ?: Section.LIVE
        // Yatay (TV) düzende liste yüklenince odak ilk kategoriye geçsin
        focusCatsOnLoad = !portrait
        findViewById<View>(navIds.getValue(start)).requestFocus()
        showSection(start)
    }

    private fun reloadFavKeys() {
        favKeys.clear(); store.favorites(pl.id).forEach { favKeys += it.key }
    }

    override fun onResume() {
        super.onResume()
        reloadFavKeys()
        computeLocks()
        // Tam ekrandan dönünce, orada en son açılan kanal önizlemede devam etsin.
        // Tam ekran oynatıcı bağlantısını kapatana kadar kısa bekle (tek bağlantılı hesaplar).
        if (previewItem != null && section in previewSections) {
            val back = Session.queue.getOrNull(Session.queueIndex)?.takeIf { it.kind == Kind.LIVE } ?: previewItem
            resumePreview = Runnable {
                back ?: return@Runnable
                startPreview(back)
                // Odağı oynayan kanala taşı
                val pos = shown.indexOfFirst { it.key == back.key }
                if (pos >= 0 && rvItems.hasFocus()) {
                    rvItems.scrollToPosition(pos)
                    rvItems.post { rvItems.layoutManager?.findViewByPosition(pos)?.requestFocus() }
                }
            }.also { rvItems.postDelayed(it, 700) }
        }
        // Favori yıldızları / son izlenenler güncel olsun
        if (section == Section.FAV || section == Section.RECENT) showSection(section)
        else rvItems.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) }
    }

    override fun onPause() {
        super.onPause()
        resumePreview?.let { rvItems.removeCallbacks(it) }
        releasePreview()
    }

    override fun onDestroy() {
        releasePreview()
        super.onDestroy()
    }

    /** Satırlar çizilene kadar kısa aralıklarla dener */
    private fun focusFirstCategory(tries: Int) {
        rvCats.postDelayed({
            val v = rvCats.layoutManager?.findViewByPosition(0)
            if (v != null) v.requestFocus() else if (tries > 0) focusFirstCategory(tries - 1)
        }, 100)
    }

    // ---------- Canlı TV önizleme ----------

    private fun ensurePreviewPlayer(): ExoPlayer? {
        val pv = previewView ?: return null
        previewPlayer?.let { return it }
        return Players.build(this, live = true).also { p ->
            pv.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        previewRetries = 0
                        findViewById<View>(R.id.tvPreviewMsg).visibility = View.GONE
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition(); p.prepare(); return
                    }
                    if (previewRetries < 2) {
                        previewRetries++
                        rvItems.postDelayed({ previewPlayer?.let { it.prepare(); it.playWhenReady = true } }, 800L * previewRetries)
                        return
                    }
                    findViewById<TextView>(R.id.tvPreviewMsg).apply {
                        text = "Oynatılamadı (${error.errorCodeName})"; visibility = View.VISIBLE
                    }
                }
            })
            previewPlayer = p
        }
    }

    private fun startPreview(item: Item) {
        val url = item.url ?: return
        val p = ensurePreviewPlayer() ?: return
        previewItem = item
        previewRetries = 0
        listAdapter.activeKey = item.key
        // Tam ekranda kanal değiştirme bu listeyi kullanır
        val q = shown.filter { it.kind == Kind.LIVE && it.url != null }
        Session.queue = q
        Session.queueIndex = q.indexOfFirst { it.key == item.key }.coerceAtLeast(0)

        p.stop()
        p.setMediaItem(Players.mediaItem(url))
        p.prepare()
        p.playWhenReady = true

        findViewById<TextView>(R.id.tvPreviewMsg).apply { text = ""; visibility = View.GONE }
        findViewById<TextView>(R.id.tvPreviewName).text = item.name
        findViewById<ImageView>(R.id.ivPreviewLogo).apply {
            if (item.logo != null) { visibility = View.VISIBLE; load(item.logo) } else visibility = View.GONE
        }
        findViewById<View>(R.id.previewButtons).visibility = View.VISIBLE
        updatePreviewFav()
        Session.playlist?.let { store.addRecent(it.id, item) }

        val ep = findViewById<TextView>(R.id.tvPreviewEpg)
        ep.text = ""
        previewEpgJob?.cancel()
        previewEpgJob = lifecycleScope.launch {
            val list = try { src.shortEpg(item) } catch (_: Exception) { emptyList() }
            if (list.isEmpty()) { ep.text = "Program bilgisi yok"; return@launch }
            val f = Settings.timeFormat()
            ep.text = list.take(2).mapIndexed { i, e ->
                "${if (i == 0) "Şimdi" else "Sonra"}   ${f.format(Date(e.start))} - ${f.format(Date(e.end))}\n${e.title}"
            }.joinToString("\n\n")
        }
    }

    private fun updatePreviewFav() {
        val it = previewItem ?: return
        findViewById<Button>(R.id.btnPreviewFav).text =
            if (it.key in favKeys) "★ Favorilerden çıkar" else "☆ Favorilere ekle"
    }

    /** Oynatıcıyı bırakır (bağlantı kapanır); önizlenen kanal hatırlanır */
    private fun releasePreview() {
        previewEpgJob?.cancel()
        previewPlayer?.release()
        previewPlayer = null
        previewView?.player = null
    }

    /** Önizleme panelini tamamen kapatır (başka bölüme geçince) */
    private fun clearPreview() {
        releasePreview()
        previewItem = null
        listAdapter.activeKey = null
        findViewById<View?>(R.id.previewButtons)?.visibility = View.GONE
        findViewById<TextView?>(R.id.tvPreviewName)?.text = ""
        findViewById<TextView?>(R.id.tvPreviewEpg)?.text = ""
        findViewById<View?>(R.id.ivPreviewLogo)?.visibility = View.GONE
        findViewById<TextView?>(R.id.tvPreviewMsg)?.apply {
            text = "Önizlemek için bir kanal seçin\nTekrar OK: tam ekran"; visibility = View.VISIBLE
        }
    }

    private fun goFullscreen(item: Item) {
        // Tek bağlantı hakkı olan hesaplar için önizlemeyi tam ekrandan önce kapat
        releasePreview()
        val q = shown.filter { it.kind == item.kind && it.url != null }
        Session.queue = q
        Session.queueIndex = q.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun kindOf(sec: Section) = when (sec) {
        Section.LIVE -> Kind.LIVE; Section.MOVIE -> Kind.MOVIE; else -> Kind.SERIES
    }

    private fun showSection(sec: Section) {
        // Önizleme Canlı TV, Favoriler ve Son İzlenenler arasında geçişte sürer; diğer bölümlerde kapanır
        if (sec !in previewSections || previewItem?.let { Parental.isAdultItem(it) } == true) clearPreview()
        section = sec
        navIds.forEach { (s, id) -> findViewById<View>(id).isSelected = s == sec }
        previewPanel?.visibility = if (sec in previewSections) View.VISIBLE else View.GONE
        etSearch.setText("")
        loadJob?.cancel()
        tvTitle.text = when (sec) {
            Section.LIVE -> "Canlı TV"; Section.MOVIE -> "Filmler"; Section.SERIES -> "Diziler"
            Section.FAV -> "Favoriler"; Section.RECENT -> "Son İzlenenler"
        }

        val grid = sec == Section.MOVIE || sec == Section.SERIES
        val landscape = resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        // Yatay düzen: film/dizide kategori kolonu dar, afişlere daha çok yer kalsın
        val catWeight = if (sec == Section.LIVE) 0.65f else 0.36f
        // Favoriler / Son İzlenenler'de kategori yok (yalnızca "Tümü"): sol kolon gizlenir
        rvCats.visibility = if (sec == Section.FAV || sec == Section.RECENT) View.GONE else View.VISIBLE
        val listWeight = 0.85f
        if (landscape) (rvCats.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            if (lp.weight != catWeight) { lp.weight = catWeight; rvCats.layoutParams = lp }
        }
        if (grid) {
            val dp = resources.displayMetrics.run { widthPixels / density }
            // Liste alanı genişliği (dp): canlı TV dışındaki bölümlerde önizleme paneli gizli
            val listWidth = if (!landscape) dp else dp * listWeight / (catWeight + listWeight) - 16
            // TV'de satırda 5 afiş; telefonda ekran genişliğine göre
            val cols = if (landscape) 5 else (listWidth / 118).toInt().coerceAtLeast(2)
            rvItems.layoutManager = GridLayoutManager(this, cols)
            rvItems.adapter = gridAdapter
        } else {
            rvItems.layoutManager = LinearLayoutManager(this)
            rvItems.adapter = listAdapter
        }

        if (sec == Section.FAV || sec == Section.RECENT) {
            val hidden = Settings.hiddenCats(pl.id)
            allItems = (if (sec == Section.FAV) store.favorites(pl.id) else store.recents(pl.id))
                .filter { !Parental.isItemInHiddenCat(pl.id, it, hidden) }
            byCat = emptyMap()
            sectionCats = emptyList(); lockedIds = emptySet()
            catAdapter.items = listOf(Category("*", "Tümü", allItems.size))
            selectCategory(catAdapter.items[0])
            return
        }

        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        catAdapter.items = emptyList(); setShown(emptyList())
        loadJob = lifecycleScope.launch {
            try {
                val kind = kindOf(sec)
                val raw = src.items(kind)
                val items = if (Settings.sortAz) withContext(Dispatchers.Default) { raw.sortedBy { it.name.lowercase() } } else raw
                val allCats = src.categories(kind)
                Session.rememberCats(kind, allCats)
                val cats = allCats.filter { it.id == "*" || it.count > 0 }
                sectionCats = cats
                computeLocks(resetList = true)
                byCat = withContext(Dispatchers.Default) { items.groupBy { it.categoryId } }
                allItems = items
                progress.visibility = View.GONE
                catAdapter.items.firstOrNull()?.let { selectCategory(it) }
                if (focusCatsOnLoad) {
                    focusCatsOnLoad = false
                    focusFirstCategory(10)
                }
                prefetchOthers()
            } catch (e: Exception) {
                tvEmpty.text = "Yüklenemedi: ${e.message}"
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** Kilitli kategorileri bir kez hesaplar (her satırda tekrar kontrol edilmez) */
    private fun computeLocks(resetList: Boolean = false) {
        if (section == Section.FAV || section == Section.RECENT) { lockedIds = emptySet(); return }
        val kind = kindOf(section)
        val hidden = Settings.hiddenCats(pl.id)
        // Kullanıcının gizlediği kategoriler PIN açık olsa bile "Tümü" ve aramadan da çıkarılır
        val hiddenIds = sectionCats.filter { it.id != "*" && Parental.catKey(kind, it.id) in hidden }.map { it.id }.toSet()
        lockedIds = hiddenIds + (if (Parental.unlocked) emptySet()
        else sectionCats.filter { it.id != "*" && Parental.isLocked(pl.id, kind, it.id, it.name) }.map { it.id })
        hideAdultItems = !Parental.unlocked && Settings.adultMode != "off"
        // Gizlenen kategoriler ve "Tamamen gizle" modundaki yetişkin kategoriler listede görünmez;
        // "Tümü" sayısı da gizlenenler kadar azalır
        val hiddenCount = sectionCats.filter { it.id in hiddenIds }.sumOf { it.count }
        val visible = sectionCats.filter { it.id == "*" || !Parental.isCatHidden(pl.id, kind, it.id, it.name, hidden) }
            .map { if (it.id == "*" && hiddenCount > 0) it.copy(count = (it.count - hiddenCount).coerceAtLeast(0)) else it }
        if (resetList || visible.size != catAdapter.items.size) catAdapter.items = visible
        else catAdapter.notifyItemRangeChanged(0, catAdapter.itemCount)
    }
    /** Sunucunun is_adult işaretlediği tekil kanallar "Tümü" ve aramada gizlenir */
    private var hideAdultItems = false

    /** Kategoriye uzun basınca kilitle / kilidi kaldır (PIN gerekir) */
    private fun toggleCategoryLock(c: Category) {
        if (c.id == "*" || section == Section.FAV || section == Section.RECENT) return
        val doIt = {
            val key = Parental.catKey(kindOf(section), c.id)
            val set = Settings.lockedCats(pl.id).toMutableSet()
            val nowLocked = if (key in set) { set -= key; false } else { set += key; true }
            Settings.setLockedCats(pl.id, set)
            if (nowLocked) Parental.unlocked = false
            computeLocks(); applyFilter()
            Toast.makeText(this, if (nowLocked) "🔒 ${c.name} kilitlendi" else "🔓 ${c.name} kilidi kaldırıldı", Toast.LENGTH_SHORT).show()
        }
        if (Parental.unlocked) doIt() else Parental.askPin(this, "Kategori kilidi") { computeLocks(); doIt() }
    }

    private fun selectCategory(c: Category) {
        if (c.id in lockedIds) {
            Parental.askPin(this, "${c.name} kilitli") { computeLocks(); selectCategory(c) }
            return
        }
        // Yetişkin kanal önizlemedeyken başka kategoriye geçilirse önizleme kapanır (adı/görüntüsü kalmasın)
        previewItem?.let { pi -> if (c.id != pi.categoryId && Parental.isAdultItem(pi)) clearPreview() }
        currentCat = c
        catAdapter.selectedId = c.id
        applyFilter()
        if (section != Section.FAV && section != Section.RECENT) tvTitle.text = c.name
    }

    /** Canlı TV açıldıktan sonra Film ve Dizi listelerini arka planda hazırla (sekme geçişi anında olsun) */
    private var prefetched = false
    private fun prefetchOthers() {
        if (prefetched) return
        prefetched = true
        lifecycleScope.launch {
            for (k in listOf(Kind.MOVIE, Kind.SERIES)) try { src.categories(k) } catch (_: Exception) { }
        }
    }

    private fun applyFilter(debounce: Boolean = false) {
        val q = etSearch.text.toString().trim()
        val c = currentCat
        filterJob?.cancel()
        val locked = lockedIds
        if (q.isEmpty()) {
            when {
                c != null && c.id != "*" -> setShown(byCat[c.id] ?: allItems.filter { it.categoryId == c.id })
                section == Section.FAV || section == Section.RECENT -> setShown(
                    if (Parental.unlocked) allItems else allItems.filter { !Parental.isItemLocked(pl.id, it) })
                locked.isEmpty() && !hideAdultItems -> setShown(allItems)
                else -> {
                    val src0 = allItems; val ha = hideAdultItems
                    filterJob = lifecycleScope.launch {
                        setShown(withContext(Dispatchers.Default) { src0.filter { it.categoryId !in locked && !(ha && it.adult) } })
                    }
                }
            }
            return
        }
        // Arama tüm kategorilerde (kilitliler hariç), arka planda ve yazma bitince (300 ms) yapılır
        val source = if (section == Section.FAV || section == Section.RECENT)
            allItems.filter { !Parental.isItemLocked(pl.id, it) }
        else if (locked.isEmpty() && !hideAdultItems) allItems
        else allItems.filter { it.categoryId !in locked && !(hideAdultItems && it.adult) }
        filterJob = lifecycleScope.launch {
            if (debounce) delay(300)
            val res = withContext(Dispatchers.Default) { source.filter { it.name.contains(q, ignoreCase = true) } }
            setShown(res)
        }
    }

    private fun setShown(list: List<Item>) {
        shown = list
        (rvItems.adapter as? ItemAdapter)?.items = list
        rvItems.scrollToPosition(0)
        if (progress.visibility != View.VISIBLE) {
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            if (list.isEmpty()) tvEmpty.text = when (section) {
                Section.FAV -> "Favori yok. Bir öğeye uzun basarak favoriye ekleyin."
                Section.RECENT -> "Henüz izlenen yok."
                else -> "Sonuç yok"
            }
        }
    }

    private fun open(item: Item, pos: Int) {
        if (item.kind == Kind.SERIES) {
            Session.seriesItem = item
            startActivity(Intent(this, SeriesActivity::class.java))
            return
        }
        // Canlı TV (yatay): ilk OK önizlemede açar, aynı kanala tekrar OK tam ekran yapar
        if (item.kind == Kind.LIVE && section in previewSections && previewView != null) {
            if (previewItem?.key == item.key && previewPlayer != null) goFullscreen(item) else startPreview(item)
            return
        }
        goFullscreen(item)
    }

    private fun toggleFav(item: Item, pos: Int) {
        val added = store.toggleFav(pl.id, item)
        if (added) favKeys += item.key else favKeys -= item.key
        Toast.makeText(this, if (added) "⭐ Favorilere eklendi" else "Favorilerden çıkarıldı", Toast.LENGTH_SHORT).show()
        if (section == Section.FAV) showSection(Section.FAV)
        else if (pos >= 0) rvItems.adapter?.notifyItemChanged(pos)
        updatePreviewFav()
    }
}
