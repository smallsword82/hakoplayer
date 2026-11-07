package com.hako.player

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val catAdapter = CategoryAdapter { selectCategory(it) }
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

        reloadFavKeys()
        val fav: (Item) -> Boolean = { it.key in favKeys }
        listAdapter = ItemAdapter(false, fav, ::open, ::toggleFav)
        gridAdapter = ItemAdapter(true, fav, ::open, ::toggleFav)

        navIds.forEach { (sec, id) -> findViewById<View>(id).setOnClickListener { showSection(sec) } }
        findViewById<View>(R.id.navBack).setOnClickListener { finish() }
        findViewById<View>(R.id.navReload).setOnClickListener {
            src.clearCache(); showSection(section)
            Toast.makeText(this, "Liste yenileniyor…", Toast.LENGTH_SHORT).show()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = applyFilter(debounce = true)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            findViewById<TextView>(R.id.tvAccount).text = try { src.accountInfo() } catch (e: Exception) { pl.name }
        }
        showSection(Section.LIVE)
        findViewById<View>(R.id.navLive).requestFocus()
    }

    private fun reloadFavKeys() {
        favKeys.clear(); store.favorites(pl.id).forEach { favKeys += it.key }
    }

    override fun onResume() {
        super.onResume()
        reloadFavKeys()
        // Favori yıldızları / son izlenenler güncel olsun
        if (section == Section.FAV || section == Section.RECENT) showSection(section)
        else rvItems.adapter?.notifyDataSetChanged()
    }

    private fun kindOf(sec: Section) = when (sec) {
        Section.LIVE -> Kind.LIVE; Section.MOVIE -> Kind.MOVIE; else -> Kind.SERIES
    }

    private fun showSection(sec: Section) {
        section = sec
        navIds.forEach { (s, id) -> findViewById<View>(id).isSelected = s == sec }
        etSearch.setText("")
        loadJob?.cancel()
        tvTitle.text = when (sec) {
            Section.LIVE -> "Canlı TV"; Section.MOVIE -> "Filmler"; Section.SERIES -> "Diziler"
            Section.FAV -> "Favoriler"; Section.RECENT -> "Son İzlenenler"
        }

        val grid = sec == Section.MOVIE || sec == Section.SERIES
        if (grid) {
            val dp = resources.displayMetrics.run { widthPixels / density }
            val listWidth = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) dp else dp - 450
            rvItems.layoutManager = GridLayoutManager(this, (listWidth / 140).toInt().coerceAtLeast(2))
            rvItems.adapter = gridAdapter
        } else {
            rvItems.layoutManager = LinearLayoutManager(this)
            rvItems.adapter = listAdapter
        }

        if (sec == Section.FAV || sec == Section.RECENT) {
            allItems = if (sec == Section.FAV) store.favorites(pl.id) else store.recents(pl.id)
            byCat = emptyMap()
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
                val items = src.items(kind)
                val cats = src.categories(kind).filter { it.id == "*" || it.count > 0 }
                byCat = withContext(Dispatchers.Default) { items.groupBy { it.categoryId } }
                allItems = items
                catAdapter.items = cats
                progress.visibility = View.GONE
                catAdapter.items.firstOrNull()?.let { selectCategory(it) }
                prefetchOthers()
            } catch (e: Exception) {
                tvEmpty.text = "Yüklenemedi: ${e.message}"
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun selectCategory(c: Category) {
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
        if (q.isEmpty()) {
            setShown(if (c == null || c.id == "*") allItems else byCat[c.id] ?: allItems.filter { it.categoryId == c.id })
            return
        }
        // Arama tüm kategorilerde, arka planda ve yazma bitince (300 ms) yapılır
        val source = allItems
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
        // Kanal geçişi için yalnızca aynı türden öğeleri sıraya koy
        val q = shown.filter { it.kind == item.kind && it.url != null }
        Session.queue = q
        Session.queueIndex = q.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFav(item: Item, pos: Int) {
        val added = store.toggleFav(pl.id, item)
        if (added) favKeys += item.key else favKeys -= item.key
        Toast.makeText(this, if (added) "⭐ Favorilere eklendi" else "Favorilerden çıkarıldı", Toast.LENGTH_SHORT).show()
        if (section == Section.FAV) showSection(Section.FAV) else rvItems.adapter?.notifyItemChanged(pos)
    }
}
