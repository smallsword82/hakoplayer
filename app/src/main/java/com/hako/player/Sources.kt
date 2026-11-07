package com.hako.player

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 11) HakoPlayer/1.0"
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sunucu hatası: HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }
}

interface Source {
    val title: String
    suspend fun accountInfo(): String
    suspend fun categories(kind: Kind): List<Category>
    suspend fun items(kind: Kind): List<Item>
    suspend fun seriesInfo(item: Item): SeriesInfo? = null
    suspend fun shortEpg(item: Item): List<EpgEntry> = emptyList()
    fun clearCache()
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/* -------------------------- XTREAM CODES -------------------------- */
class XtreamSource(private val p: Playlist) : Source {
    private val base = p.server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    private val api = "$base/player_api.php?username=${enc(p.user)}&password=${enc(p.pass)}"
    private val cacheItems = HashMap<Kind, List<Item>>()
    private val cacheCats = HashMap<Kind, List<Category>>()
    private var liveExt = "ts"
    override val title get() = p.name

    /** Giriş kontrolü; başarısızsa hata fırlatır */
    suspend fun login(): JSONObject {
        val txt = Net.get(api)
        val o = try { JSONObject(txt) } catch (e: Exception) { throw IOException("Geçersiz sunucu yanıtı") }
        val ui = o.optJSONObject("user_info") ?: throw IOException("Giriş başarısız")
        if (ui.optInt("auth", 0) != 1) throw IOException("Kullanıcı adı / şifre hatalı")
        val formats = ui.optJSONArray("allowed_output_formats")
        val f = (0 until (formats?.length() ?: 0)).map { formats!!.getString(it) }
        liveExt = if ("m3u8" in f && "ts" !in f) "m3u8" else "ts"
        return o
    }

    override suspend fun accountInfo(): String {
        val ui = login().getJSONObject("user_info")
        val exp = ui.optString("exp_date").toLongOrNull()
        val expTxt = if (exp == null) "Sınırsız" else
            java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(exp * 1000))
        return "${p.name}\nDurum: ${ui.optString("status")}\nBitiş: $expTxt\nBağlantı: ${ui.optString("active_cons")}/${ui.optString("max_connections")}"
    }

    private fun action(kind: Kind, cats: Boolean) = when (kind) {
        Kind.LIVE -> if (cats) "get_live_categories" else "get_live_streams"
        Kind.MOVIE -> if (cats) "get_vod_categories" else "get_vod_streams"
        else -> if (cats) "get_series_categories" else "get_series"
    }

    override suspend fun categories(kind: Kind): List<Category> {
        cacheCats[kind]?.let { return it }
        val a = JSONArray(Net.get("$api&action=${action(kind, true)}"))
        val items = items(kind)
        val counts = items.groupingBy { it.categoryId }.eachCount()
        val list = mutableListOf(Category("*", "Tümü", items.size))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("category_id")
            list += Category(id, o.optString("category_name"), counts[id] ?: 0)
        }
        cacheCats[kind] = list
        return list
    }

    override suspend fun items(kind: Kind): List<Item> {
        cacheItems[kind]?.let { return it }
        val txt = Net.get("$api&action=${action(kind, false)}")
        val list = withContext(Dispatchers.Default) {
            val a = JSONArray(txt)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                when (kind) {
                    Kind.LIVE -> {
                        val id = o.optString("stream_id")
                        Item(id, o.optString("name"), o.optString("stream_icon").ifBlank { null },
                            o.optString("category_id"), Kind.LIVE,
                            "$base/live/${p.user}/${p.pass}/$id.$liveExt",
                            epgId = id, num = o.optInt("num", i + 1))
                    }
                    Kind.MOVIE -> {
                        val id = o.optString("stream_id")
                        val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                        Item(id, o.optString("name"), o.optString("stream_icon").ifBlank { null },
                            o.optString("category_id"), Kind.MOVIE,
                            "$base/movie/${p.user}/${p.pass}/$id.$ext",
                            num = i + 1, extra = o.optString("rating").takeIf { it.isNotBlank() && it != "0" })
                    }
                    else -> Item(o.optString("series_id"), o.optString("name"),
                        o.optString("cover").ifBlank { null }, o.optString("category_id"), Kind.SERIES, null,
                        num = i + 1, extra = o.optString("rating").takeIf { it.isNotBlank() && it != "0" })
                }
            }
        }
        cacheItems[kind] = list
        return list
    }

    override suspend fun seriesInfo(item: Item): SeriesInfo {
        val o = JSONObject(Net.get("$api&action=get_series_info&series_id=${item.id}"))
        val info = o.optJSONObject("info") ?: JSONObject()
        val seasons = mutableListOf<Season>()
        val eps = o.opt("episodes")
        fun parseEps(season: String, arr: JSONArray): Season {
            val list = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val id = e.optString("id")
                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                val img = e.optJSONObject("info")?.optString("movie_image")?.ifBlank { null }
                Item(id, "${e.optInt("episode_num", i + 1)}. ${e.optString("title")}", img ?: item.logo,
                    item.id, Kind.EPISODE, "$base/series/${p.user}/${p.pass}/$id.$ext", num = e.optInt("episode_num", i + 1))
            }
            return Season(season, list)
        }
        when (eps) {
            is JSONObject -> eps.keys().asSequence().sortedBy { it.toIntOrNull() ?: 999 }
                .forEach { k -> seasons += parseEps(k, eps.getJSONArray(k)) }
            is JSONArray -> for (i in 0 until eps.length()) {
                val arr = eps.optJSONArray(i) ?: continue
                seasons += parseEps((i + 1).toString(), arr)
            }
        }
        val meta = listOf(info.optString("releaseDate"), info.optString("genre"),
            info.optString("rating").let { if (it.isNotBlank()) "★ $it" else "" })
            .filter { it.isNotBlank() }.joinToString(" • ")
        return SeriesInfo(info.optString("plot"), meta, info.optString("cover").ifBlank { item.logo }, seasons)
    }

    override suspend fun shortEpg(item: Item): List<EpgEntry> {
        if (item.kind != Kind.LIVE) return emptyList()
        return try {
            val o = JSONObject(Net.get("$api&action=get_short_epg&stream_id=${item.id}&limit=3"))
            val a = o.optJSONArray("epg_listings") ?: return emptyList()
            (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                EpgEntry(b64(e.optString("title")), e.optLong("start_timestamp") * 1000,
                    e.optLong("stop_timestamp") * 1000, b64(e.optString("description")))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun b64(s: String) = try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { s }

    override fun clearCache() { cacheItems.clear(); cacheCats.clear() }
}

/* ------------------------------ M3U ------------------------------- */
class M3USource(private val p: Playlist, private val rawText: String? = null) : Source {
    private var parsed: List<Item>? = null
    override val title get() = p.name

    override suspend fun accountInfo() = "${p.name}\nM3U listesi\n${all().size} kayıt"

    private suspend fun all(): List<Item> {
        parsed?.let { return it }
        val text = rawText ?: Net.get(p.url)
        if (!text.trimStart().startsWith("#EXTM3U") && !text.contains("#EXTINF"))
            throw IOException("Geçerli bir M3U listesi değil")
        val list = withContext(Dispatchers.Default) { parse(text) }
        parsed = list
        return list
    }

    private fun attr(line: String, name: String): String? =
        Regex("""$name="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.ifBlank { null }

    private fun parse(text: String): List<Item> {
        val out = ArrayList<Item>()
        var info: String? = null
        var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF", true) -> info = line
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> {}
                info != null -> {
                    val name = info.substringAfterLast(',').trim().ifEmpty { "Kanal" }
                    val g = attr(info, "group-title") ?: group ?: "Diğer"
                    val lower = line.lowercase()
                    val kind = when {
                        "/movie/" in lower -> Kind.MOVIE
                        "/series/" in lower -> Kind.EPISODE
                        Regex("""\.(mp4|mkv|avi|mov)(\?|$)""").containsMatchIn(lower) -> Kind.MOVIE
                        else -> Kind.LIVE
                    }
                    out += Item(out.size.toString(), name, attr(info, "tvg-logo"), g, kind, line,
                        epgId = attr(info, "tvg-id"), num = out.size + 1)
                    info = null; group = null
                }
            }
        }
        return out
    }

    // Diziler M3U'da bölüm bölüm gelir; "Diziler" bölümünde oynatılabilir öğe olarak gösterilir
    private fun match(kind: Kind, it: Item) = if (kind == Kind.SERIES) it.kind == Kind.EPISODE else it.kind == kind

    override suspend fun categories(kind: Kind): List<Category> {
        val items = items(kind)
        val groups = LinkedHashMap<String, Int>()
        items.forEach { groups[it.categoryId] = (groups[it.categoryId] ?: 0) + 1 }
        return listOf(Category("*", "Tümü", items.size)) + groups.map { Category(it.key, it.key, it.value) }
    }

    override suspend fun items(kind: Kind) = all().filter { match(kind, it) }
    override fun clearCache() { if (rawText == null) parsed = null }
}
