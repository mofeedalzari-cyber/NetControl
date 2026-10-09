package com.zarinet.netcontrol.account

import com.zarinet.netcontrol.data.Device
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.subscription.SupabaseApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * أجهزة MikroTik محفوظة على حساب المستخدم (جدول routers مع RLS) مع نسخة محلية للعمل بدون إنترنت.
 * كلمة مرور الراوتر تُرفع مشفّرة فقط.
 */
class CloudDevices(private val local: DeviceStore, private val api: SupabaseApi = SupabaseApi()) {
    /** أجهزة لم يُفك تشفير كلمة مرورها (مثلاً بعد تغيير كلمة مرور الحساب) — يلزم إعادة إدخالها. */
    var needPassword: Set<String> = emptySet()
        private set

    fun cached(): List<Device> = local.load()

    /** يجلب أجهزة الحساب؛ أول مرة يرفع الأجهزة المحلية القديمة غير الموجودة في الحساب. */
    suspend fun sync(): List<Device> {
        val key = AuthService.routerKey
        val remote = AuthService.authorized { t -> api.rest("GET", "routers?select=*&order=created_at.asc", null, t) }
        val arr = JSONArray(remote)
        val miss = HashSet<String>()
        // عناوين الوصول السحابي (نفق) لكل راوتر — إن تعذّر جلبها نُبقي آخر نسخة محفوظة محلياً
        val tunnels = runCatching {
            val t = AuthService.authorized { tk -> api.rest("GET", "cloud_tunnels?select=router_id,public_host,api_port,winbox_port", null, tk) }
            val ta = JSONArray(t)
            (0 until ta.length()).associate { j -> ta.getJSONObject(j).let { it.getString("router_id") to it } }
        }.getOrNull()
        val cloud = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val pass = key?.let { RouterCrypto.decrypt(o.optString("password_enc"), it) }
            if (pass == null) miss += id
            val d = Device(id = id, name = o.optString("name"), host = o.getString("host"), port = o.optInt("port", 8728),
                user = o.optString("username"), password = pass ?: local.load().firstOrNull { it.id == id }?.password.orEmpty(),
                wanInterface = o.optString("wan_interface"))
            val tn = tunnels?.get(id)
            when {
                tn != null -> d.copy(cloudHost = tn.optString("public_host"), cloudPort = tn.optInt("api_port"), cloudWinbox = tn.optInt("winbox_port"))
                tunnels == null -> local.load().firstOrNull { it.id == id }
                    ?.let { l -> d.copy(cloudHost = l.cloudHost, cloudPort = l.cloudPort, cloudWinbox = l.cloudWinbox) } ?: d
                else -> d
            }
        }
        // ترحيل الأجهزة المحلية القديمة إلى الحساب
        val toUpload = local.load().filter { l -> cloud.none { it.id == l.id } }
        toUpload.forEach { upsert(it, updateLocal = false) }
        // بعد استعادة كلمة المرور: إعادة تشفير الأجهزة التي نعرف كلمة مرورها محلياً بالمفتاح الجديد
        if (key != null) cloud.filter { it.id in miss && it.password.isNotEmpty() }.forEach { runCatching { upsert(it, updateLocal = false) } }
        val all = cloud + toUpload
        needPassword = miss.filter { id -> all.firstOrNull { it.id == id }?.password.isNullOrEmpty() }.toSet()
        local.save(all)
        return all
    }

    /** بعد تغيير كلمة مرور الحساب: فك التشفير بالمفتاح القديم وإعادة الرفع بالمفتاح الجديد. */
    suspend fun reencrypt(oldKey: ByteArray?) {
        val arr = JSONArray(AuthService.authorized { t -> api.rest("GET", "routers?select=*", null, t) })
        val localById = local.load().associateBy { it.id }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val pass = oldKey?.let { RouterCrypto.decrypt(o.optString("password_enc"), it) } ?: localById[id]?.password ?: continue
            upsert(Device(id = id, name = o.optString("name"), host = o.getString("host"), port = o.optInt("port", 8728),
                user = o.optString("username"), password = pass, wanInterface = o.optString("wan_interface")))
        }
    }

    suspend fun upsert(d: Device, updateLocal: Boolean = true) {
        val key = AuthService.routerKey ?: throw SupabaseApi.ApiError(401, "سجّل الدخول مجدداً لحفظ كلمات المرور بأمان")
        val body = JSONObject().put("id", d.id).put("name", d.name).put("host", d.host).put("port", d.port)
            .put("username", d.user).put("password_enc", RouterCrypto.encrypt(d.password, key)).put("wan_interface", d.wanInterface)
        AuthService.authorized { t -> api.rest("POST", "routers?on_conflict=id", JSONArray().put(body).toString(), t, "resolution=merge-duplicates,return=minimal") }
        if (updateLocal) local.upsert(d)
        needPassword = needPassword - d.id
    }

    suspend fun delete(id: String) {
        AuthService.authorized { t -> api.rest("DELETE", "routers?id=eq.$id", null, t, "return=minimal") }
        local.delete(id)
    }
}
