package com.hako.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.cacheDir = cacheDir
        Settings.init(this)
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
