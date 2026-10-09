package com.zarinet.netcontrol.subscription

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * آخر حالة معروفة محلياً — موقّعة بمفتاح HMAC داخل Android Keystore (لا يمكن استخراجه).
 * أي تعديل يدوي على الملف يُبطل التوقيع فتُتجاهل النسخة المحلية. لا يُكتب هنا إلا ما أعاده الخادم.
 */
class SubscriptionCache(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("mikroadmin_sub", Context.MODE_PRIVATE)

    fun save(sub: Subscription, raw: String, wallNow: Long, uid: String) {
        val o = JSONObject().put("uid", uid).put("raw", raw).put("wall", wallNow).put("mono", 0L).put("maxWall", wallNow)
            .put("boot", bootMark()).put("elapsed", SystemClock.elapsedRealtime())
        write(o)
        runCatching { prefs.edit().putString("public_id", sub.publicId).apply() }
    }

    /** المعرف العام (للعرض فقط — لا يُستخدم لأي قرار). */
    fun publicId(): String? = prefs.getString("public_id", null)

    /** يقرأ النسخة الموقّعة ويحدّث العدّاد الرتيب وأكبر وقت شوهد. */
    fun load(wallNow: Long, uid: String?): CachedCheck? {
        val o = read() ?: return null
        if (uid == null || o.optString("uid") != uid) return null   // النسخة المحلية تخص حساباً آخر
        val sub = runCatching { SupabaseApi.parse(o.getString("raw")) }.getOrNull() ?: return null
        // العدّاد الرتيب: نضيف ما مرّ منذ آخر نقطة إن كنا في نفس الإقلاع
        val nowElapsed = SystemClock.elapsedRealtime()
        val sameBoot = o.optLong("boot") == bootMark()
        val add = if (sameBoot) (nowElapsed - o.optLong("elapsed")).coerceAtLeast(0) else 0L
        val mono = o.optLong("mono") + add
        val maxWall = maxOf(o.optLong("maxWall"), wallNow)
        o.put("mono", mono).put("maxWall", maxWall).put("boot", bootMark()).put("elapsed", nowElapsed)
        write(o)
        return CachedCheck(sub, o.optLong("wall"), mono, maxWall)
    }

    fun clear() = runCatching { prefs.edit().remove("data").remove("sig").remove("public_id").apply() }

    /** علامة الإقلاع الحالي (وقت الإقلاع التقريبي بالدقائق). */
    private fun bootMark(): Long = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 600_000L

    private fun write(o: JSONObject) {
        val data = o.toString()
        val sig = sign(data) ?: return          // بدون Keystore لا نحفظ شيئاً (التحقق يصبح من الخادم فقط)
        runCatching { prefs.edit().putString("data", data).putString("sig", sig).apply() }
    }

    private fun read(): JSONObject? {
        val data = prefs.getString("data", null) ?: return null
        val sig = prefs.getString("sig", null) ?: return null
        val expect = sign(data) ?: return null
        if (!java.security.MessageDigest.isEqual(expect.toByteArray(), sig.toByteArray())) return null
        return runCatching { JSONObject(data) }.getOrNull()
    }

    private fun sign(data: String): String? = runCatching {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(key())
        Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return g.generateKey()
    }

    private companion object { const val ALIAS = "mikroadmin_sub_hmac" }
}
