package com.hako.player

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Locale

/** Uygulama ayarları (App.onCreate içinde init edilir) */
object Settings {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("hako_settings", Context.MODE_PRIVATE) }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** Ebeveyn kontrolü PIN'i (varsayılan 0000) */
    var pin: String get() = str("pin", "0000"); set(v) = put("pin", v)
    /** Açılışta son listeyi otomatik yükle */
    var autoLoad: Boolean get() = bool("auto_load", true); set(v) = put("auto_load", v)
    /** Açılışta son izlenen kanalı otomatik oynat */
    var autoPlayLast: Boolean get() = bool("auto_play", false); set(v) = put("auto_play", v)
    /** Canlı yayın formatı: auto | ts | m3u8 */
    var liveFormat: String get() = str("live_format", "auto"); set(v) = put("live_format", v)
    var clock24: Boolean get() = bool("clock24", true); set(v) = put("clock24", v)
    /** Kanal/film sıralaması: false = sunucu sırası, true = A-Z */
    var sortAz: Boolean get() = bool("sort_az", false); set(v) = put("sort_az", v)
    /** Liste önbellek süresi (saat). 0 = her açılışta güncelle */
    var cacheHours: Int get() = sp.getInt("cache_hours", 6); set(v) = sp.edit().putInt("cache_hours", v).apply()
    var userAgent: String get() = str("user_agent", ""); set(v) = put("user_agent", v)
    /**
     * Yetişkin içerik: "lock" = PIN ile kilitle (varsayılan), "hide" = tamamen gizle, "off" = kapalı.
     * Yetişkin sayılan: sunucunun is_adult işareti + kategori adında anahtar kelime.
     */
    var adultMode: String get() = str("adult_mode", "lock"); set(v) = put("adult_mode", v)

    fun lockedCats(pl: String): Set<String> = sp.getStringSet("locked_$pl", emptySet()) ?: emptySet()
    fun setLockedCats(pl: String, set: Set<String>) = sp.edit().putStringSet("locked_$pl", HashSet(set)).apply()

    fun timeFormat() = SimpleDateFormat(if (clock24) "HH:mm" else "hh:mm a", Locale.getDefault())
}

/** Ebeveyn kontrolü */
object Parental {
    /** PIN bir kez girilince uygulama kapanana kadar kilitler açık kalır */
    @Volatile var unlocked = false
    /**
     * Kategori/kanal adında aranan kelimeler (tam kelime eşleşmesi; "Sussex", "Essex" gibi adlar eşleşmez).
     * Türkçe, İngilizce, Almanca, Fransızca, İspanyolca, Arapça yaygın etiketler.
     */
    private val adult = Regex(
        """(?<![\p{L}\d])(xxx+|adults?|18\s?\+|\+\s?18|\[18\]|\(18\)|yeti[sş]kin(ler)?|porno?|porn|erotik|erotic[a]?|""" +
        """sex|sexy|hot\s?xxx|playboy|hustler|brazzers|private\s?tv|redlight|blue\s?hustler|dorcel|""" +
        """erwachsene|adulte?s?|adultos|para\s?adultos|\u0644\u0644\u0643\u0628\u0627\u0631|\+21|21\+)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE)
    /** Yanlış pozitif istisnaları */
    private val notAdult = Regex("""adult\s?swim|young\s?adult|adult\s?education""", RegexOption.IGNORE_CASE)

    fun isAdultName(name: String) = adult.containsMatchIn(name) && !notAdult.containsMatchIn(name)

    /** Kategori yetişkin mi (sunucu işareti veya adı) */
    fun isAdultCat(kind: Kind, catId: String, catName: String? = null): Boolean {
        val key = catKey(kind, catId)
        if (key in Session.adultCats) return true
        val name = catName ?: Session.catNames[key] ?: catId
        return isAdultName(name)
    }

    /** "Tamamen gizle" modunda yetişkin kategori listede hiç görünmez */
    fun isHidden(kind: Kind, catId: String, catName: String? = null) =
        !unlocked && Settings.adultMode == "hide" && isAdultCat(kind, catId, catName)

    fun kindKey(k: Kind) = if (k == Kind.EPISODE) Kind.SERIES.name else k.name
    fun catKey(kind: Kind, catId: String) = "${kindKey(kind)}:$catId"

    fun isLocked(pl: String, kind: Kind, catId: String, catName: String? = null): Boolean {
        if (unlocked) return false
        if (catKey(kind, catId) in Settings.lockedCats(pl)) return true
        return Settings.adultMode != "off" && isAdultCat(kind, catId, catName)
    }

    /** Tek öğe (favori/son izlenen/arama): sunucu işareti, kategori veya adı */
    fun isItemLocked(pl: String, item: Item): Boolean {
        if (unlocked) return false
        if (Settings.adultMode != "off" && (item.adult || isAdultName(item.name))) return true
        return isLocked(pl, item.kind, item.categoryId)
    }

    /** PIN sorar; doğruysa onOk çağrılır */
    fun askPin(ctx: Context, title: String = "PIN girin", onOk: () -> Unit) {
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4 haneli PIN"
            setPadding(48, 32, 48, 32)
        }
        val d = AlertDialog.Builder(ctx).setTitle("🔒 $title").setView(et)
            .setPositiveButton("Tamam") { _, _ ->
                if (et.text.toString() == Settings.pin) { unlocked = true; onOk() }
                else Toast.makeText(ctx, "PIN hatalı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null).create()
        d.show()
        et.requestFocus()
    }
}
