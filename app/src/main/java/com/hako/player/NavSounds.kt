package com.hako.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Menü geçiş sesleri (Ayarlar → "Menü geçiş sesleri"). Açıkken kumandanın yön tuşlarında ve OK'de
 * kısa sistem sesi çalınır. Her ekranın pencere geri çağrısı sarılır; açılır menüler [key] ile çağırır.
 */
object NavSounds {
    private var am: AudioManager? = null

    fun key(ctx: Context, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || !Settings.navSounds) return
        val fx = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> AudioManager.FX_KEY_CLICK
            else -> return
        }
        val a = am ?: (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { am = it }
        // Ses düzeyli sürüm, cihazın "sistem sesleri" ayarı kapalı olsa da çalar
        a.playSoundEffect(fx, 0.6f)
    }

    /** Uygulamadaki her ekranın tuş olaylarını dinler (ekran kodlarına dokunmadan) */
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {
            val w = a.window ?: return
            val inner = w.callback ?: return
            w.callback = Proxy.newProxyInstance(Window.Callback::class.java.classLoader,
                arrayOf(Window.Callback::class.java)) { _, m, args ->
                if (m.name == "dispatchKeyEvent" && args?.size == 1) (args[0] as? KeyEvent)?.let { key(a, it) }
                try {
                    if (args == null) m.invoke(inner) else m.invoke(inner, *args)
                } catch (e: InvocationTargetException) { throw e.targetException }
            } as Window.Callback
        }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    })
}
