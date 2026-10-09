package com.zarinet.netcontrol.subscription

import org.json.JSONObject

/** الوصول لبيانات الاشتراك على الخادم (التطبيق يقرأ فقط؛ لا توجد أي دالة تعديل للمستخدم). */
open class SubscriptionRepository(private val api: SupabaseApi = SupabaseApi()) {
    val configured: Boolean get() = api.configured

    /** اشتراك الحساب المسجّل (أول مرة ينشئ المعرف والتجربة على الخادم) مع بصمة الجهاز لمنع تكرار التجربة. */
    open suspend fun fetch(deviceHash: String): Pair<Subscription, String> {
        val raw = com.zarinet.netcontrol.account.AuthService.authorized { t ->
            api.rpc("my_subscription", JSONObject().put("p_device_id", deviceHash), t)
        }
        val sub = SupabaseApi.parse(raw) ?: throw SupabaseApi.ApiError(0, "رد غير متوقع من الخادم")
        return sub to raw
    }
}

/** عمليات لوحة الإدارة — بحساب المستخدم المسجّل؛ الخادم يرفض أي حساب ليس في جدول admins. */
class AdminRepository(private val api: SupabaseApi = SupabaseApi()) {
    private suspend fun <T> auth(block: suspend (String) -> T) = com.zarinet.netcontrol.account.AuthService.authorized(block)

    /** هل الحساب الحالي مسؤول؟ (لإظهار رابط الإدارة فقط؛ الحماية الحقيقية على الخادم). */
    suspend fun isAdmin(): Boolean = runCatching { auth { t -> api.rpc("is_admin", JSONObject(), t).trim() == "true" } }.getOrDefault(false)

    suspend fun search(query: String, limit: Int = 100): List<JSONObject> = auth { t ->
        SupabaseApi.parseList(api.rpc("admin_search_subscriptions", JSONObject().put("p_query", query.trim()).put("p_limit", limit), t)) }

    suspend fun activate(publicId: String, plan: SubscriptionPlan): Subscription = auth { t ->
        SupabaseApi.parse(api.rpc("admin_activate_subscription", JSONObject().put("p_public_id", publicId).put("p_plan", plan.code), t))!! }

    suspend fun setSuspended(publicId: String, suspended: Boolean): Subscription = auth { t ->
        SupabaseApi.parse(api.rpc("admin_set_suspended", JSONObject().put("p_public_id", publicId).put("p_suspended", suspended), t))!! }

    /** تعيين كلمة مرور جديدة لمستخدم (عند نسيانها) — ينهي جلساته القديمة ويُسجَّل في السجل. */
    suspend fun setPassword(email: String, password: String) = auth { t ->
        api.rpc("admin_set_password", JSONObject().put("p_email", email).put("p_password", password), t) }

    suspend fun savePlan(code: String?, name: String, months: Int, price: Int, active: Boolean) = auth { t ->
        api.rpc("admin_save_plan", JSONObject().put("p_code", code ?: JSONObject.NULL).put("p_name", name).put("p_months", months)
            .put("p_price", price).put("p_active", active), t) }

    /** يعيد true إن حُذفت، false إن أُخفيت لأنها مستخدمة في اشتراكات سابقة. */
    suspend fun deletePlan(code: String): Boolean = auth { t ->
        JSONObject(api.rpc("admin_delete_plan", JSONObject().put("p_code", code), t)).optBoolean("deleted") }

    /** فك ربط الحساب بجهازه الحالي (ليتمكن من تسجيل الدخول من جهاز آخر). */
    suspend fun releaseDevice(publicId: String) = auth { t ->
        api.rpc("admin_release_device", JSONObject().put("p_public_id", publicId), t) }

    suspend fun deleteUser(publicId: String) = auth { t ->
        api.rpc("admin_delete_user", JSONObject().put("p_public_id", publicId), t) }

    suspend fun transactions(publicId: String): List<JSONObject> = auth { t ->
        SupabaseApi.parseList(api.rpc("admin_list_transactions", JSONObject().put("p_public_id", publicId), t)) }
}
