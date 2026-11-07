package com.hako.player

/**
 * Üçüncü taraf, herkese açık hazır listeler. Uygulama içerik sağlamaz; bu listeler
 * iptv-org topluluğu tarafından güncel tutulur ve doğrudan kaynağından okunur.
 * Adres tek yerde durur; ileride uzaktan ayar dosyasından okunacak şekilde değiştirilebilir.
 */
object FreeLists {
    const val TR_URL = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    const val TR_NAME = "Ücretsiz TR"
    const val DISCLAIMER = "Bu liste üçüncü taraf bir kaynaktan (iptv-org) gelir. " +
        "İçerik HakoPlayer'a ait değildir; kanalların çalışması garanti edilmez."

    fun isIptvOrg(url: String) = url.startsWith("https://iptv-org.github.io/")

    /** Kayıtlı listeler arasında Türkiye listesi (aynı adres) */
    fun findTr(lists: List<Playlist>) = lists.firstOrNull { it.type == "m3u" && it.url == TR_URL }

    private val groupNames = mapOf(
        "general" to "Genel", "news" to "Haber", "sports" to "Spor", "music" to "Müzik",
        "kids" to "Çocuk", "animation" to "Çocuk", "documentary" to "Belgesel", "religious" to "Dini",
        "entertainment" to "Eğlence", "movies" to "Sinema", "series" to "Dizi", "culture" to "Kültür",
        "education" to "Eğitim", "travel" to "Gezi", "lifestyle" to "Yaşam", "business" to "Ekonomi",
        "cooking" to "Yemek", "comedy" to "Komedi", "classic" to "Klasik", "weather" to "Hava Durumu",
        "outdoor" to "Doğa", "science" to "Bilim", "shop" to "Alışveriş", "auto" to "Otomobil",
        "legislative" to "Meclis", "relax" to "Rahatlama", "family" to "Aile", "undefined" to "Diğer",
    )

    /** "Animation;Kids" -> "Çocuk"; birden fazla grup varsa ilki kullanılır */
    fun group(raw: String): String {
        val first = raw.split(';').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "Diğer"
        return groupNames[first.lowercase()] ?: first
    }

    private val resolution = Regex("""\s*\(\d{3,4}[pi]\)""")
    private val country = Regex("""\s*\((Turkiye|Türkiye|Turkey)\)""", RegexOption.IGNORE_CASE)

    /** "ATV (Turkiye) (1080p) [Not 24/7]" -> "ATV (aralıklı yayın)" */
    fun cleanName(raw: String): String = raw
        .replace(resolution, "")
        .replace(country, "")
        .replace("[Not 24/7]", "(aralıklı yayın)")
        .replace("[Geo-blocked]", "(bölge kısıtlı)")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}
