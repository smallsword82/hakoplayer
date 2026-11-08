package com.hako.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** Tam ekran oynatıcı ve canlı TV önizlemesi için ortak ExoPlayer ayarları */
@OptIn(UnstableApi::class)
object Players {
    /**
     * Önizleme ⇄ tam ekran geçişinde devredilen canlı oynatıcı. Yayın kapatılıp yeniden açılmaz,
     * yalnızca görüntünün çizildiği pencere değişir. Alan taraf [take] ile alır; uymazsa bırakır.
     */
    private var handoff: ExoPlayer? = null

    fun give(p: ExoPlayer) { handoff?.takeIf { it !== p }?.release(); handoff = p }

    /** Devredilen oynatıcı bu adresi oynuyorsa onu verir; değilse serbest bırakır ve null döner */
    fun take(url: String?): ExoPlayer? {
        val p = handoff ?: return null
        handoff = null
        if (url != null && p.currentMediaItem?.localConfiguration?.uri?.toString() == url) return p
        p.release()
        return null
    }

    fun build(ctx0: Context, live: Boolean): ExoPlayer {
        // Oynatıcı ekranlar arasında devredilebildiği için aktivite değil uygulama bağlamı kullanılır
        val ctx = ctx0.applicationContext
        val dsf = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.UA)
        val extractors = DefaultExtractorsFactory()
            // Anahtar kareyi beklemeden başlat + erişim birimlerini erken algıla (daha hızlı ilk görüntü)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            // Önce cihazın kendi çözücüleri; çözemediği ses biçiminde (ör. MP2) FFmpeg devreye girer
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // Hızlı açılış: canlıda 0.5 sn, film/dizide 1 sn veri gelince oynat (varsayılan 2.5 sn)
        val startMs = if (live) 500 else 1_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, startMs, if (live) 1_500 else 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf, extractors))
            .build()
    }

    /** Devralınan oynatıcı hata/boşta durumundaysa yeniden hazırla */
    fun revive(p: ExoPlayer) {
        if (p.playerError != null || p.playbackState == androidx.media3.common.Player.STATE_IDLE) p.prepare()
        p.playWhenReady = true
    }

    fun mediaItem(url: String): MediaItem = MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()
}
