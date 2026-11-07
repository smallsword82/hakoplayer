package com.hako.player

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var overlay: View
    private lateinit var tvError: TextView
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private var epgJob: Job? = null
    private var retries = 0
    private var resizeIdx = 0
    private var lastZapAt = 0L
    private val startPending = Runnable { play() }
    private val retryRunnable = Runnable { player?.let { it.prepare(); it.playWhenReady = true } }
    private var digits = ""
    private val digitJump = Runnable { jumpToNumber() }
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    private val resizeNames = arrayOf("Sığdır", "Uzat", "Yakınlaştır")

    private val current get() = Session.queue.getOrNull(Session.queueIndex)
    private val isLive get() = current?.kind == Kind.LIVE

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Session.queue.isEmpty()) { finish(); return }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        store = Store(this)
        view = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        tvError = findViewById(R.id.tvError)

        findViewById<View>(R.id.btnPrev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { zap(+1) }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleResize() }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            if (v == View.VISIBLE) showOverlay(autoHide = false) else if (!isLive) overlay.visibility = View.GONE
        })

        val dsf = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.UA)
        val extractors = DefaultExtractorsFactory()
            // Anahtar kareyi beklemeden başlat + erişim birimlerini erken algıla (daha hızlı ilk görüntü)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        // Hızlı açılış: canlıda 0.5 sn, film/dizide 1 sn veri gelince oynat (varsayılan 2.5 sn)
        val startMs = if (isLive) 500 else 1_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, startMs, if (isLive) 1_500 else 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf, extractors))
            .build().also { p ->
                view.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = handleError(error)
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) { retries = 0; tvError.visibility = View.GONE }
                        if (state == Player.STATE_ENDED && !isLive) zap(+1, wrap = false)
                    }
                })
            }
        play()
    }

    private fun play() {
        val item = current ?: return
        val url = item.url ?: return
        val p = player ?: return
        handler.removeCallbacks(startPending)
        handler.removeCallbacks(retryRunnable)
        saveProgress()
        tvError.visibility = View.GONE
        // Önceki yayının bağlantısını hemen kapat (tek bağlantı hakkı olan hesaplarda önemli)
        p.stop()
        val mi = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(mi)
        if (!isLive) {
            val pos = store.position(url)
            if (pos > 30_000) {
                p.seekTo(pos)
                Toast.makeText(this, "Kaldığınız yerden devam ediliyor", Toast.LENGTH_SHORT).show()
            }
        }
        p.prepare()
        p.playWhenReady = true
        view.useController = true
        Session.playlist?.let { pl -> if (item.kind != Kind.EPISODE) store.addRecent(pl.id, item) }
        showOverlay(autoHide = true)
        loadEpg(item)
    }

    private fun handleError(e: PlaybackException) {
        val p = player ?: return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition(); p.prepare(); return
        }
        if (retries < 3) {
            retries++
            // İlk tekrar hemen, sonrakiler artan bekleme ile
            handler.postDelayed(retryRunnable, if (retries == 1) 300L else 1000L * retries)
            return
        }
        tvError.text = "Oynatılamadı (${e.errorCodeName})\n${current?.name ?: ""}\n\nYukarı/Aşağı ile kanal değiştirin"
        tvError.visibility = View.VISIBLE
    }

    private fun zap(dir: Int, wrap: Boolean = true) {
        val n = Session.queue.size
        if (n <= 1) return
        var i = Session.queueIndex + dir
        if (i !in 0 until n) { if (!wrap) return; i = (i + n) % n }
        Session.queueIndex = i
        retries = 0
        startWithDebounce()
    }

    /**
     * Tek basışta kanal anında açılır. Tuşa arka arkaya basılırsa (kanal tarama) sadece
     * kanal adı güncellenir; yayın, durulan kanalda açılır. Böylece her ara kanal için
     * sunucuya boşuna bağlantı açılmaz.
     */
    private fun startWithDebounce() {
        val now = android.os.SystemClock.uptimeMillis()
        val rapid = now - lastZapAt < 350
        lastZapAt = now
        handler.removeCallbacks(startPending)
        if (rapid) {
            showOverlay(autoHide = true)
            handler.postDelayed(startPending, 250)
        } else play()
    }

    private fun onDigit(d: Int) {
        if (!isLive || digits.length >= 5) return
        digits += d
        overlay.visibility = View.VISIBLE
        findViewById<TextView>(R.id.ovTitle).text = "Kanal: ${digits}_"
        findViewById<TextView>(R.id.ovEpg).text = ""
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(digitJump)
        handler.postDelayed(digitJump, 1200)
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull(); digits = ""
        if (n == null || n < 1 || n > Session.queue.size) {
            Toast.makeText(this, "Kanal bulunamadı", Toast.LENGTH_SHORT).show(); showOverlay(true); return
        }
        Session.queueIndex = n - 1
        retries = 0
        play()
    }

    private fun showOverlay(autoHide: Boolean) {
        val item = current ?: return
        val idx = Session.queueIndex + 1
        findViewById<TextView>(R.id.ovTitle).text = if (isLive) "$idx  ${item.name}" else item.name
        findViewById<ImageView>(R.id.ovLogo).load(item.logo)
        val showZap = Session.queue.size > 1
        findViewById<View>(R.id.btnPrev).visibility = if (showZap) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnNext).visibility = if (showZap) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        if (autoHide) handler.postDelayed(hideOverlay, 5000)
    }

    private fun loadEpg(item: Item) {
        val ep = findViewById<TextView>(R.id.ovEpg)
        ep.text = ""
        epgJob?.cancel()
        if (item.kind != Kind.LIVE) return
        epgJob = lifecycleScope.launch {
            val list = Session.source?.shortEpg(item).orEmpty()
            val f = Settings.timeFormat()
            ep.text = list.take(2).mapIndexed { i, e ->
                "${if (i == 0) "Şimdi" else "Sonra"}  ${f.format(Date(e.start))}-${f.format(Date(e.end))}  ${e.title}"
            }.joinToString("\n")
        }
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        view.resizeMode = resizeModes[resizeIdx]
        Toast.makeText(this, "Görüntü: ${resizeNames[resizeIdx]}", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controllerHidden = !view.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (isLive && controllerHidden) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (isLive && controllerHidden) { zap(+1); return true }
                KeyEvent.KEYCODE_MENU -> { cycleResize(); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (isLive) { onDigit(event.keyCode - KeyEvent.KEYCODE_0); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showOverlay(true); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (isLive && controllerHidden && overlay.visibility != View.VISIBLE) {
                    showOverlay(true); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveProgress() {
        val p = player ?: return
        val item = current ?: return
        val url = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        if (item.kind == Kind.LIVE) return
        val dur = p.duration
        val pos = p.currentPosition
        // Sona yakınsa sıfırla
        store.setPosition(url, if (dur > 0 && pos > dur - 60_000) 0 else pos)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
