package com.hako.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        Net.cacheDir = cacheDir
        Settings.init(this)
        NavSounds.install(this)
        // Oynatıcı kayıtları yayın adresini (içinde kullanıcı adı/şifre) logcat'e yazabilir: kapat
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        Thread {
            try { Net.pruneCache() } catch (_: Exception) { }
            try { Updater.cleanup(this) } catch (_: Exception) { }
        }.start()
    }

    /** Logolar/afişler: bellek + 200 MB disk önbelleği, RGB565 (yarı bellek), geçiş animasyonu yok */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
        .respectCacheHeaders(false)   // IPTV sunucuları çoğu zaman "no-cache" gönderir; yine de önbelleğe al
        .allowRgb565(true)
        .crossfade(false)
        .build()
}
