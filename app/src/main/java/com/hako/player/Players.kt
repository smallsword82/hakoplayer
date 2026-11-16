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
        if (url != null && p.currentMediaItem?.localConfiguration?.uri?.toString().let { it == url || it == liveUrl(url) }) return p
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
        // Açılış: canlıda ayardaki süre (varsayılan 0.5 sn), film/dizide 1 sn veri gelince oynat (Media3 varsayılanı 2.5 sn)
        val startMs = if (live) Settings.liveStartMs else 1_000
        // Takılmadan sonra yeniden başlamak için: başlangıcın iki katı, 1-3 sn arası
        val rebufferMs = if (live) (startMs * 2).coerceIn(1_000, 3_000) else 2_000
        val maxMs = Settings.bufferSec * 1_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minOf(15_000, maxMs), maxMs, startMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf, extractors))
            .build()
    }


    /**
     * "Otomatik" yayın formatında sunucu bir kanalın MPEG-TS bağlantısını kestiyse o kanal bu oturum boyunca HLS ile açılır.
     * HLS'te yayın parça parça istendiği için kesinti görüntüye yansımaz; MPEG-TS ise kanalları daha hızlı açar.
     * Uygulama her açılışta (ve liste değişince) yine MPEG-TS ile başlar.
     */
    /** Yalnızca kesinti yaşanan kanallar HLS'e geçer; diğerleri MPEG-TS ile hızlı açılmaya devam eder */
    private val hlsChannels: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Yeni liste / yeni oturum: tüm kanallar yine MPEG-TS ile başlar */
    fun resetFallback() = hlsChannels.clear()

    private fun canFallback(url: String) = Settings.liveFormat == "auto" && Session.playlist?.type == "xtream" &&
        url.contains("/live/") && url.endsWith(".ts")

    /** Oynatılacak gerçek adres: kanal HLS'e geçtiyse Xtream canlı adresinin uzantısı .m3u8 olur */
    fun liveUrl(url: String): String = if (url in hlsChannels && canFallback(url)) url.dropLast(3) + ".m3u8" else url

    /** Canlı yayın bitti (sunucu kesti): bu kanal ilk kez kesildiyse HLS'e geç, değilse aynı adrese yeniden bağlan */
    fun onLiveEnded(p: ExoPlayer, url: String?): Boolean {
        if (url != null && url !in hlsChannels && canFallback(url)) {
            hlsChannels += url
            p.setMediaItem(mediaItem(url))
            p.prepare()
            p.playWhenReady = true
            return true
        }
        return reconnectLive(p)
    }

    /**
     * Canlı yayın "bitti" (sunucu bağlantıyı kapattı): aynı adrese yeniden bağlanır.
     * Bazı sunucular birkaç saniyelik veri gönderip bağlantıyı keser; eskiden görüntü burada donup kalıyordu.
     * Yayın gerçekten kapalıysa sonsuz döngüye girmesin: 30 sn içinde 5 denemeden sonra false döner.
     */
    fun reconnectLive(p: ExoPlayer): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        reconnects.removeAll { now - it > 30_000 }
        if (reconnects.size >= 5) return false
        reconnects += now
        val item = p.currentMediaItem ?: return false
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        return true
    }
    private val reconnects = ArrayList<Long>()

    /** Devralınan oynatıcı hata/boşta durumundaysa yeniden hazırla */
    fun revive(p: ExoPlayer) {
        if (p.playerError != null || p.playbackState == androidx.media3.common.Player.STATE_IDLE) p.prepare()
        p.playWhenReady = true
    }

    fun mediaItem(url0: String): MediaItem {
        val url = liveUrl(url0)
        return MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
    }
}
