package com.hako.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {
    private var seasons: List<Season> = emptyList()
    private val seasonAdapter = CategoryAdapter { c -> showSeason(c.id) }.apply { horizontal = true }
    private lateinit var epAdapter: ItemAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val item = Session.seriesItem; val src = Session.source; val pl = Session.playlist
        if (item == null || src == null || pl == null) { finish(); return }
        setContentView(R.layout.activity_series)
        val store = Store(this)

        findViewById<TextView>(R.id.title).text = item.name
        findViewById<ImageView>(R.id.cover).load(item.logo)
        val rvSeasons = findViewById<RecyclerView>(R.id.rvSeasons)
        val rvEps = findViewById<RecyclerView>(R.id.rvEpisodes)
        rvSeasons.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvSeasons.adapter = seasonAdapter
        epAdapter = ItemAdapter(false, { false }, { ep, _ ->
            val list = epAdapter.items
            Session.queue = list
            Session.queueIndex = list.indexOf(ep).coerceAtLeast(0)
            store.addRecent(pl.id, item) // dizi son izlenenlere eklenir
            startActivity(Intent(this, PlayerActivity::class.java))
        }, { _, _ ->
            val added = store.toggleFav(pl.id, item)
            android.widget.Toast.makeText(this, if (added) "⭐ Dizi favorilere eklendi" else "Favorilerden çıkarıldı",
                android.widget.Toast.LENGTH_SHORT).show()
        })
        rvEps.layoutManager = LinearLayoutManager(this)
        rvEps.adapter = epAdapter

        val progress = findViewById<ProgressBar>(R.id.progress)
        lifecycleScope.launch {
            try {
                val info = src.seriesInfo(item) ?: return@launch
                findViewById<TextView>(R.id.plot).text = info.plot
                findViewById<TextView>(R.id.meta).text = info.meta
                info.cover?.let { findViewById<ImageView>(R.id.cover).load(it) }
                seasons = info.seasons
                seasonAdapter.items = seasons.map { Category(it.number, "Sezon ${it.number}", it.episodes.size) }
                seasons.firstOrNull()?.let { showSeason(it.number) }
                rvSeasons.post { rvSeasons.getChildAt(0)?.requestFocus() }
            } catch (e: Exception) {
                findViewById<TextView>(R.id.plot).text = "Yüklenemedi: ${e.message}"
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun showSeason(n: String) {
        seasonAdapter.selectedId = n
        epAdapter.items = seasons.firstOrNull { it.number == n }?.episodes ?: emptyList()
    }
}
