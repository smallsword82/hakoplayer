package com.hako.player

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    /** Satır yenilemek için: değer metnini döndüren fonksiyonlar */
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.list)
        build()
        list.getChildAt(2)?.requestFocus()
    }

    private fun build() {
        header("Genel")
        toggle("Açılışta son listeyi otomatik yükle", { Settings.autoLoad }) { Settings.autoLoad = it }
        row("Açılışta oynat", {
            when (Settings.startMode) {
                "last" -> "Son izlenen kanal"
                "channel" -> Session.playlist?.let { Settings.startChannel(it.id)?.name } ?: "Kanal seçilmedi"
                else -> "Kapalı"
            }
        }) {
            AlertDialog.Builder(this).setTitle("Açılışta oynat")
                .setItems(arrayOf("Kapalı", "Son izlenen kanal", "Seçtiğim kanal…")) { _, w ->
                    when (w) {
                        0 -> Settings.startMode = "off"
                        1 -> Settings.startMode = "last"
                        else -> pickChannel { ch ->
                            Settings.setStartChannel(Session.playlist!!.id, ch); Settings.startMode = "channel"
                            Toast.makeText(this, "Açılışta: ${ch.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    refresh()
                }.show()
        }
        row("Canlı TV'ye girince önizlemede aç", {
            when (Settings.liveEntry) {
                "first" -> "İlk kanal"
                "channel" -> Session.playlist?.let { Settings.liveEntryChannel(it.id)?.name } ?: "Kanal seçilmedi"
                else -> "Kapalı"
            }
        }) {
            AlertDialog.Builder(this).setTitle("Canlı TV'ye girince")
                .setItems(arrayOf("Kapalı", "Kategorinin ilk kanalı", "Seçtiğim kanal…")) { _, w ->
                    when (w) {
                        0 -> Settings.liveEntry = "off"
                        1 -> Settings.liveEntry = "first"
                        else -> pickChannel { ch ->
                            Settings.setLiveEntryChannel(Session.playlist!!.id, ch); Settings.liveEntry = "channel"
                            Toast.makeText(this, "Canlı TV'de: ${ch.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    refresh()
                }.show()
        }
        toggle("Menü geçiş sesleri", { Settings.navSounds }) { Settings.navSounds = it }
        choice("Görüntü oranı", listOf("Otomatik" to "fit", "16:9" to "16:9", "16:10" to "16:10", "4:3" to "4:3",
            "21:9" to "21:9", "5:4" to "5:4", "Ekranı doldur" to "fill", "Yakınlaştır" to "zoom"),
            { Settings.aspect }) { Settings.aspect = it }
        choice("Saat formatı", listOf("24 saat" to true, "12 saat (AM/PM)" to false), { Settings.clock24 }) { Settings.clock24 = it }
        choice("Kanal sıralaması",listOf("Sunucu sırası" to false, "A'dan Z'ye" to true), { Settings.sortAz }) {
            Settings.sortAz = it
        }

        header("Program rehberi (EPG)")
        toggle("Sunucu EPG vermezse harici rehberi kullan (epgshare01)", { Settings.extEpg }) { Settings.extEpg = it }
        row("EPG ülkeleri", {
            val sel = Settings.epgCountries
            (listOf("Türkiye") + ExtEpg.countries.keys.filter { it in sel && it != "TR" }.map { ExtEpg.countryNames[it] ?: it })
                .joinToString(", ") + "  (diğerleri kanal açılınca)"
        }) {
            // Yalnızca kullanıcının listesinde kanalı olan ülkeler; Türkiye her zaman indirilir
            val list = ExtEpg.countriesInList().filter { it != "TR" }
            if (list.isEmpty()) {
                Toast.makeText(this, "Listede başka ülke kanalı bulunamadı (önce Canlı TV'yi açın)", Toast.LENGTH_LONG).show()
                return@row
            }
            val sel = Settings.epgCountries.toMutableSet()
            AlertDialog.Builder(this).setTitle("Açılışta hazırlanacak ülkeler")
                .setMultiChoiceItems(list.map { ExtEpg.countryNames[it] ?: it }.toTypedArray(),
                    list.map { it in sel }.toBooleanArray()) { _, w, on -> if (on) sel += list[w] else sel -= list[w] }
                .setPositiveButton("Tamam") { _, _ -> Settings.epgCountries = sel; refresh() }
                .setNegativeButton("Vazgeç", null).show()
        }

        header("Oynatıcı")
        choice("Canlı yayın formatı", listOf("Otomatik" to "auto", "MPEG-TS (.ts)" to "ts", "HLS (.m3u8)" to "m3u8"),
            { Settings.liveFormat }) {
            Settings.liveFormat = it
            // Kanal adresleri yeni formatla yeniden oluşturulsun (liste diskten okunur, tekrar indirilmez)
            Session.playlist?.let { p -> Session.source = Session.sourceFor(p) }
        }
        choice("Kanal açılış hızı (başlangıç tamponu)",
            listOf("Çok hızlı (0.3 sn)" to 300, "Hızlı (0.5 sn)" to 500, "Dengeli (1.5 sn)" to 1500, "Kararlı (3 sn)" to 3000),
            { Settings.liveStartMs }) { Settings.liveStartMs = it }
        choice("Tampon bellek süresi",
            listOf("15 sn" to 15, "30 sn" to 30, "1 dk" to 60, "2 dk" to 120, "5 dk (canlıyı duraklatma için)" to 300),
            { Settings.bufferSec }) { Settings.bufferSec = it }
        row("User-Agent", { Settings.userAgent.ifBlank { "Varsayılan" } }) {
            val et = EditText(this).apply {
                setText(Settings.userAgent); hint = Net.DEFAULT_UA; setSingleLine()
            }
            AlertDialog.Builder(this).setTitle("Özel User-Agent").setView(et)
                .setMessage("Bazı sağlayıcılar belirli bir User-Agent ister. Boş bırakırsanız varsayılan kullanılır.")
                .setPositiveButton("Kaydet") { _, _ -> Settings.userAgent = et.text.toString().trim(); refresh() }
                .setNeutralButton("Varsayılan") { _, _ -> Settings.userAgent = ""; refresh() }
                .setNegativeButton("Vazgeç", null).show()
        }

        header("Liste")
        choice("Liste güncelleme sıklığı",
            listOf("Her açılışta" to 0, "6 saatte bir" to 6, "12 saatte bir" to 12, "Günde bir" to 24, "Haftada bir" to 168),
            { Settings.cacheHours }) { Settings.cacheHours = it }
        row("Önbelleği temizle", { "Liste ve resim önbelleği" }) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    Net.clearListCache()
                    imageLoader.diskCache?.clear()
                }
                imageLoader.memoryCache?.clear()
                Session.source?.clearCache()
                Toast.makeText(this@SettingsActivity, "Önbellek temizlendi", Toast.LENGTH_SHORT).show()
            }
        }

        header("Ebeveyn Kontrolü")
        row("Kategori kilitle", { lockSummary() }) { requirePin { pickLockedCategories() } }
        row("Kategori gizle", { hideSummary() }) { requirePin { pickHiddenCategories() } }
        row("Yetişkin içerik (otomatik)", {
            when (Settings.adultMode) { "hide" -> "Tamamen gizli"; "off" -> "Kapalı"; else -> "PIN ile kilitli" }
        }) {
            // Korumayı gevşetmek PIN ister
            requirePin {
                val opts = arrayOf("PIN ile kilitle" to "lock", "Tamamen gizle" to "hide", "Kapalı" to "off")
                AlertDialog.Builder(this).setTitle("Yetişkin içerik")
                    .setSingleChoiceItems(opts.map { it.first }.toTypedArray(),
                        opts.indexOfFirst { it.second == Settings.adultMode }) { d, w ->
                        Settings.adultMode = opts[w].second
                        Parental.unlocked = false
                        refresh(); d.dismiss()
                    }.show()
            }
        }
        row("PIN değiştir", { if (Settings.pinIsDefault) "⚠ Varsayılan PIN (0000) — değiştirin" else "Özel PIN ayarlı" }) {
            requirePin { changePin() }
        }
        row("Kilitleri şimdi kapat", { if (Parental.unlocked) "Kilitler şu an açık" else "Kilitli" }) {
            Parental.unlocked = false; refresh()
            Toast.makeText(this, "Kilitli içerik tekrar gizlendi", Toast.LENGTH_SHORT).show()
        }

        header("Hakkında")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        row("Sürüm", { "HakoPlayer $ver" }) {}
        row("Güncellemeleri denetle", { "Yeni sürüm varsa indirip kurar" }) { Updater.check(this, manual = true) }
        toggle("Açılışta güncelleme denetle", { Settings.autoUpdate }) { Settings.autoUpdate = it }
        row("Cihaz", { "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}" }) {}
        row("Yerel IP", { LocalServer.localIp() ?: "Ağ yok" }) {}
        row("Aktif liste", { Session.playlist?.name ?: "—" }) {}
    }

    /* ---------------- Ebeveyn kontrolü ---------------- */

    private fun requirePin(then: () -> Unit) {
        if (Parental.unlocked) then() else Parental.askPin(this, "Ebeveyn PIN'i") { then(); refresh() }
    }

    private fun lockSummary(): String {
        val pl = Session.playlist ?: return "Önce bir liste yükleyin"
        val n = Settings.lockedCats(pl.id).size
        return if (n == 0) "Kilitli kategori yok" else "$n kategori kilitli"
    }

    private fun hideSummary(): String {
        val pl = Session.playlist ?: return "Önce bir liste yükleyin"
        val n = Settings.hiddenCats(pl.id).size
        return if (n == 0) "Gizli kategori yok" else "$n kategori gizli"
    }

    private fun pickLockedCategories() = pickCategories("Kilitlenecek kategoriler", Settings::lockedCats) { pl, set ->
        Settings.setLockedCats(pl, set)
        Parental.unlocked = false // kaydettikten sonra kilitler devreye girsin
    }

    /** Gizlenen kategoriler PIN açık olsa bile listede, "Tümü"de, aramada, favori/son izlenende görünmez */
    private fun pickHiddenCategories() = pickCategories("Gizlenecek kategoriler", Settings::hiddenCats) { pl, set ->
        Settings.setHiddenCats(pl, set)
    }

    /** Bölüm seçtirir, o bölümün kategorilerini işaretli listeyle gösterir; seçim liste başına saklanır */
    private fun pickCategories(title: String, get: (String) -> Set<String>, save: (String, Set<String>) -> Unit) {
        val pl = Session.playlist; val src = Session.source
        if (pl == null || src == null) { Toast.makeText(this, "Önce bir liste yükleyin", Toast.LENGTH_SHORT).show(); return }
        val kinds = arrayOf("📺 Canlı TV" to Kind.LIVE, "🎬 Filmler" to Kind.MOVIE, "🎞 Diziler" to Kind.SERIES)
        AlertDialog.Builder(this).setTitle("Hangi bölüm?")
            .setItems(kinds.map { it.first }.toTypedArray()) { _, w ->
                val kind = kinds[w].second
                lifecycleScope.launch {
                    val cats = try { src.categories(kind).filter { it.id != "*" } } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Kategoriler yüklenemedi", Toast.LENGTH_SHORT).show(); return@launch
                    }
                    Session.rememberCats(kind, cats)
                    val current = get(pl.id).toMutableSet()
                    val keys = cats.map { Parental.catKey(kind, it.id) }
                    val checked = BooleanArray(cats.size) { keys[it] in current }
                    AlertDialog.Builder(this@SettingsActivity).setTitle(title)
                        .setMultiChoiceItems(cats.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton("Kaydet") { _, _ ->
                            keys.forEachIndexed { i, k -> if (checked[i]) current += k else current -= k }
                            save(pl.id, current)
                            refresh()
                        }
                        .setNegativeButton("Vazgeç", null).show()
                }
            }.show()
    }

    private fun changePin() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        fun field(h: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }.also { box.addView(it) }
        val a = field("Yeni PIN (4 hane)"); val c = field("Yeni PIN tekrar")
        AlertDialog.Builder(this).setTitle("PIN değiştir").setView(box)
            .setPositiveButton("Kaydet") { _, _ ->
                val p = a.text.toString()
                when {
                    p.length != 4 -> toast("PIN 4 haneli olmalı")
                    p != c.text.toString() -> toast("PIN'ler eşleşmiyor")
                    p == Settings.DEFAULT_PIN -> toast("Varsayılan PIN (0000) kullanılamaz")
                    else -> { Settings.setPin(p); toast("PIN değiştirildi"); refresh() }
                }
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /* ---------------- Satır yardımcıları ---------------- */

    private fun refresh() = refreshers.forEach { it() }

    private fun header(t: String) {
        list.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setPadding(8, 36, 8, 8)
        })
    }

    private fun row(title: String, value: () -> String, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_item)
            isFocusable = true; isClickable = true
            setPadding(28, 26, 28, 26)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 }
        }
        val t = TextView(this).apply {
            text = title; textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val v = TextView(this).apply {
            textSize = 15f; setTextColor(ContextCompat.getColor(context, R.color.muted)); gravity = Gravity.END
            maxLines = 2
        }
        r.addView(t); r.addView(v)
        val upd = { v.text = value() }
        upd(); refreshers += upd
        r.setOnClickListener { onClick(); refresh() }
        list.addView(r)
    }

    /** Açılış kanalı: önce kategori, sonra kanal seçilir (gizli / kilitli kategoriler listelenmez) */
    private fun pickChannel(onPick: (Item) -> Unit) {
        val src = Session.source; val pl = Session.playlist
        if (src == null || pl == null) {
            Toast.makeText(this, "Önce bir liste açın", Toast.LENGTH_SHORT).show(); return
        }
        lifecycleScope.launch {
            val cats = try { src.categories(Kind.LIVE) } catch (_: Exception) { emptyList() }
                .filter { it.id != "*" && it.count > 0 && !Parental.isCatHidden(pl.id, Kind.LIVE, it.id, it.name)
                    && !Parental.isLocked(pl.id, Kind.LIVE, it.id, it.name) }
            if (cats.isEmpty()) { Toast.makeText(this@SettingsActivity, "Kategori bulunamadı", Toast.LENGTH_SHORT).show(); return@launch }
            AlertDialog.Builder(this@SettingsActivity).setTitle("Kategori seçin")
                .setItems(cats.map { "${it.name}  (${it.count})" }.toTypedArray()) { _, ci ->
                    lifecycleScope.launch {
                        val all = try { src.items(Kind.LIVE) } catch (_: Exception) { emptyList() }
                        val chans = all.filter { it.categoryId == cats[ci].id && it.url != null && !Parental.isItemLocked(pl.id, it) }
                        if (chans.isEmpty()) return@launch
                        AlertDialog.Builder(this@SettingsActivity).setTitle(cats[ci].name)
                            .setItems(chans.map { it.name }.toTypedArray()) { _, k ->
                                onPick(chans[k]); refresh()
                            }.show()
                    }
                }.show()
        }
    }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        row(title, { if (get()) "✅ Açık" else "⬜ Kapalı" }) { set(!get()) }

    private fun <T> choice(title: String, options: List<Pair<String, T>>, get: () -> T, set: (T) -> Unit) =
        row(title, { options.firstOrNull { it.second == get() }?.first ?: "" }) {
            val cur = options.indexOfFirst { it.second == get() }
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), cur) { d, w ->
                    set(options[w].second); refresh(); d.dismiss()
                }.show()
        }
}
