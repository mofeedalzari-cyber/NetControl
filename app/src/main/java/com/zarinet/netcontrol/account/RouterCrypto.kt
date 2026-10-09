package com.zarinet.netcontrol.account

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * تشفير كلمة مرور الراوتر قبل رفعها للحساب (End-to-end):
 * المفتاح = PBKDF2-SHA256(كلمة مرور الحساب، الملح = معرف المستخدم، 120000 تكرار) → AES-256-GCM.
 * الخادم لا يرى كلمة مرور الراوتر ولا كلمة مرور الحساب. تسجيل الدخول على هاتف آخر بنفس الحساب يعيد اشتقاق نفس المفتاح.
 * الصيغة المخزنة: "v1:" + Base64(IV[12] + ciphertext).
 */
object RouterCrypto {
    private const val ITER = 120_000
    private val rnd = SecureRandom()

    fun deriveKey(accountPassword: String, userId: String): ByteArray {
        val spec = PBEKeySpec(accountPassword.toCharArray(), "mikroadmin-routers:$userId".toByteArray(Charsets.UTF_8), ITER, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun encrypt(plain: String, key: ByteArray): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "v1:" + B64.encode(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** يعيد null إذا تعذّر فك التشفير (مثلاً بعد تغيير كلمة مرور الحساب). */
    fun decrypt(enc: String, key: ByteArray): String? {
        if (enc.isEmpty()) return ""
        if (!enc.startsWith("v1:")) return null
        return runCatching {
            val b = B64.decode(enc.removePrefix("v1:"))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}

/** Base64 بسيط يعمل على كل إصدارات Android (API 24+) وفي اختبارات JVM. */
internal object B64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun encode(b: ByteArray): String = buildString {
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 255 shl 16) or ((if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0) shl 8) or (if (i + 2 < b.size) b[i + 2].toInt() and 255 else 0)
            append(A[n shr 18 and 63]); append(A[n shr 12 and 63])
            append(if (i + 1 < b.size) A[n shr 6 and 63] else '='); append(if (i + 2 < b.size) A[n and 63] else '=')
            i += 3
        }
    }
    fun decode(s: String): ByteArray {
        val clean = s.filter { it != '=' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (ch in clean) {
            val v = A.indexOf(ch); require(v >= 0) { "bad base64" }
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write(buf shr bits and 255) }
        }
        return out.toByteArray()
    }
}
