package com.zarinet.netcontrol.subscription

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * بصمة الجهاز المرسلة للخادم = SHA-256(ANDROID_ID + اسم الحزمة).
 * ANDROID_ID ثابت لنفس الجهاز ونفس مفتاح التوقيع، ولا يتغير بحذف التطبيق أو إعادة تثبيته أو مسح بياناته
 * (يتغير فقط بإعادة ضبط المصنع). لا يُرسل ANDROID_ID نفسه أبداً — فقط تجزئته.
 * المعرف العام @RG… يولَّد على الخادم ولا يعتمد على هذه البصمة.
 */
object DeviceIdentity {
    private const val BAD_ID = "9774d56d682e549c" // قيمة معروفة لأجهزة قديمة معطوبة

    @SuppressLint("HardwareIds")
    fun deviceHash(ctx: Context): String {
        val androidId = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        val base = if (androidId.isNullOrBlank() || androidId == BAD_ID) fallbackId(ctx) else androidId
        return hash("mikroadmin:$base:${ctx.packageName}")
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** احتياطي نادر: معرف تثبيت عشوائي (أضعف لأنه يُمسح مع البيانات). */
    private fun fallbackId(ctx: Context): String {
        val p = ctx.getSharedPreferences("mikroadmin_install", Context.MODE_PRIVATE)
        return p.getString("iid", null) ?: UUID.randomUUID().toString().also { id -> runCatching { p.edit().putString("iid", id).apply() } }
    }
}
