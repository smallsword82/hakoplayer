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
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("hako_settings", Context.MODE_PRIVATE)
        migratePin()
    }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /* ---- Ebeveyn PIN'i: tuzlu PBKDF2 özeti, deneme sınırı ---- */
    const val DEFAULT_PIN = "0000"

    /** Eski sürümlerdeki düz metin PIN'i özete çevirip siler */
    private fun migratePin() {
        if (sp.contains("pin_hash")) return
        setPin(sp.getString("pin", null) ?: DEFAULT_PIN, isDefault = !sp.contains("pin"))
        sp.edit().remove("pin").apply()
    }

    fun setPin(pin: String, isDefault: Boolean = false) {
        val salt = PinHash.newSalt()
        sp.edit().putString("pin_salt", salt).putString("pin_hash", PinHash.hash(pin, salt))
            .putBoolean("pin_default", isDefault).putInt("pin_fail", 0).putLong("pin_lock_until", 0).apply()
    }

    val pinIsDefault: Boolean get() = bool("pin_default", true)

    /** PIN girişi şu an kilitliyse kalan süre (ms), değilse 0 */
    fun pinLockRemaining(): Long = (sp.getLong("pin_lock_until", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** PIN'i doğrular; hatalı denemeleri sayar ve gerekirse beklemeye alır */
    fun verifyPin(pin: String): Boolean {
        if (pinLockRemaining() > 0) return false
        val ok = PinHash.matches(pin, str("pin_salt", ""), str("pin_hash", ""))
        if (ok) sp.edit().putInt("pin_fail", 0).apply()
        else {
            val f = sp.getInt("pin_fail", 0) + 1
            sp.edit().putInt("pin_fail", f)
                .putLong("pin_lock_until", System.currentTimeMillis() + PinHash.lockoutMs(f)).apply()
        }
        return ok
    }
    /** Açılışta son listeyi otomatik yükle */
    var autoLoad: Boolean get() = bool("auto_load", true); set(v) = put("auto_load", v)
    /** Açılışta son izlenen kanalı otomatik oynat */
    var autoPlayLast: Boolean get() = bool("auto_play", false); set(v) = put("auto_play", v)
    /** Canlı TV'ye girince listedeki ilk kanal önizlemede kendiliğinden açılsın */
    var previewFirstLive: Boolean get() = bool("preview_first_live", true); set(v) = put("preview_first_live", v)
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

    /** Kullanıcının gizlediği kategoriler ("LIVE:12"); PIN açık olsa bile hiçbir yerde görünmez */
    fun hiddenCats(pl: String): Set<String> = sp.getStringSet("hidden_$pl", emptySet()) ?: emptySet()
    fun setHiddenCats(pl: String, set: Set<String>) = sp.edit().putStringSet("hidden_$pl", HashSet(set)).apply()

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

    /** Kategori listede görünmez mi: kullanıcı gizlediyse ya da "Tamamen gizle" modunda yetişkinse */
    fun isCatHidden(pl: String, kind: Kind, catId: String, catName: String? = null,
                    hidden: Set<String> = Settings.hiddenCats(pl)) =
        catKey(kind, catId) in hidden || isHidden(kind, catId, catName)

    /** Öğe kullanıcının gizlediği bir kategoride mi (favori / son izlenen / arama için) */
    fun isItemInHiddenCat(pl: String, item: Item, hidden: Set<String> = Settings.hiddenCats(pl)) =
        catKey(item.kind, item.categoryId) in hidden

    /** "Tümü" sayısından düşülecek: gizlenen kategorilerdeki öğe sayısı */
    fun hiddenItemCount(pl: String, kind: Kind, cats: List<Category>): Int {
        val hidden = Settings.hiddenCats(pl)
        if (hidden.isEmpty()) return 0
        return cats.filter { it.id != "*" && catKey(kind, it.id) in hidden }.sumOf { it.count }
    }

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

    /**
     * Öğe yetişkin içerik mi (PIN açık olsa bile). Son izlenenlere kaydedilmez, önizlemede
     * kategori dışına taşınmaz. Ebeveyn kontrolü "Kapalı" ise hiçbir şey yetişkin sayılmaz.
     */
    fun isAdultItem(item: Item, mode: String = Settings.adultMode): Boolean {
        if (mode == "off") return false
        return item.adult || isAdultName(item.name) || isAdultCat(item.kind, item.categoryId)
    }

    /** PIN sorar; doğruysa onOk çağrılır */
    fun askPin(ctx: Context, title: String = "PIN girin", onOk: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setSingleLine()
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT // şifre alanının eş aralıklı yazı tipi yerine
            gravity = android.view.Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setBackgroundResource(R.drawable.bg_field)
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        // Alan pencere kenarlarına yapışmasın
        val box = android.widget.FrameLayout(ctx).apply {
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(et, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val d = AlertDialog.Builder(ctx).setTitle("🔒 $title").setView(box)
            .setPositiveButton("Tamam") { _, _ ->
                val wait = Settings.pinLockRemaining()
                when {
                    wait > 0 -> Toast.makeText(ctx, "Çok fazla hatalı deneme. ${(wait + 999) / 1000} sn bekleyin.", Toast.LENGTH_LONG).show()
                    Settings.verifyPin(et.text.toString()) -> { unlocked = true; onOk() }
                    else -> {
                        val w = Settings.pinLockRemaining()
                        Toast.makeText(ctx, if (w > 0) "PIN hatalı. ${(w + 999) / 1000} sn bekleyin." else "PIN hatalı",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Vazgeç", null).create()
        d.show()
        et.requestFocus()
    }
}
