package com.hako.player

import android.content.Context
import org.json.JSONArray

/** Yerel kayıt: listeler, favoriler, son izlenenler, kaldığı yer */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hako", Context.MODE_PRIVATE)

    fun playlists(): List<Playlist> = arr("playlists").map { Playlist.from(it) }
    fun savePlaylist(p: Playlist) {
        val list = playlists().filter { it.id != p.id } + p
        sp.edit().putString("playlists", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
    fun deletePlaylist(id: String) {
        val list = playlists().filter { it.id != id }
        sp.edit().putString("playlists", JSONArray(list.map { it.toJson() }).toString())
            .remove("fav_$id").remove("recent_$id").apply()
    }

    fun favorites(pl: String): List<Item> = arr("fav_$pl").map { Item.from(it) }
    fun isFav(pl: String, item: Item) = favorites(pl).any { it.key == item.key }
    /** @return yeni durum (true = favoriye eklendi) */
    fun toggleFav(pl: String, item: Item): Boolean {
        val cur = favorites(pl)
        val exists = cur.any { it.key == item.key }
        val next = if (exists) cur.filter { it.key != item.key } else cur + item
        sp.edit().putString("fav_$pl", JSONArray(next.map { it.toJson() }).toString()).apply()
        return !exists
    }

    fun recents(pl: String): List<Item> = arr("recent_$pl").map { Item.from(it) }
    fun addRecent(pl: String, item: Item) {
        val next = (listOf(item) + recents(pl).filter { it.key != item.key }).take(40)
        sp.edit().putString("recent_$pl", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun position(url: String): Long = sp.getLong("pos_${url.hashCode()}", 0L)
    fun setPosition(url: String, ms: Long) {
        sp.edit().putLong("pos_${url.hashCode()}", ms).apply()
    }

    private fun arr(key: String): List<org.json.JSONObject> {
        val s = sp.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }
}
