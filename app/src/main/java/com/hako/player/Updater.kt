package com.hako.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Kendini güncelleme: GitHub'daki update/version.json okunur, daha yeni sürüm varsa sorulur,
 * APK indirilip Android'in paket yükleyicisiyle kurulur (kullanıcı ekranda onaylar).
 */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/smallsword82/hakoplayer/main/update/"

    class Info(val code: Long, val name: String, val notes: String, val apk: String)

    /** Uygulama açıkken otomatik denetim bir kez yapılır */
    private var autoChecked = false
    private var busy = false

    private fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    private suspend fun fetch(): Info? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + "version.json?t=" + System.currentTimeMillis() / 60_000).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val j = JSONObject(r.body?.string() ?: return@withContext null)
            val apk = j.optString("apk", "HakoPlayer.apk")
            Info(j.getLong("versionCode"), j.getString("versionName"), j.optString("notes"),
                if (apk.startsWith("http")) apk else BASE + apk)
        }
    }

    /** manual = Ayarlar'dan basıldı: güncel olduğunu ya da hata olduğunu da söyler */
    fun check(act: AppCompatActivity, manual: Boolean) {
        if (!manual && (autoChecked || !Settings.autoUpdate)) return
        if (busy) return
        autoChecked = true
        act.lifecycleScope.launch {
            val info = try { fetch() } catch (_: Exception) { null }
            if (info == null) {
                if (manual) toast(act, "Güncelleme bilgisi alınamadı")
                else autoChecked = false // ağ yoksa sonraki açılışta yine dene
                return@launch
            }
            if (info.code <= currentCode(act)) {
                if (manual) toast(act, "HakoPlayer güncel (${info.name})")
                return@launch
            }
            // Ekran o an görünmüyorsa (ör. açılışta kanal tam ekran açıldı) sonra sorulur
            if (!act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { autoChecked = false; return@launch }
            AlertDialog.Builder(act)
                .setTitle("Yeni sürüm: ${info.name}")
                .setMessage(info.notes.ifBlank { "HakoPlayer'ın yeni sürümü hazır." } + "\n\nŞimdi güncellensin mi?")
                .setPositiveButton("Güncelle") { _, _ -> download(act, info) }
                .setNegativeButton("Sonra", null)
                .show()
        }
    }

    private fun download(act: AppCompatActivity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            askInstallPermission(act); return
        }
        busy = true
        val tv = TextView(act).apply { setPadding(60, 40, 60, 20); textSize = 16f; text = "İndiriliyor…" }
        val dlg = AlertDialog.Builder(act).setTitle("HakoPlayer ${info.name}").setView(tv).setCancelable(false).show()
        act.lifecycleScope.launch {
            val file = File(act.cacheDir, "update.apk")
            val ok = try {
                withContext(Dispatchers.IO) {
                    Net.client.newCall(Request.Builder().url(info.apk).build()).execute().use { r ->
                        if (!r.isSuccessful) return@withContext false
                        val body = r.body ?: return@withContext false
                        val total = body.contentLength()
                        var done = 0L; var lastPct = -1
                        body.byteStream().use { ins -> file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { tv.text = "İndiriliyor… %$pct" } }
                            }
                        } }
                        total <= 0 || done == total
                    }
                }
            } catch (_: Exception) { false }
            if (!ok) { busy = false; dlg.dismiss(); toast(act, "İndirme başarısız, sonra tekrar deneyin"); return@launch }
            tv.text = "Kuruluyor…"
            try { withContext(Dispatchers.IO) { install(act, file) } }
            catch (e: Exception) { toast(act, "Kurulum başlatılamadı: ${e.message}") }
            busy = false
            dlg.dismiss()
        }
    }

    /** Android'in paket yükleyicisine verir; onay ekranını [Receiver] açar */
    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("hako.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, Receiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }

    private fun askInstallPermission(act: AppCompatActivity) {
        AlertDialog.Builder(act).setTitle("İzin gerekli")
            .setMessage("Güncellemeyi kurabilmek için HakoPlayer'a \"bilinmeyen uygulamaları yükleme\" izni verin, sonra tekrar Güncelle'ye basın.\n\n" +
                "Fire TV: Ayarlar › Benim Fire TV'm › Geliştirici seçenekleri › Bilinmeyen uygulamaları yükle › HakoPlayer")
            .setPositiveButton("İzin ekranını aç") { _, _ ->
                try {
                    act.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                } catch (_: Exception) { toast(act, "İzin ekranı açılamadı; ayarlardan elle verin") }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** Paket yükleyicinin sonucu: onay gerekiyorsa onay ekranı açılır */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> {}
                else -> Toast.makeText(ctx, "Güncelleme kurulamadı: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
