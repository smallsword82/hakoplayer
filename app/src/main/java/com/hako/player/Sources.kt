package com.hako.player

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Net {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11) HakoPlayer/1.0"
    /** Ayarlardan özel User-Agent girilebilir */
    val UA: String get() = Settings.userAgent.ifBlank { DEFAULT_UA }
    /** Liste önbelleği süresi (Ayarlar > Liste güncelleme). "Yenile" butonu önbelleği atlar. */
    val LIST_TTL: Long get() = Settings.cacheHours * 60 * 60 * 1000L

    /** Tüm liste önbelleğini siler */
    fun clearListCache() {
        cacheDir.listFiles()?.forEach { if (it.name.startsWith("list_")) it.delete() }
    }

    /** App.onCreate içinde atanır */
    lateinit var cacheDir: File

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .followRedirects(true).followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** Küçük yanıtlar için (giriş, EPG, dizi bilgisi) */
    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sunucu hatası: HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    fun cacheFile(url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "list_$h")
    }

    /**
     * Büyük listeler: diske indirir (önbellek) ve dosyadan akış halinde ayrıştırır.
     * Tüm yanıt hiçbir zaman tek bir String olarak belleğe alınmaz.
     */
    suspend fun <T> cached(url: String, notBefore: Long, parse: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val f = cacheFile(url)
        val mod = f.lastModified()
        if (f.exists() && mod >= notBefore && System.currentTimeMillis() - mod < LIST_TTL) {
            try { return@withContext f.inputStream().buffered(64 * 1024).use(parse) } catch (e: Exception) { f.delete() }
        }
        val tmp = File(f.path + ".tmp")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sunucu hatası: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Boş yanıt")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
        }
        try {
            val result = tmp.inputStream().buffered(64 * 1024).use(parse)
            tmp.renameTo(f)
            result
        } catch (e: Exception) {
            tmp.delete(); f.delete()
            throw IOException("Liste okunamadı: ${e.message}")
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
    /** Bir sonraki yüklemede sunucudan taze liste çeker */
    fun clearCache()
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/* ---------- JsonReader yardımcıları (akış halinde, düşük bellek) ---------- */
private fun JsonReader.anyString(): String = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); "" }
    else -> { skipValue(); "" }
}

/** Kök bir dizi ise her nesnenin alanlarını okur; dizi değilse (hata/boş) boş liste döner */
private inline fun <T> readObjectArray(inp: InputStream, wanted: Set<String>, map: (Map<String, String>, Int) -> T?): List<T> {
    val r = JsonReader(InputStreamReader(inp, Charsets.UTF_8))
    r.isLenient = true
    if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return emptyList() }
    val out = ArrayList<T>(1024)
    val fields = HashMap<String, String>(16)
    var i = 0
    r.beginArray()
    while (r.hasNext()) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
        fields.clear()
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            if (n in wanted) fields[n] = r.anyString() else r.skipValue()
        }
        r.endObject()
        map(fields, i++)?.let { out += it }
    }
    r.endArray()
    return out
}

private fun String?.orNull() = if (this.isNullOrBlank()) null else this
private fun isAdultFlag(v: String?) = v == "1" || v.equals("true", true)

/* -------------------------- XTREAM CODES -------------------------- */
class XtreamSource(private val p: Playlist) : Source {
    private val base = p.server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    private val api = "$base/player_api.php?username=${enc(p.user)}&password=${enc(p.pass)}"
    private val cacheItems = HashMap<Kind, List<Item>>()
    private val cacheCats = HashMap<Kind, List<Category>>()
    private val locks = Kind.values().associateWith { Mutex() }
    @Volatile private var refreshedAt = 0L
    @Volatile private var loginCache: JSONObject? = null
    private var liveExt = if (Settings.liveFormat == "m3u8") "m3u8" else "ts"
    override val title get() = p.name

    /** Giriş kontrolü; başarısızsa hata fırlatır */
    suspend fun login(): JSONObject {
        val txt = Net.get(api)
        val o = try { JSONObject(txt) } catch (e: Exception) { throw IOException("Geçersiz sunucu yanıtı") }
        val ui = o.optJSONObject("user_info") ?: throw IOException("Giriş başarısız")
        if (ui.optInt("auth", 0) != 1) throw IOException("Kullanıcı adı / şifre hatalı")
        val formats = ui.optJSONArray("allowed_output_formats")
        val f = (0 until (formats?.length() ?: 0)).map { formats!!.getString(it) }
        liveExt = when (Settings.liveFormat) {
            "ts" -> "ts"
            "m3u8" -> "m3u8"
            else -> if ("m3u8" in f && "ts" !in f) "m3u8" else "ts"
        }
        loginCache = o
        return o
    }

    override suspend fun accountInfo(): String {
        val ui = (loginCache ?: login()).getJSONObject("user_info")
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
        val items = items(kind)
        return locks.getValue(kind).withLock {
            cacheCats[kind]?.let { return@withLock it }
            val counts = HashMap<String, Int>()
            val adultIds = HashSet<String>()
            for (it in items) {
                counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1
                if (it.adult) adultIds += it.categoryId
            }
            val raw = Net.cached("$api&action=${action(kind, true)}", refreshedAt) { inp ->
                readObjectArray(inp, setOf("category_id", "category_name")) { f, _ ->
                    Category(f["category_id"].orEmpty(), f["category_name"].orEmpty())
                }
            }
            val list = ArrayList<Category>(raw.size + 1)
            list += Category("*", "Tümü", items.size)
            raw.forEach { it.count = counts[it.id] ?: 0; it.adult = it.id in adultIds; list += it }
            cacheCats[kind] = list
            list
        }
    }

    override suspend fun items(kind: Kind): List<Item> {
        cacheItems[kind]?.let { return it }
        return locks.getValue(kind).withLock {
            cacheItems[kind]?.let { return@withLock it }
            if (loginCache == null) try { login() } catch (_: Exception) { }
            val user = p.user; val pass = p.pass; val ext = liveExt
            val list: List<Item> = Net.cached("$api&action=${action(kind, false)}", refreshedAt) { inp ->
                when (kind) {
                    Kind.LIVE -> readObjectArray(inp, setOf("stream_id", "name", "stream_icon", "category_id", "num", "is_adult")) { f, i ->
                        val id = f["stream_id"].orEmpty()
                        Item(id, f["name"].orEmpty(), f["stream_icon"].orNull(), f["category_id"].orEmpty(), Kind.LIVE,
                            "$base/live/$user/$pass/$id.$ext", epgId = id, num = f["num"]?.toIntOrNull() ?: (i + 1),
                            adult = isAdultFlag(f["is_adult"]))
                    }
                    Kind.MOVIE -> readObjectArray(inp, setOf("stream_id", "name", "stream_icon", "category_id", "container_extension", "rating", "is_adult")) { f, i ->
                        val id = f["stream_id"].orEmpty()
                        val e = f["container_extension"].orNull() ?: "mp4"
                        Item(id, f["name"].orEmpty(), f["stream_icon"].orNull(), f["category_id"].orEmpty(), Kind.MOVIE,
                            "$base/movie/$user/$pass/$id.$e", num = i + 1,
                            extra = f["rating"]?.takeIf { it.isNotBlank() && it != "0" }, adult = isAdultFlag(f["is_adult"]))
                    }
                    else -> readObjectArray(inp, setOf("series_id", "name", "cover", "category_id", "rating")) { f, i ->
                        Item(f["series_id"].orEmpty(), f["name"].orEmpty(), f["cover"].orNull(), f["category_id"].orEmpty(),
                            Kind.SERIES, null, num = i + 1, extra = f["rating"]?.takeIf { it.isNotBlank() && it != "0" })
                    }
                }
            }
            cacheItems[kind] = list
            list
        }
    }

    override suspend fun seriesInfo(item: Item): SeriesInfo = withContext(Dispatchers.Default) {
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
        SeriesInfo(info.optString("plot"), meta, info.optString("cover").ifBlank { item.logo }, seasons)
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

    override fun clearCache() {
        cacheItems.clear(); cacheCats.clear(); loginCache = null
        refreshedAt = System.currentTimeMillis() // bundan eski önbellek kullanılmaz
    }
}

/* ------------------------------ M3U ------------------------------- */
class M3USource(private val p: Playlist) : Source {
    @Volatile private var parsed: List<Item>? = null
    private val byKind = HashMap<Kind, List<Item>>()
    private val lock = Mutex()
    @Volatile private var refreshedAt = 0L
    override val title get() = p.name

    override suspend fun accountInfo() = "${p.name}\nM3U listesi\n${all().size} kayıt"

    private suspend fun all(): List<Item> {
        parsed?.let { return it }
        return lock.withLock {
            parsed?.let { return@withLock it }
            val list = Net.cached(p.url, refreshedAt) { parse(it) }
            parsed = list
            list
        }
    }

    private fun parse(inp: InputStream): List<Item> {
        val out = ArrayList<Item>(4096)
        var info: String? = null
        var group: String? = null
        var sawHeader = false
        inp.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line[0] == '#') {
                    when {
                        line.startsWith("#EXTINF", true) -> { info = line; sawHeader = true }
                        line.startsWith("#EXTM3U", true) -> sawHeader = true
                        line.startsWith("#EXTGRP:", true) -> group = line.substring(8).trim()
                    }
                    continue
                }
                val inf = info ?: continue
                val attrs = parseAttrs(inf)
                val name = nameOf(inf).ifEmpty { "Kanal" }
                val g = attrs["group-title"] ?: group ?: "Diğer"
                out += Item(out.size.toString(), name, attrs["tvg-logo"], g, kindOf(line), line,
                    epgId = attrs["tvg-id"], num = out.size + 1)
                info = null; group = null
            }
        }
        if (!sawHeader) throw IOException("Geçerli bir M3U listesi değil")
        return out
    }

    /** Regex kullanmadan tek geçişte key="value" alanlarını okur */
    private fun parseAttrs(line: String): Map<String, String> {
        val m = HashMap<String, String>(8)
        var i = 0
        while (true) {
            val eq = line.indexOf("=\"", i)
            if (eq < 0) break
            var ks = eq - 1
            while (ks >= 0 && (line[ks].isLetterOrDigit() || line[ks] == '-' || line[ks] == '_')) ks--
            val end = line.indexOf('"', eq + 2)
            if (end < 0) break
            val v = line.substring(eq + 2, end)
            if (v.isNotBlank()) m[line.substring(ks + 1, eq).lowercase()] = v
            i = end + 1
        }
        return m
    }

    /** Kanal adı: son tırnaktan sonraki ilk virgülden sonrası (adda virgül olabilir) */
    private fun nameOf(line: String): String {
        val lastQuote = line.lastIndexOf('"')
        val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        return if (comma >= 0) line.substring(comma + 1).trim() else ""
    }

    private fun kindOf(url: String): Kind {
        val u = url.substringBefore('?').lowercase()
        return when {
            "/movie/" in u -> Kind.MOVIE
            "/series/" in u -> Kind.EPISODE
            u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".avi") || u.endsWith(".mov") -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    override suspend fun categories(kind: Kind): List<Category> {
        val items = items(kind)
        val groups = LinkedHashMap<String, Int>()
        for (it in items) groups[it.categoryId] = (groups[it.categoryId] ?: 0) + 1
        return listOf(Category("*", "Tümü", items.size)) + groups.map { Category(it.key, it.key, it.value) }
    }

    // Diziler M3U'da bölüm bölüm gelir; "Diziler" bölümünde oynatılabilir öğe olarak gösterilir
    override suspend fun items(kind: Kind): List<Item> {
        val all = all()
        synchronized(byKind) { byKind[kind]?.let { return it } }
        val want = if (kind == Kind.SERIES) Kind.EPISODE else kind
        val list = withContext(Dispatchers.Default) { all.filter { it.kind == want } }
        synchronized(byKind) { byKind[kind] = list }
        return list
    }

    override fun clearCache() {
        parsed = null
        synchronized(byKind) { byKind.clear() }
        refreshedAt = System.currentTimeMillis()
    }
}
