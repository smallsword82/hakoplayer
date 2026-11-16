package com.hako.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Harici program rehberi (epgshare01.online). Sunucu EPG vermezse kanallar adlarına göre eşleştirilir.
 * Türkiye açılışta indirilir; diğer ülkeler o ülkenin kanalı ilk açıldığında. Her ülke günde bir güncellenir.
 * Belleğe ve diske yalnızca şimdiden sonraki ~30 saatin program başlıkları alınır (küçük kalsın).
 */
object ExtEpg {
    private const val BASE = "https://epgshare01.online/epgshare01/epg_ripper_"
    private const val MAX_AGE = 24 * 3600_000L

    /** Kategori öneki -> epgshare dosyaları. Burada olmayan ülkeler (ör. AZ, IR) için EPG yok. */
    val countries: Map<String, List<String>> = linkedMapOf(
        "TR" to listOf("TR1", "TR3"), "DE" to listOf("DE1"), "UK" to listOf("UK1"), "US" to listOf("US2"),
        "CA" to listOf("CA2"), "FR" to listOf("FR1"), "NL" to listOf("NL1"), "BE" to listOf("BE2"),
        "AT" to listOf("AT1"), "CH" to listOf("CH1"), "IT" to listOf("IT1"), "ES" to listOf("ES1"),
        "PT" to listOf("PT1"), "PL" to listOf("PL1"), "RO" to listOf("RO1", "RO2"), "BG" to listOf("BG1"),
        "GR" to listOf("GR1"), "AL" to listOf("AL1"), "EX" to listOf("RS1", "HR1", "BA1", "SPORTKLUB1"),
        "DK" to listOf("DK1"), "SE" to listOf("SE1"), "NO" to listOf("NO1"), "RU" to listOf("viva-russia.ru"),
        "AR" to listOf("AE1", "SA1", "SA2", "EG1", "ALJAZEERA1", "BEIN1"),
    )
    val countryNames = mapOf(
        "TR" to "Türkiye", "DE" to "Almanya", "UK" to "İngiltere", "US" to "ABD", "CA" to "Kanada", "FR" to "Fransa",
        "NL" to "Hollanda", "BE" to "Belçika", "AT" to "Avusturya", "CH" to "İsviçre", "IT" to "İtalya", "ES" to "İspanya",
        "PT" to "Portekiz", "PL" to "Polonya", "RO" to "Romanya", "BG" to "Bulgaristan", "GR" to "Yunanistan",
        "AL" to "Arnavutluk", "EX" to "Eski Yugoslavya", "DK" to "Danimarka", "SE" to "İsveç", "NO" to "Norveç",
        "RU" to "Rusya", "AR" to "Arap ülkeleri",
    )

    private class Prog(val start: Long, val end: Long, val title: String)

    /** ülke -> (eşleştirme anahtarı -> programlar) */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, Map<String, List<Prog>>>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val dir get() = File(Net.cacheDir, "epg").apply { mkdirs() }

    /** Sunucunun EPG vermediği kanallar (kimlik -> zaman); 1 saat boyunca sunucu bu kanal için tekrar sorulmaz */
    private val serverEmpty = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Önizleme / bilgi çubuğu için. Öncelik sunucuda: sunucu bu kanal için program verirse o gösterilir.
     * Harici rehber aynı anda hazırlanır, yalnızca sunucu boş dönerse kullanılır (beklemeden).
     */
    suspend fun shortEpg(item: Item, count: Int = 2): List<EpgEntry> = kotlinx.coroutines.coroutineScope {
        if (item.kind != Kind.LIVE) return@coroutineScope emptyList()
        val ext: kotlinx.coroutines.Deferred<List<EpgEntry>>? =
            if (Settings.extEpg) async { try { external(item, count) } catch (_: Exception) { emptyList<EpgEntry>() } } else null
        val skip = (serverEmpty[item.key] ?: 0L) > System.currentTimeMillis() - 3600_000L
        val server = if (skip) emptyList() else try { Session.source?.shortEpg(item).orEmpty() } catch (_: Exception) { emptyList() }
        if (server.isNotEmpty()) { serverEmpty.remove(item.key); ext?.cancel(); return@coroutineScope server }
        if (!skip) serverEmpty[item.key] = System.currentTimeMillis()
        ext?.await().orEmpty()
    }

    private suspend fun external(item: Item, count: Int): List<EpgEntry> {
        val c = countryOf(item) ?: return emptyList()
        val map = ensure(c) ?: return emptyList()
        val list = lookup(map, item.name) ?: return emptyList()
        val now = System.currentTimeMillis()
        return list.filter { it.end > now }.take(count).map { EpgEntry(it.title, it.start, it.end, "") }
    }

    /** Liste değişince sunucu yeniden denensin */
    fun resetServerProbe() = serverEmpty.clear()

    /** Kanalın ülkesi: kategori adındaki önek ("DE-Sports", "EX-YU"), yoksa kanal adındaki önek ("TR: ...") */
    fun countryOf(item: Item): String? {
        val cat = Session.catNames[Parental.catKey(Kind.LIVE, item.categoryId)]
        prefix(cat)?.let { return it }
        prefix(item.name)?.let { return it }
        return if (cat == null) "TR" else null // kategorisiz listeler (ör. ücretsiz TR listesi)
    }

    private val prefixRe = Regex("""^\s*([A-Za-z]{2})\s*[-:|]""")
    fun prefix(s: String?): String? {
        val p = prefixRe.find(s ?: return null)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: return null
        val k = if (p == "GB") "UK" else p
        return k.takeIf { it in countries }
    }

    /** Kullanıcının listesinde kanalı olan ve EPG dosyası bulunan ülkeler */
    fun countriesInList(): List<String> {
        val have = Session.catNames.entries.filter { it.key.startsWith("LIVE:") }.mapNotNull { prefix(it.value) }.toSet()
        return countries.keys.filter { it in have }
    }

    /** Açılışta: Türkiye + ayarlarda seçilen ülkeler arka planda hazırlanır */
    suspend fun preload() {
        if (!Settings.extEpg) return
        (listOf("TR") + Settings.epgCountries).distinct().forEach { c -> try { ensure(c) } catch (_: Exception) { } }
    }

    /** Ülkenin rehberi: bellekte yoksa diskteki özetten, o da eski/yoksa indirip işleyerek */
    private suspend fun ensure(c: String): Map<String, List<Prog>>? {
        loaded[c]?.let { return it }
        val files = countries[c] ?: return null
        return locks.getOrPut(c) { Mutex() }.withLock {
            loaded[c]?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val cache = File(dir, "$c.v5.bin") // biçim değişince sürüm artar, eski özet kullanılmaz
                val map = (if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE)
                    try { readCache(cache) } catch (_: Exception) { null } else null)
                    ?: download(files).also { if (it.isNotEmpty()) writeCache(cache, it) }
                loaded[c] = map
                map
            }
        }
    }

    // ---------------- Eşleştirme ----------------

    private val tr = mapOf('İ' to 'I', 'I' to 'I', 'ı' to 'I', 'Ş' to 'S', 'ş' to 'S', 'Ğ' to 'G', 'ğ' to 'G',
        'Ü' to 'U', 'ü' to 'U', 'Ö' to 'O', 'ö' to 'O', 'Ç' to 'C', 'ç' to 'C')
    private val countryPrefix = Regex("""^\s*[A-Za-z]{2,3}\s*[:|]\s*""")
    private val brackets = Regex("""[(\[{].*?[)\]}]""")
    /** Küçük büyük harf / üst simge ile yazılmış etiketler ("ᶠᴴᴰ", "ʜᴅ"): ad kısmına dahil değil */
    private val fancy = Regex("[\u1D00-\u1DBF\uA700-\uA7FF\u2070-\u209F\u0250-\u02FF]+")
    private val noise = Regex("""\b(UHD|FHD|HD|SD|HQ|LQ|4K|8K|HEVC|H\.?265|H\.?264|1080[PI]?|720P|50FPS|60FPS|MOBIL|MOBILE|RAW|BACKUP|YEDEK|VIP|LIVE|CANLI|TR|TURKIYE|TURKEY|THE|CHANNEL|NETWORK|CANADA|EAST|WEST|PACIFIC|NATIONAL\s+FEED|FEED|STREAM|ALTERNATE)\b""")
    /** Yaygın kısaltmalar: listede "NAT GEO", rehberde "NATIONAL GEOGRAPHIC" */
    private val aliases = listOf(Regex("""\bNAT\s*GEO\b""") to "NATIONAL GEOGRAPHIC")

    /** "TR: beIN SPORTS 1 HD" ve "beIN.SPORTS.1.tr" -> "BEINSPORTS1" */
    fun norm(s0: String): String {
        var s = s0.replace(countryPrefix, "").replace(brackets, " ").replace(fancy, " ")
        s = String(s.map { tr[it] ?: it }.toCharArray()).uppercase(Locale.ROOT).replace('.', ' ').replace('_', ' ')
        aliases.forEach { (re, to) -> s = s.replace(re, to) }
        s = s.replace(noise, " ")
        return s.filter { it.isLetterOrDigit() }
    }

    /** XMLTV kimliği: "ATV.HD.tr" -> "ATV HD" (sondaki ülke kodu atılır) */
    private fun idName(id: String) = id.replace(Regex("""\.[a-zA-Z]{2}\d?$"""), "") // ".tr", ".ca2", ".us2"

    private fun lookup(map: Map<String, List<Prog>>, name: String): List<Prog>? {
        val k = norm(name)
        if (k.isEmpty()) return null
        map[k]?.let { return it }
        // "SHOW" <-> "SHOWTV" gibi küçük farklar
        if (k.endsWith("TV")) map[k.removeSuffix("TV")]?.let { return it } else map[k + "TV"]?.let { return it }
        // "BEINSPORTS1" <-> rehberde "BEINSPORTS"
        if (k.endsWith("1") && k.length > 2 && !k[k.length - 2].isDigit()) map[k.dropLast(1)]?.let { return it }
        return null
    }

    // ---------------- İndirme / XMLTV ----------------

    private fun download(files: List<String>): Map<String, List<Prog>> {
        val now = System.currentTimeMillis()
        val from = now - 3 * 3600_000L; val to = now + 30 * 3600_000L
        val keysOf = HashMap<String, MutableSet<String>>()      // kanal kimliği -> eşleştirme anahtarları
        // "dosya##kanal kimliği" -> programlar: aynı kanal iki dosyada farklı akışla olabilir, karıştırılmaz
        val progs = HashMap<String, MutableList<Prog>>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT)
        for (f in files) try {
            val req = Request.Builder().url("$BASE$f.xml.gz").header("User-Agent", "HakoPlayer").build()
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use
                val p = android.util.Xml.newPullParser()
                // Dosyalar UTF-8 BOM ile başlıyor; ayrıştırıcı kabul etmediği için atlanır
                val rd = java.io.PushbackReader(GZIPInputStream(r.body!!.byteStream()).buffered(64 * 1024).reader(Charsets.UTF_8), 1)
                rd.read().let { c -> if (c != 0xFEFF && c >= 0) rd.unread(c) }
                p.setInput(rd)
                var ch: String? = null; var start = 0L; var end = 0L; var inProg = false
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev != XmlPullParser.START_TAG) { if (ev == XmlPullParser.END_TAG && p.name == "programme") inProg = false; continue }
                    when (p.name) {
                        "channel" -> {
                            ch = p.getAttributeValue(null, "id") ?: continue
                            keysOf.getOrPut(ch!!) { HashSet() } += norm(idName(ch!!))
                        }
                        "display-name" -> if (!inProg) ch?.let { id -> keysOf.getOrPut(id) { HashSet() } += norm(p.nextText()) }
                        "programme" -> {
                            ch = p.getAttributeValue(null, "channel"); inProg = true
                            start = parse(fmt, p.getAttributeValue(null, "start"))
                            end = parse(fmt, p.getAttributeValue(null, "stop"))
                        }
                        "title" -> if (inProg) {
                            val t = p.nextText().trim()
                            val id = ch
                            if (id != null && t.isNotEmpty() && end > from && start < to) progs.getOrPut("$f##$id") { ArrayList() } += Prog(start, end, t)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        // Aynı anahtara birden çok kanal düşerse en çok programı olan kazanır
        val out = HashMap<String, List<Prog>>()
        for ((fid, list) in progs) {
            val id = fid.substringAfter("##")
            // Aynı kanal birden çok dosyada olabilir (TR1 + TR3): aynı saatte başlayan tekrarlar atılır
            val sorted = list.sortedBy { it.start }.distinctBy { it.start }
            for (k in (keysOf[id] ?: setOf(norm(idName(id))))) {
                if (k.isEmpty()) continue
                val old = out[k]
                if (old == null || old.size < sorted.size) out[k] = sorted
            }
        }
        return out
    }

    private fun parse(f: SimpleDateFormat, s: String?): Long = try { f.parse(s ?: "")?.time ?: 0 } catch (_: Exception) { 0 }

    private fun writeCache(file: File, map: Map<String, List<Prog>>) = try {
        DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(map.size)
            for ((k, l) in map) { o.writeUTF(k); o.writeInt(l.size); for (p in l) { o.writeLong(p.start); o.writeLong(p.end); o.writeUTF(p.title.take(200)) } }
        }
    } catch (_: Exception) { file.delete() }

    private fun readCache(file: File): Map<String, List<Prog>> = DataInputStream(file.inputStream().buffered()).use { i ->
        val n = i.readInt(); val m = HashMap<String, List<Prog>>(n * 2)
        repeat(n) { val k = i.readUTF(); val c = i.readInt(); m[k] = List(c) { Prog(i.readLong(), i.readLong(), i.readUTF()) } }
        m
    }
}
