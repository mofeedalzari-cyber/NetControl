package com.zarinet.netcontrol.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تخزين محلي مشفّر (AES-GCM بمفتاح داخل Android Keystore لا يمكن استخراجه) للجلسة ومفتاح تشفير كلمات مرور الراوترات.
 * إن لم يتوفر Keystore (بيئة اختبار) تُحفظ القيم في الذاكرة فقط.
 */
class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mikroadmin_secure", Context.MODE_PRIVATE)
    private val memory = HashMap<String, String>()

    fun put(key: String, value: String?) {
        if (value == null) { memory.remove(key); runCatching { prefs.edit().remove(key).apply() }; return }
        val enc = runCatching { encrypt(value) }.getOrNull()
        if (enc == null) memory[key] = value else runCatching { prefs.edit().putString(key, enc).apply() }
    }

    fun get(key: String): String? = memory[key] ?: prefs.getString(key, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun clear() { memory.clear(); runCatching { prefs.edit().clear().apply() } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "mikroadmin_secure_store" }
}
