package com.hako.player

import android.content.Context
import org.json.JSONArray

/** Yerel kayıt: listeler, favoriler, son izlenenler, kaldığı yer */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hako", Context.MODE_PRIVATE)

    /**
     * Listeler (sunucu, kullanıcı adı, şifre, M3U adresi) Android Keystore ile şifreli saklanır.
     * Eski sürümlerden kalan şifresiz kayıt ilk okumada şifrelenir. Çözülen liste bellekte tutulur.
     */
    fun playlists(): List<Playlist> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val raw = sp.getString("playlists", null)
            val list = if (raw == null) emptyList() else {
                val json = Crypto.decrypt(raw)
                if (json == null) emptyList() // anahtar kaybolduysa (ör. cihaz sıfırlama) listeler okunamaz
                else try {
                    val a = JSONArray(json); (0 until a.length()).map { Playlist.from(a.getJSONObject(it)) }
                } catch (e: Exception) { emptyList() }
            }
            cache = list
            if (raw != null && !Crypto.isEncrypted(raw) && Crypto.available) writePlaylists(list)
            return list
        }
    }

    private fun writePlaylists(list: List<Playlist>) {
        synchronized(lock) {
            cache = list
            val json = JSONArray(list.map { it.toJson() }).toString()
            sp.edit().putString("playlists", Crypto.encrypt(json)).apply()
        }
    }

    fun savePlaylist(p: Playlist) = writePlaylists(playlists().filter { it.id != p.id } + p)

    fun deletePlaylist(id: String) {
        writePlaylists(playlists().filter { it.id != id })
        sp.edit().remove("fav_$id").remove("recent_$id").apply()
        if (sp.getString("last_playlist", null) == id) sp.edit().remove("last_playlist").apply()
    }

    companion object {
        private val lock = Any()
        @Volatile private var cache: List<Playlist>? = null
    }

    fun lastPlaylist(): Playlist? {
        val id = sp.getString("last_playlist", null) ?: return null
        return playlists().firstOrNull { it.id == id }
    }
    fun setLastPlaylist(id: String) = sp.edit().putString("last_playlist", id).apply()

    fun favorites(pl: String): List<Item> = arr("fav_$pl").map { Item.from(it) }
    fun isFav(pl: String, item: Item) = favorites(pl).any { it.key == item.key }
    /** @return yeni durum (true = favoriye eklendi) */
    fun toggleFav(pl: String, item: Item): Boolean {
        val cur = favorites(pl)
        val exists = cur.any { it.key == item.key }
        val next = if (exists) cur.filter { it.key != item.key } else cur + item
        sp.edit().putString("fav_$pl", Crypto.encrypt(JSONArray(next.map { it.toJson() }).toString())).apply()
        return !exists
    }

    /** Yetişkin içerik son izlenenlerde hiç görünmez (eski sürümlerden kalan kayıtlar da süzülür) */
    fun recents(pl: String): List<Item> = arr("recent_$pl").map { Item.from(it) }.filter { !Parental.isAdultItem(it) }
    fun addRecent(pl: String, item: Item) {
        if (Parental.isAdultItem(item)) return
        val next = (listOf(item) + recents(pl).filter { it.key != item.key }).take(40)
        sp.edit().putString("recent_$pl", Crypto.encrypt(JSONArray(next.map { it.toJson() }).toString())).apply()
    }

    fun position(url: String): Long = sp.getLong("pos_${url.hashCode()}", 0L)
    fun setPosition(url: String, ms: Long) {
        sp.edit().putLong("pos_${url.hashCode()}", ms).apply()
    }

    private fun arr(key: String): List<org.json.JSONObject> {
        // Favori/son izlenen kayıtları yayın adreslerini (dolayısıyla kullanıcı adı/şifreyi) içerdiği için şifreli
        val s = sp.getString(key, null)?.let { Crypto.decrypt(it) } ?: return emptyList()
        return try {
            val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }
}
