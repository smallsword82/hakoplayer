package com.hako.player

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Hassas verileri (IPTV kullanıcı adı / şifre / M3U adresi) Android Keystore'daki
 * donanım destekli AES-256-GCM anahtarıyla şifreler. Anahtar cihazdan dışarı çıkarılamaz.
 * Android 5.x (API < 23) Keystore AES desteklemediği için orada veri şifresiz kalır.
 */
object Crypto {
    private const val ALIAS = "hako_master_v1"
    private const val PREFIX = "enc1:"
    private const val GCM_IV = 12
    private const val GCM_TAG = 128

    @Volatile private var cachedKey: SecretKey? = null

    val available: Boolean get() = Build.VERSION.SDK_INT >= 23 && key() != null

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build())
                }.generateKey()
            cachedKey = k
            k
        } catch (e: Exception) { null }
    }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    /** Şifreleyemezse (eski cihaz) düz metni döndürür */
    fun encrypt(plain: String): String {
        val k = key() ?: return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) { plain }
    }

    /** Şifresiz (eski sürüm) veriyi olduğu gibi döndürür; çözülemezse null */
    fun decrypt(s: String): String? {
        if (!isEncrypted(s)) return s
        val k = key() ?: return null
        return try {
            val raw = Base64.decode(s.substring(PREFIX.length), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG, raw, 0, GCM_IV))
            String(c.doFinal(raw, GCM_IV, raw.size - GCM_IV), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}

/** PIN'ler düz metin değil, tuzlu PBKDF2 özeti olarak saklanır */
object PinHash {
    private const val ITER = 20_000

    fun newSalt(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), ITER, 256)
        // PBKDF2WithHmacSHA1 Android 5.0'dan beri her cihazda var
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        spec.clearPassword()
        return out.joinToString("") { "%02x".format(it) }
    }

    /** Zamanlama saldırısına karşı sabit süreli karşılaştırma */
    fun matches(pin: String, salt: String, expected: String) =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())

    /** Hatalı deneme sayısına göre bekleme süresi: 5 hatadan sonra 30 sn, her seferinde iki katı, en fazla 15 dk */
    fun lockoutMs(failures: Int): Long =
        if (failures < 5) 0L else minOf(30_000L shl (failures - 5).coerceAtMost(5), 15 * 60_000L)
}
