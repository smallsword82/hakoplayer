package com.hako.player

import org.json.JSONObject

enum class Kind { LIVE, MOVIE, SERIES, EPISODE }

data class Playlist(
    val id: String,
    val name: String,
    val type: String,          // "xtream" | "m3u"
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val url: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("server", server).put("user", user).put("pass", pass).put("url", url)

    companion object {
        fun from(o: JSONObject) = Playlist(
            o.getString("id"), o.optString("name"), o.optString("type"),
            o.optString("server"), o.optString("user"), o.optString("pass"), o.optString("url"),
        )
    }
}

data class Category(val id: String, val name: String, var count: Int = 0)

data class Item(
    val id: String,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val kind: Kind,
    val url: String?,          // dizi için null (bölümler ayrıca yüklenir)
    val epgId: String? = null,
    val num: Int = 0,
    val extra: String? = null, // puan / yıl vb.
) {
    val key: String = "${kind.name}:$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("logo", logo).put("cat", categoryId)
        .put("kind", kind.name).put("url", url).put("epg", epgId).put("num", num).put("extra", extra)

    companion object {
        fun from(o: JSONObject) = Item(
            o.getString("id"), o.optString("name"), o.optString("logo").ifEmpty { null },
            o.optString("cat"), Kind.valueOf(o.optString("kind", "LIVE")),
            o.optString("url").ifEmpty { null }, o.optString("epg").ifEmpty { null },
            o.optInt("num"), o.optString("extra").ifEmpty { null },
        )
    }
}

data class EpgEntry(val title: String, val start: Long, val end: Long, val desc: String)

data class Season(val number: String, val episodes: List<Item>)
data class SeriesInfo(val plot: String, val meta: String, val cover: String?, val seasons: List<Season>)

/** Ekranlar arası paylaşılan oturum durumu */
object Session {
    var playlist: Playlist? = null
    var source: Source? = null
    var queue: List<Item> = emptyList()
    var queueIndex: Int = 0
    var seriesItem: Item? = null
}
