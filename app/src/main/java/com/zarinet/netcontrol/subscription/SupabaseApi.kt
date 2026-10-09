package com.zarinet.netcontrol.subscription

import com.zarinet.netcontrol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل Supabase خفيف (REST/RPC + Auth) بدون مكتبات إضافية.
 * يستخدم anon key فقط (مفتاح عام بطبيعته). لا يوجد أي Service Role Key داخل التطبيق.
 */
open class SupabaseApi(
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    class ApiError(val code: Int, msg: String) : Exception(msg)

    open val configured: Boolean get() = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    /** استدعاء دالة RPC. [token] = JWT المستخدم المسجّل، وإلا anon. */
    open suspend fun rpc(fn: String, args: JSONObject, token: String? = null): String =
        request("POST", "/rest/v1/rpc/$fn", args.toString(), token)

    /** Supabase Auth: تسجيل دخول بالبريد وكلمة المرور → رد الجلسة كاملاً. */
    open suspend fun signInPassword(email: String, password: String): JSONObject =
        JSONObject(request("POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password).toString(), null))

    /** إنشاء حساب. إن كان تأكيد البريد معطلاً يعيد جلسة مباشرة، وإلا يعيد المستخدم فقط. */
    open suspend fun signUp(email: String, password: String): JSONObject =
        JSONObject(request("POST", "/auth/v1/signup", JSONObject().put("email", email).put("password", password).toString(), null))

    open suspend fun refresh(refreshToken: String): JSONObject =
        JSONObject(request("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refreshToken).toString(), null))

    /** دالة الخادم auth-helper (إنشاء حساب مؤكَّد، واستعادة كلمة المرور برمز). */
    open suspend fun authHelper(body: JSONObject): JSONObject =
        JSONObject(request("POST", "/functions/v1/auth-helper", body.toString(), null).ifBlank { "{}" })

    /** استدعاء Edge Function باسم [name] بصلاحية المستخدم ([token]). */
    open suspend fun function(name: String, body: JSONObject, token: String): JSONObject =
        JSONObject(request("POST", "/functions/v1/$name", body.toString(), token).ifBlank { "{}" })

    /** تغيير كلمة مرور المستخدم صاحب الجلسة. */
    open suspend fun updatePassword(token: String, newPassword: String): JSONObject =
        JSONObject(request("PUT", "/auth/v1/user", JSONObject().put("password", newPassword).toString(), token))

    open suspend fun signOut(token: String) { runCatching { request("POST", "/auth/v1/logout?scope=local", "{}", token) } }

    /** REST على جدول (تخضع لـ RLS بصلاحية المستخدم). */
    open suspend fun rest(method: String, pathAndQuery: String, body: String?, token: String, prefer: String? = null): String =
        request(method, "/rest/v1/$pathAndQuery", body, token, prefer)

    private suspend fun request(method: String, path: String, body: String?, token: String?, prefer: String? = null): String = withContext(Dispatchers.IO) {
        if (!configured) throw ApiError(0, "الخادم غير مُعد")
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") }
            else c.requestMethod = method
            c.connectTimeout = 8000
            // دوال Edge قد تستغرق وقتاً أطول
            c.readTimeout = if (path.startsWith("/functions/")) 30000 else 10000
            c.setRequestProperty("apikey", anonKey)
            c.setRequestProperty("Authorization", "Bearer ${token ?: anonKey}")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiError(if (isDeviceInUse(text)) DEVICE_IN_USE else code, errorMessage(code, path, text))
            text
        } catch (e: java.io.IOException) {
            throw ApiError(-1, "تعذر الاتصال بالخادم — تحقق من الإنترنت")
        } finally { c.disconnect() }
    }

    companion object {
        /** رمز خطأ داخلي: الحساب مسجّل دخول على جهاز آخر. */
        const val DEVICE_IN_USE = 409

        fun isDeviceInUse(text: String): Boolean = text.contains("device_in_use")

        /** رسائل أخطاء عربية واضحة من ردود Supabase. */
        fun errorMessage(code: Int, path: String, text: String): String {
            val o = runCatching { JSONObject(text) }.getOrNull()
            val raw = listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { k -> o?.optString(k)?.takeIf { it.isNotBlank() } }.orEmpty()
            val errCode = o?.optString("error_code").orEmpty()
            return when {
                raw.contains("device_in_use") -> {
                    val dev = raw.substringAfter("device_in_use:", "").trim()
                    "هذا الحساب مسجّل دخول على جهاز آخر" + (if (dev.isBlank()) "" else "\nالجهاز: \u200e$dev") +
                        "\nسجّل الخروج من الجهاز الآخر أولاً ثم حاول مرة أخرى."
                }
                raw.contains("admin only") -> "غير مصرح — هذا الحساب ليس مسؤولاً"
                errCode == "invalid_credentials" || raw.contains("Invalid login credentials") -> "البريد أو كلمة المرور غير صحيحة"
                errCode == "email_not_confirmed" || raw.contains("Email not confirmed") -> "لم يتم تأكيد البريد الإلكتروني بعد"
                errCode == "user_already_exists" || raw.contains("already registered") -> "هذا البريد مسجل مسبقاً — سجّل الدخول"
                errCode == "weak_password" || raw.contains("Password should") -> "كلمة المرور ضعيفة — 6 أحرف على الأقل"
                errCode == "signup_disabled" || raw.contains("Signups not allowed") -> "إنشاء الحسابات متوقف حالياً"
                errCode == "email_address_invalid" || raw.contains("invalid format") -> "البريد الإلكتروني غير صالح"
                errCode == "mail_not_configured" -> "إرسال البريد غير مُعد بعد — تواصل معنا عبر واتساب"
                errCode == "over_request_rate_limit" -> "محاولات كثيرة — حاول لاحقاً"
                errCode == "otp_expired" || raw.contains("expired or is invalid") -> "رمز التأكيد غير صحيح أو منتهي الصلاحية"
                errCode == "same_password" || raw.contains("different from the old") -> "كلمة المرور الجديدة يجب أن تختلف عن القديمة"
                errCode == "over_email_send_rate_limit" || raw.contains("security purposes") || code == 429 -> "محاولات كثيرة — انتظر دقيقة ثم أعد المحاولة"
                code == 401 -> "انتهت الجلسة — سجّل الدخول مجدداً"
                raw.isNotBlank() -> raw
                else -> "خطأ من الخادم ($code)"
            }
        }

        /** تحويل رد JSON من الخادم إلى نموذج Subscription. */
        fun parse(json: String): Subscription? {
            val t = json.trim()
            if (t.isEmpty() || t == "null") return null
            return parse(JSONObject(t))
        }

        fun parse(o: JSONObject): Subscription = Subscription(
            publicId = o.getString("public_id"),
            serverStatus = o.getString("status"),
            plan = o.optString("plan").takeIf { !o.isNull("plan") && it.isNotBlank() }?.let { c ->
                SubscriptionPlan.of(c) ?: SubscriptionPlan(c, 0, if (o.isNull("price_sar")) 0 else o.optInt("price_sar"),
                    o.optString("plan_name").takeIf { !o.isNull("plan_name") } ?: c) },
            planName = o.optString("plan_name").takeIf { !o.isNull("plan_name") && it.isNotBlank() },
            priceSar = if (o.isNull("price_sar")) null else o.optInt("price_sar"),
            trialEndsAt = ts(o, "trial_ends_at"),
            subscriptionStartedAt = ts(o, "subscription_started_at"),
            subscriptionEndsAt = ts(o, "subscription_ends_at"),
            endsAt = ts(o, "ends_at"),
            serverTime = ts(o, "server_time") ?: error("server_time missing")
        )

        fun parseList(json: String): List<JSONObject> {
            val a = JSONArray(json.ifBlank { "[]" })
            return (0 until a.length()).map { a.getJSONObject(it) }
        }

        /** تحليل timestamptz من PostgREST (مثل 2026-10-02T09:15:30.123456+00:00). */
        fun ts(o: JSONObject, k: String): Long? {
            if (!o.has(k) || o.isNull(k)) return null
            return parseTs(o.getString(k))
        }

        fun parseTs(s: String): Long {
            // نقصّ أجزاء الثانية إلى 3 أرقام لتوافق java.time على API 24 (نستخدم SimpleDateFormat)
            val m = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}(:?\d{2})?)$""").find(s.trim())
                ?: error("bad timestamp: $s")
            val (date, time, frac, zone) = m.destructured
            val ms = frac.drop(1).padEnd(3, '0').take(3)
            val z = when {
                zone == "Z" -> "+0000"
                zone.length == 3 -> zone + "00"
                else -> zone.replace(":", "")
            }
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
            return f.parse("${date}T$time.$ms$z")!!.time
        }
    }
}
