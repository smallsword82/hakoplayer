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
    fun build(ctx: Context, live: Boolean): ExoPlayer {
        val dsf = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.UA)
        val extractors = DefaultExtractorsFactory()
            // Anahtar kareyi beklemeden başlat + erişim birimlerini erken algıla (daha hızlı ilk görüntü)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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

    fun mediaItem(url: String): MediaItem = MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()
}
