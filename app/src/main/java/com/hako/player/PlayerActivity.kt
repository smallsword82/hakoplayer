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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
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

    // CH+/CH- ile açılan kanal listesi
    private lateinit var channelPanel: View
    private lateinit var rvChannels: RecyclerView
    private lateinit var panelAdapter: ItemAdapter
    private val hidePanel = Runnable { hideChannelPanel() }
    private val panelBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideChannelPanel()
    }
    private val panelVisible get() = ::channelPanel.isInitialized && channelPanel.visibility == View.VISIBLE

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

        setupChannelPanel()

        player = Players.build(this, isLive).also { p ->
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
        p.setMediaItem(Players.mediaItem(url))
        if (!isLive) {
            val pos = store.position(url)
            if (pos > 30_000) {
                p.seekTo(pos)
                Toast.makeText(this, "Kaldığınız yerden devam ediliyor", Toast.LENGTH_SHORT).show()
            }
        }
        p.prepare()
        p.playWhenReady = true
        // Canlı yayında play/pause paneli yok: kanal değiştirirken ortaya çıkıp yön tuşlarını kapmasın
        view.useController = !isLive
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

    // ---------- Kanal listesi (CH+/CH-) ----------

    private fun setupChannelPanel() {
        channelPanel = findViewById(R.id.channelPanel)
        rvChannels = findViewById(R.id.rvChannels)
        val favKeys = Session.playlist?.let { pl -> store.favorites(pl.id).map { it.key }.toHashSet() } ?: hashSetOf()
        panelAdapter = ItemAdapter(false, { it.key in favKeys }, { _, pos -> pickChannel(pos) }, { _, _ -> })
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = panelAdapter
        (rvChannels.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        onBackPressedDispatcher.addCallback(this, panelBack)
    }

    private fun showChannelPanel(move: Int) {
        if (!isLive || Session.queue.size <= 1) return
        if (!panelVisible) {
            panelAdapter.items = Session.queue
            panelAdapter.activeKey = current?.key
            findViewById<TextView>(R.id.tvPanelTitle).text = "Kanallar (${Session.queue.size})"
            overlay.visibility = View.GONE
            view.hideController()
            view.useController = false
            channelPanel.visibility = View.VISIBLE
            panelBack.isEnabled = true
            focusPanelRow(Session.queueIndex)
        } else {
            val focused = rvChannels.focusedChild?.let { rvChannels.getChildAdapterPosition(it) } ?: Session.queueIndex
            focusPanelRow((focused + move).coerceIn(0, Session.queue.size - 1))
        }
        bumpPanelTimer()
    }

    private fun focusPanelRow(pos: Int) {
        val lm = rvChannels.layoutManager as LinearLayoutManager
        val v = lm.findViewByPosition(pos)
        if (v != null) { v.requestFocus(); return }
        // Görünmüyorsa ortaya kaydırıp çizilince odakla
        lm.scrollToPositionWithOffset(pos, rvChannels.height / 3)
        rvChannels.post { lm.findViewByPosition(pos)?.requestFocus() }
    }

    private fun bumpPanelTimer() {
        handler.removeCallbacks(hidePanel)
        handler.postDelayed(hidePanel, 8000)
    }

    private fun hideChannelPanel() {
        handler.removeCallbacks(hidePanel)
        if (!::channelPanel.isInitialized) return
        channelPanel.visibility = View.GONE
        panelBack.isEnabled = false
        view.useController = !isLive
        view.requestFocus()
    }

    private fun pickChannel(pos: Int) {
        hideChannelPanel()
        if (pos !in Session.queue.indices) return
        if (pos != Session.queueIndex) {
            Session.queueIndex = pos
            retries = 0
            play()
        } else showOverlay(autoHide = true)
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        view.resizeMode = resizeModes[resizeIdx]
        Toast.makeText(this, "Görüntü: ${resizeNames[resizeIdx]}", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Kanal listesi açıkken: CH± imleci gezdirir, yön tuşları ve OK listede çalışır
        if (panelVisible) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                bumpPanelTimer()
                when (event.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { showChannelPanel(-1); return true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { showChannelPanel(+1); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_MENU -> { hideChannelPanel(); return true }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controllerHidden = !view.isControllerFullyVisible
            when (event.keyCode) {
                // Canlı yayında CH+/CH-: şeffaf kanal listesini aç (diğerlerinde doğrudan geç)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (isLive && Session.queue.size > 1) showChannelPanel(0) else zap(-1); return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (isLive && Session.queue.size > 1) showChannelPanel(0) else zap(+1); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (isLive && controllerHidden) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (isLive && controllerHidden) { zap(+1); return true }
                KeyEvent.KEYCODE_MENU -> { cycleResize(); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (isLive) { onDigit(event.keyCode - KeyEvent.KEYCODE_0); return true }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showOverlay(true); return true }
                // Canlı yayında OK: kanal listesine (önizleme ekranına) dön
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (isLive && controllerHidden) {
                    finish(); return true
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
