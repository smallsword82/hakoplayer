package com.hako.player

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Program rehberi: solda kategoriler, ortada kanallar, sağda imlecin üstündeki kanalın yayın akışı.
 * OK kanalı tam ekran açar. Akış önce sunucudan, yoksa harici rehberden (epgshare01) gelir.
 */
class GuideActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var rvCats: RecyclerView
    private lateinit var rvChans: RecyclerView
    private lateinit var rvProgs: RecyclerView
    private val progAdapter = ProgAdapter()
    private lateinit var chanAdapter: ItemAdapter
    private val catAdapter = CategoryAdapter(
        isLocked = { c -> Session.playlist?.let { Parental.isLocked(it.id, Kind.LIVE, c.id, c.name) } == true },
    ) { openCategory(it) }
    private var shownKey: String? = null
    private var progJob: Job? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val src = Session.source; val pl = Session.playlist
        if (src == null || pl == null) { finish(); return }
        setContentView(R.layout.activity_guide)
        store = Store(this)
        rvCats = findViewById(R.id.rvGuideCats)
        rvChans = findViewById(R.id.rvGuideChans)
        rvProgs = findViewById(R.id.rvGuideProgs)

        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        val favKeys = store.favorites(pl.id).map { it.key }.toHashSet()
        chanAdapter = ItemAdapter(false, { it.key in favKeys }, { _, pos -> play(pos) }, { _, _ -> })
        rvChans.layoutManager = LinearLayoutManager(this)
        rvChans.adapter = chanAdapter
        rvProgs.layoutManager = LinearLayoutManager(this)
        rvProgs.adapter = progAdapter
        listOf(rvCats, rvChans, rvProgs).forEach { (it.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false }

        // İmleç hangi kanala gelirse sağda onun akışı gösterilir
        rvChans.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null || now.parent !== rvChans) return@addOnGlobalFocusChangeListener
            val pos = rvChans.getChildAdapterPosition(now)
            chanAdapter.items.getOrNull(pos)?.let { showSchedule(it) }
        }

        lifecycleScope.launch {
            val raw = try { src.categories(Kind.LIVE) } catch (_: Exception) { emptyList() }
            Session.rememberCats(Kind.LIVE, raw)
            val hidden = Settings.hiddenCats(pl.id)
            val cats = raw.filter { it.id != "*" && it.count > 0 && !Parental.isCatHidden(pl.id, Kind.LIVE, it.id, it.name, hidden) }
            if (cats.isEmpty()) { Toast.makeText(this@GuideActivity, "Kategori bulunamadı", Toast.LENGTH_SHORT).show(); return@launch }
            catAdapter.items = cats
            val want = intent.getStringExtra("cat")
            val start = cats.firstOrNull { it.id == want && !Parental.isLocked(pl.id, Kind.LIVE, it.id, it.name) }
                ?: cats.first { !Parental.isLocked(pl.id, Kind.LIVE, it.id, it.name) }
            openCategory(start, intent.getStringExtra("key"))
        }
    }

    /** Kategorinin kanallarını listeler (kilitliyse PIN sorulur); imleç verilen kanala ya da ilk kanala gider */
    private fun openCategory(c: Category, focusKey: String? = null) {
        val src = Session.source ?: return
        val pl = Session.playlist ?: return
        if (Parental.isLocked(pl.id, Kind.LIVE, c.id, c.name)) {
            Parental.askPin(this, "${c.name} kilitli") { catAdapter.notifyItemRangeChanged(0, catAdapter.itemCount); openCategory(c, focusKey) }
            return
        }
        lifecycleScope.launch {
            val all = try { src.items(Kind.LIVE) } catch (_: Exception) { return@launch }
            val list = withContext(Dispatchers.Default) {
                all.filter { it.categoryId == c.id && it.url != null && !Parental.isItemLocked(pl.id, it) }
                    .let { l -> if (Settings.sortAz) l.sortedBy { it.name.lowercase() } else l }
            }
            catAdapter.selectedId = c.id
            findViewById<TextView>(R.id.tvGuideCat).text = "${c.name} (${list.size})"
            chanAdapter.items = list
            val pos = list.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)
            list.getOrNull(pos)?.let { showSchedule(it) }
            (rvChans.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, rvChans.height / 3)
            rvChans.post { rvChans.layoutManager?.findViewByPosition(pos)?.requestFocus() }
        }
    }

    /** Sağdaki akış: gün başlıklarıyla, oynayan program işaretli. Hızlı gezinmede gereksiz sorgu atılmaz. */
    private fun showSchedule(item: Item) {
        if (shownKey == item.key) return
        shownKey = item.key
        findViewById<TextView>(R.id.tvGuideName).text = item.name
        findViewById<ImageView>(R.id.ivGuideLogo).load(item.logo)
        val empty = findViewById<TextView>(R.id.tvGuideEmpty)
        progJob?.cancel()
        progJob = lifecycleScope.launch {
            delay(150)
            empty.text = "Yükleniyor…"; empty.visibility = View.VISIBLE
            progAdapter.set(emptyList())
            val list = try { ExtEpg.shortEpg(item, 80) } catch (_: Exception) { emptyList() }
            if (list.isEmpty()) { empty.text = "Bu kanal için program bilgisi yok"; return@launch }
            empty.visibility = View.GONE
            progAdapter.set(list)
            rvProgs.scrollToPosition(0)
        }
    }

    private fun play(pos: Int) {
        val list = chanAdapter.items
        if (pos !in list.indices) return
        Session.queue = list
        Session.queueIndex = pos
        Session.playlist?.let { store.addRecent(it.id, list[pos]) }
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private var clockJob: Job? = null

    override fun onResume() {
        super.onResume()
        // Tam ekrandan dönünce devredilen oynatıcı burada kullanılmaz: bağlantıyı kapat
        Players.take(null)
        // "▶ şu an" işareti dakikada bir güncel saate göre yenilenir
        clockJob?.cancel()
        clockJob = lifecycleScope.launch { while (true) { delay(60_000); progAdapter.refreshNow() } }
    }

    override fun onPause() {
        super.onPause()
        clockJob?.cancel()
    }

    /** Akış satırları: gün başlığı ya da "saat  program adı" */
    private class ProgAdapter : RecyclerView.Adapter<ProgAdapter.VH>() {
        private sealed class Row { class Day(val title: String) : Row(); class Prog(val e: EpgEntry) : Row() }
        private var rows: List<Row> = emptyList()
        private val time = Settings.timeFormat()
        private val dayFmt = SimpleDateFormat("d MMMM EEEE", Locale("tr"))

        fun set(list: List<EpgEntry>) {
            val out = ArrayList<Row>()
            var lastDay = -1
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR)
            for (e in list) {
                cal.timeInMillis = e.start
                val d = cal.get(Calendar.DAY_OF_YEAR)
                if (d != lastDay) {
                    lastDay = d
                    val name = when (d - today) { 0 -> "Bugün"; 1 -> "Yarın"; -1 -> "Dün"; else -> null }
                    out += Row.Day(listOfNotNull(name, dayFmt.format(Date(e.start))).joinToString("  •  "))
                }
                out += Row.Prog(e)
            }
            rows = out
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        /** Satırlar aynı kalır, yalnızca oynayan program işareti güncel saate göre yeniden çizilir */
        fun refreshNow() = notifyItemRangeChanged(0, itemCount)

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun getItemViewType(i: Int) = if (rows[i] is Row.Day) 0 else 1

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val dp = p.resources.displayMetrics.density
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (t == 0) {
                    textSize = 13f; setTextColor(0xFF7A8CFF.toInt())
                    setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                } else {
                    textSize = 15f
                    setPadding((8 * dp).toInt(), (7 * dp).toInt(), (8 * dp).toInt(), (7 * dp).toInt())
                    setBackgroundResource(R.drawable.bg_item)
                    isFocusable = true
                }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            when (val r = rows[i]) {
                is Row.Day -> h.tv.text = r.title
                is Row.Prog -> {
                    val now = System.currentTimeMillis()
                    val live = r.e.start <= now && now < r.e.end
                    h.tv.text = "${if (live) "▶ " else "    "}${time.format(Date(r.e.start))} - ${time.format(Date(r.e.end))}    ${r.e.title}"
                    h.tv.setTextColor(if (live) 0xFFFFFFFF.toInt() else 0xFFB8C0DA.toInt())
                    h.tv.setTypeface(null, if (live) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
            }
        }

        override fun getItemCount() = rows.size
    }
}
