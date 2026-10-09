package com.zarinet.netcontrol.account

import android.content.Context
import android.os.Build
import com.zarinet.netcontrol.subscription.DeviceIdentity
import com.zarinet.netcontrol.subscription.SupabaseApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AuthSession(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAt: Long)

/** نتيجة إنشاء الحساب: إما دخول مباشر، أو يلزم تأكيد البريد. */
sealed class SignUpResult { object LoggedIn : SignUpResult(); object ConfirmEmail : SignUpResult() }

/** حساب المستخدم (Supabase Auth) — الجلسة تُحفظ مشفّرة محلياً وتُجدَّد تلقائياً. */
object AuthService {
    private var api: SupabaseApi = SupabaseApi()
    private var store: SecureStore? = null
    private val mutex = Mutex()
    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session

    /** مفتاح تشفير كلمات مرور الراوترات (يُشتق عند تسجيل الدخول). */
    var routerKey: ByteArray? = null
        private set

    val configured: Boolean get() = api.configured

    /** بصمة هذا الهاتف واسمه — الحساب يعمل على جهاز واحد فقط في نفس الوقت. */
    private var deviceId: String = ""
    private var deviceName: String = ""

    /** رسالة الطرد عند تسجيل الدخول بنفس الحساب من جهاز آخر (تُعرض في شاشة الدخول). */
    private val _kicked = MutableStateFlow<String?>(null)
    val kicked: StateFlow<String?> = _kicked
    fun clearKicked() { _kicked.value = null }

    /** هل الحساب الحالي مدير؟ (للواجهة فقط؛ الصلاحيات الحقيقية تُفحص على الخادم في كل عملية). */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin

    suspend fun refreshAdmin() {
        val v = runCatching { authorized { t -> api.rpc("is_admin", JSONObject(), t).trim() == "true" } }.getOrNull() ?: return
        _isAdmin.value = v; store?.put("admin", v.toString())
    }

    fun init(ctx: Context, supabase: SupabaseApi = SupabaseApi()) {
        if (store != null) return
        api = supabase
        store = SecureStore(ctx.applicationContext)
        deviceId = runCatching { DeviceIdentity.deviceHash(ctx.applicationContext) }.getOrDefault("")
        deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ")
            .replaceFirstChar { it.uppercase() }
        val s = store!!
        val uid = s.get("uid"); val rt = s.get("refresh")
        if (uid != null && rt != null) {
            _session.value = AuthSession(uid, s.get("email").orEmpty(), s.get("access").orEmpty(), rt, s.get("exp")?.toLongOrNull() ?: 0)
            routerKey = s.get("rkey")?.let { runCatching { B64.decode(it) }.getOrNull() }
            _isAdmin.value = s.get("admin") == "true"
        }
    }

    suspend fun signIn(email: String, password: String) {
        val res = api.signInPassword(email.trim().lowercase(), password)
        claimDevice(res.getString("access_token"))
        save(res, password)
        _kicked.value = null
    }

    /** إنشاء حساب مؤكَّد عبر الخادم (بدون انتظار رسالة تأكيد) ثم الدخول مباشرة. */
    suspend fun signUp(email: String, password: String): SignUpResult {
        val e = email.trim().lowercase()
        api.authHelper(JSONObject().put("action", "signup").put("email", e).put("password", password))
        signIn(e, password)
        return SignUpResult.LoggedIn
    }

    /**
     * تغيير كلمة المرور من إعدادات الحساب: التحقق من الحالية ← الحفظ على الخادم ← الدخول بالجديدة.
     * يعيد المفتاح القديم لإعادة تشفير كلمات مرور الراوترات بالمفتاح الجديد.
     */
    suspend fun changePassword(current: String, newPassword: String): ByteArray? {
        val s = _session.value ?: throw SupabaseApi.ApiError(401, "سجّل الدخول أولاً")
        val oldKey = routerKey
        val verified = try { api.signInPassword(s.email, current) } catch (e: SupabaseApi.ApiError) {
            if (e.code == 400) throw SupabaseApi.ApiError(400, "كلمة المرور الحالية غير صحيحة") else throw e
        }
        api.updatePassword(verified.getString("access_token"), newPassword)
        signIn(s.email, newPassword)          // جلسة جديدة + مفتاح تشفير مشتق من كلمة المرور الجديدة
        return oldKey
    }

    /** ربط الحساب بهذا الهاتف (يطرد أي جهاز آخر). إن رفض الخادم نُلغي الجلسة الجديدة ونعيد الخطأ. */
    private suspend fun claimDevice(token: String) {
        if (deviceId.isBlank()) return
        try {
            api.rpc("claim_device", JSONObject().put("p_device_id", deviceId).put("p_device_name", deviceName), token)
        } catch (e: SupabaseApi.ApiError) {
            api.signOut(token)
            throw e
        }
    }

    /** طرد محلي: الحساب فُتح على جهاز آخر (أو أُلغيت الجلسة من الخادم). */
    fun kick(message: String?) {
        _kicked.value = message ?: "هذا الحساب مسجّل دخول على جهاز آخر"
        _session.value = null; routerKey = null; _isAdmin.value = false
        store?.clear()
    }

    suspend fun signOut() {
        _session.value?.let { s ->
            if (deviceId.isNotBlank()) runCatching { authorized { t -> api.rpc("release_device", JSONObject().put("p_device_id", deviceId), t) } }
            api.signOut(runCatching { token() }.getOrNull() ?: s.accessToken)
        }
        _session.value = null; routerKey = null; _isAdmin.value = false
        store?.clear()
    }

    /** رمز صالح للطلبات (يجدده إن قارب الانتهاء). يعيد null إن لم يكن هناك جلسة. */
    suspend fun token(forceRefresh: Boolean = false): String? = mutex.withLock {
        val s = _session.value ?: return@withLock null
        if (!forceRefresh && s.accessToken.isNotBlank() && System.currentTimeMillis() < s.expiresAt - 60_000) return@withLock s.accessToken
        try {
            val res = api.refresh(s.refreshToken)
            save(res, null)
            _session.value?.accessToken
        } catch (e: SupabaseApi.ApiError) {
            if (e.code in 400..401) { _session.value = null; routerKey = null; store?.clear() }   // الجلسة أُلغيت من الخادم
            throw e
        }
    }

    /** تنفيذ طلب بصلاحية المستخدم مع إعادة المحاولة مرة عند انتهاء الرمز. */
    suspend fun <T> authorized(block: suspend (String) -> T): T {
        val t = token() ?: throw SupabaseApi.ApiError(401, "سجّل الدخول أولاً")
        return try { block(t) } catch (e: SupabaseApi.ApiError) {
            if (e.code != 401) throw e
            block(token(forceRefresh = true) ?: throw e)
        }
    }

    private fun save(res: JSONObject, password: String?) {
        val user = res.getJSONObject("user")
        val uid = user.getString("id")
        val s = AuthSession(uid, user.optString("email"), res.getString("access_token"), res.getString("refresh_token"),
            System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000)
        store?.apply { put("uid", uid); put("email", s.email); put("access", s.accessToken); put("refresh", s.refreshToken); put("exp", s.expiresAt.toString()) }
        if (password != null) {
            routerKey = RouterCrypto.deriveKey(password, uid)
            store?.put("rkey", B64.encode(routerKey!!))
        }
        _session.value = s
    }

    /** للاختبارات ولقطات الشاشة فقط. */
    internal fun setForTests(s: AuthSession?, key: ByteArray? = null, admin: Boolean = false) { _session.value = s; routerKey = key; _isAdmin.value = admin }
}
