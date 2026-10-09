package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.account.AuthService
import com.zarinet.netcontrol.api.RouterOsClient
import com.zarinet.netcontrol.data.Device
import com.zarinet.netcontrol.subscription.SupabaseApi
import kotlinx.coroutines.delay
import org.json.JSONObject

/** حالة الوصول السحابي كما تعيدها دالة cloud-access. */
data class CloudInfo(
    val enabled: Boolean,
    val server: String = "",
    val apiPort: Int = 0,
    val winboxPort: Int = 0,
    val tunnelIp: String = "",
    val l2tpUser: String = "",
    val l2tpPass: String = "",
    val ipsecSecret: String = "",
    val online: Boolean = false,
    val webPort: Int = 0
) {
    companion object {
        fun parse(o: JSONObject) = CloudInfo(
            enabled = o.optBoolean("enabled"),
            server = o.optString("server"),
            apiPort = o.optInt("api_port"),
            winboxPort = o.optInt("winbox_port"),
            webPort = o.optInt("web_port"),
            tunnelIp = o.optString("tunnel_ip"),
            l2tpUser = o.optString("l2tp_user"),
            l2tpPass = o.optString("l2tp_pass"),
            ipsecSecret = o.optString("ipsec_secret"),
            online = o.optBoolean("online")
        )
    }
}

/** حالة عميل النفق على الراوتر. */
data class RouterTunnel(val exists: Boolean, val running: Boolean, val disabled: Boolean)

/** منفذ عام على خادم السحابة يُحوَّل إلى جهاز داخل شبكة الراوتر. */
data class CloudForward(val publicPort: Int, val targetIp: String, val targetPort: Int) {
    val isWinbox: Boolean get() = targetPort == 8291

    fun url(server: String): String {
        if (isWinbox) return "$server:$publicPort"
        val scheme = if (targetPort == 443 || targetPort == 8443) "https" else "http"
        return "$scheme://$server:$publicPort"
    }
}

data class ForwardState(val server: String, val opened: CloudForward?, val active: List<CloudForward>)

/**
 * الوصول السحابي: نفق (L2TP/SSTP) من الراوتر إلى خادمنا ليُدار من أي مكان
 * بدون IP عام. الخادم يعطي منفذاً عاماً يُحوَّل عبر النفق إلى API/Winbox/Web.
 */
object CloudAccess {
    const val TAG = "shamel-cloud"
    const val GATEWAY = "10.99.0.1"

    var api: SupabaseApi = SupabaseApi()

    private suspend fun call(action: String, d: Device, extra: JSONObject.() -> Unit = {}): CloudInfo {
        val body = JSONObject().put("action", action).put("router_id", d.id)
        body.extra()
        return CloudInfo.parse(AuthService.authorized { t -> api.function("cloud-access", body, t) })
    }

    suspend fun status(d: Device): CloudInfo = call("status", d)

    /** حالة عميل النفق الموجود على الراوتر (أياً كان نوعه). */
    suspend fun routerTunnel(ros: RouterOsClient): RouterTunnel {
        var row: Map<String, String>? = null
        for (path in TUNNEL_TYPES) {
            row = runCatching {
                ros.print(path, listOf("running", "disabled"), listOf("?comment=$TAG")).firstOrNull()
            }.getOrNull()
            if (row != null) break
        }
        val r = row ?: return RouterTunnel(false, false, false)
        return RouterTunnel(true, r["running"] == "true", r["disabled"] == "true")
    }

    private val TUNNEL_TYPES = listOf("/interface/sstp-client", "/interface/l2tp-client")

    /** تفعيل: يحجز منافذ على الخادم ثم يهيّئ النفق والجدار الناري على الراوتر. */
    suspend fun enable(ros: RouterOsClient, d: Device): Pair<CloudInfo, Device> {
        val winbox = ros.print("/ip/service", listOf("port"), listOf("?name=winbox"))
            .firstOrNull()?.get("port")?.toIntOrNull() ?: 8291
        val web = wwwPort(ros)
        val info = call("enable", d) {
            put("router_api_port", d.port)
            put("router_winbox_port", winbox)
            put("router_web_port", web)
        }
        configureRouter(ros, info, d.port, winbox)
        runCatching { allowWeb(ros, web) }
        return info to d.copy(cloudHost = info.server, cloudPort = info.apiPort, cloudWinbox = info.winboxPort)
    }

    /** إيقاف: يزيل كل ما أضفناه على الراوتر (إن كان متصلاً) ثم يحرر المنافذ على الخادم. */
    suspend fun disable(ros: RouterOsClient?, d: Device): Device {
        if (ros != null) runCatching { cleanRouter(ros) }
        call("disable", d)
        return d.copy(cloudHost = "", cloudPort = 0, cloudWinbox = 0)
    }

    suspend fun configureRouter(ros: RouterOsClient, info: CloudInfo, apiPort: Int, winboxPort: Int) {
        cleanRouter(ros)
        val base = linkedMapOf(
            "name" to TAG,
            "connect-to" to info.server,
            "user" to info.l2tpUser,
            "password" to info.l2tpPass,
            "add-default-route" to "no",
            "disabled" to "no",
            "comment" to TAG
        )
        // نجرب بالترتيب: L2TP+IPsec ثم SSTP ثم L2TP بدون تشفير
        val attempts = listOf(
            "/interface/l2tp-client" to base + mapOf("use-ipsec" to "yes", "ipsec-secret" to info.ipsecSecret),
            "/interface/sstp-client" to base + mapOf("verify-server-certificate" to "no"),
            "/interface/l2tp-client" to base + mapOf("use-ipsec" to "no")
        )
        var ok = false
        for ((i, attempt) in attempts.withIndex()) {
            val (path, params) = attempt
            val last = i == attempts.lastIndex
            ok = runCatching {
                ros.exec("$path/add", params)
                waitRunning(ros, path) && tunnelPasses(ros)
            }.getOrDefault(false)
            if (ok || last) break
            runCatching { removeClient(ros, path) }
        }
        lastTunnelOk = ok

        // السماح بالـ API/Winbox من بوابة النفق قبل أي قاعدة حجب
        val first = ros.print("/ip/firewall/filter", listOf(".id"), listOf("?chain=input")).firstOrNull()?.get(".id")
        val rule = linkedMapOf(
            "chain" to "input",
            "action" to "accept",
            "protocol" to "tcp",
            "src-address" to GATEWAY,
            "dst-port" to "$apiPort,$winboxPort",
            "comment" to TAG
        )
        if (first != null) rule["place-before"] = first
        ros.exec("/ip/firewall/filter/add", rule)

        // إن كانت الخدمة مقيدة بعناوين، نضيف البوابة إليها
        for (name in listOf("api", "winbox")) {
            val s = ros.print("/ip/service", listOf(".id", "address"), listOf("?name=$name")).firstOrNull() ?: continue
            val addr = s["address"] ?: ""
            if (addr.isBlank() || addr.contains(GATEWAY)) continue
            ros.exec("/ip/service/set", mapOf(".id" to (s[".id"] ?: ""), "address" to "$addr,$GATEWAY/32"))
        }
    }

    private const val WEB_TAG = "shamel-cloud:web"

    suspend fun wwwPort(ros: RouterOsClient): Int =
        ros.print("/ip/service", listOf("port"), listOf("?name=www")).firstOrNull()?.get("port")?.toIntOrNull() ?: 80

    fun userManagerUrl(info: CloudInfo, v6: Boolean): String =
        "http://${info.server}:${info.webPort}" + (if (v6) "/userman" else "/um/")

    /** يفعّل خدمة www ويسمح بها من بوابة النفق. */
    suspend fun allowWeb(ros: RouterOsClient, port: Int) {
        val s = ros.print("/ip/service", listOf(".id", "disabled", "address"), listOf("?name=www")).firstOrNull()
        if (s != null) {
            val id = s[".id"] ?: ""
            if (s["disabled"] == "true") ros.exec("/ip/service/enable", mapOf(".id" to id))
            val addr = s["address"] ?: ""
            if (addr.isNotBlank() && !addr.contains(GATEWAY)) {
                ros.exec("/ip/service/set", mapOf(".id" to id, "address" to "$addr,$GATEWAY/32"))
            }
        }
        removeTagged(ros, WEB_TAG, exact = true)
        val first = ros.print("/ip/firewall/filter", listOf(".id"), listOf("?chain=input")).firstOrNull()?.get(".id")
        val rule = linkedMapOf(
            "chain" to "input",
            "action" to "accept",
            "protocol" to "tcp",
            "src-address" to GATEWAY,
            "dst-port" to port.toString(),
            "comment" to WEB_TAG
        )
        if (first != null) rule["place-before"] = first
        ros.exec("/ip/firewall/filter/add", rule)
    }

    /** هل قاعدة الويب موجودة ومفعّلة لنفس المنفذ وخدمة www مفعّلة؟ */
    suspend fun webReady(ros: RouterOsClient, port: Int): Boolean {
        val rules = ros.print("/ip/firewall/filter", listOf("dst-port", "disabled"), listOf("?comment=$WEB_TAG"))
        if (rules.none { it["dst-port"] == port.toString() && it["disabled"] != "true" }) return false
        val svc = ros.print("/ip/service", listOf("disabled"), listOf("?name=www")).firstOrNull()
        return svc?.get("disabled") != "true"
    }

    /** يضمن منفذ الويب (لـ User Manager) للأجهزة المفعّلة قبل إضافته. يعيد null إن كان جاهزاً. */
    suspend fun ensureWeb(ros: RouterOsClient, d: Device, force: Boolean = false): CloudInfo? {
        val port = wwwPort(ros)
        if (!force && webReady(ros, port)) return null
        val info = call("web", d) { put("router_web_port", port) }
        allowWeb(ros, port)
        return info
    }

    // ===== تحويل منافذ لأجهزة الشبكة (الجيران) =====

    const val FWD = "shamel-fwd:"

    private fun parseFwd(o: JSONObject): ForwardState {
        fun f(j: JSONObject) = CloudForward(j.optInt("public_port"), j.optString("target_ip"), j.optInt("target_port"))
        val arr = o.optJSONArray("active")
        return ForwardState(
            server = o.optString("server"),
            opened = o.optJSONObject("opened")?.let { f(it) },
            active = (0 until (arr?.length() ?: 0)).map { f(arr!!.getJSONObject(it)) }
        )
    }

    private suspend fun fwdCall(action: String, d: Device, extra: JSONObject.() -> Unit = {}): ForwardState {
        val body = JSONObject().put("action", action).put("router_id", d.id)
        body.extra()
        return parseFwd(AuthService.authorized { t -> api.function("cloud-access", body, t) })
    }

    suspend fun forwards(d: Device): ForwardState = fwdCall("forwards", d)

    suspend fun openForward(ros: RouterOsClient, d: Device, ip: String, port: Int): ForwardState {
        val st = fwdCall("forward_open", d) {
            put("target_ip", ip)
            put("target_port", port)
        }
        val f = st.opened ?: error("تعذر فتح المنفذ")
        syncRouterForwards(ros, st.active.filter { it.publicPort != f.publicPort })
        addRouterForward(ros, f)
        return st
    }

    suspend fun closeForward(ros: RouterOsClient?, d: Device, publicPort: Int): ForwardState {
        val st = fwdCall("forward_close", d) { put("port", publicPort) }
        if (ros == null) return st
        runCatching { syncRouterForwards(ros, st.active) }
        return st
    }

    suspend fun addRouterForward(ros: RouterOsClient, f: CloudForward) {
        val tag = FWD + f.publicPort
        removeTagged(ros, tag, exact = true)
        ros.exec(
            "/ip/firewall/nat/add", linkedMapOf(
                "chain" to "dstnat",
                "in-interface" to TAG,
                "protocol" to "tcp",
                "dst-port" to f.publicPort.toString(),
                "action" to "dst-nat",
                "to-addresses" to f.targetIp,
                "to-ports" to f.targetPort.toString(),
                "comment" to tag
            )
        )
        // حتى يرد الجهاز عبر الراوتر وليس عبر بوابته الافتراضية
        ros.exec(
            "/ip/firewall/nat/add", linkedMapOf(
                "chain" to "srcnat",
                "src-address" to GATEWAY,
                "dst-address" to f.targetIp,
                "protocol" to "tcp",
                "dst-port" to f.targetPort.toString(),
                "action" to "masquerade",
                "comment" to tag
            )
        )
        val first = ros.print("/ip/firewall/filter", listOf(".id"), listOf("?chain=forward")).firstOrNull()?.get(".id")
        val rule = linkedMapOf(
            "chain" to "forward",
            "action" to "accept",
            "protocol" to "tcp",
            "src-address" to GATEWAY,
            "dst-address" to f.targetIp,
            "dst-port" to f.targetPort.toString(),
            "comment" to tag
        )
        if (first != null) rule["place-before"] = first
        ros.exec("/ip/firewall/filter/add", rule)
    }

    /** يحذف من الراوتر قواعد التحويل التي لم تعد نشطة على الخادم. */
    suspend fun syncRouterForwards(ros: RouterOsClient, keep: List<CloudForward>) {
        val tags = keep.map { FWD + it.publicPort }.toSet()
        for (path in listOf("/ip/firewall/nat", "/ip/firewall/filter")) {
            val stale = ros.print(path, listOf(".id", "comment")).filter {
                val c = it["comment"] ?: ""
                c.startsWith(FWD) && c !in tags
            }
            for (r in stale) ros.exec("$path/remove", mapOf(".id" to (r[".id"] ?: "")))
        }
    }

    private suspend fun removeTagged(ros: RouterOsClient, tag: String, exact: Boolean) {
        for (path in listOf("/ip/firewall/nat", "/ip/firewall/filter")) {
            val rows = ros.print(path, listOf(".id", "comment")).filter {
                val c = it["comment"] ?: ""
                if (exact) c == tag else c.startsWith(tag)
            }
            for (r in rows) ros.exec("$path/remove", mapOf(".id" to (r[".id"] ?: "")))
        }
    }

    private suspend fun removeClient(ros: RouterOsClient, path: String) {
        for (r in ros.print(path, listOf(".id"), listOf("?comment=$TAG"))) {
            ros.exec("$path/remove", mapOf(".id" to (r[".id"] ?: "")))
        }
    }

    internal var tunnelPollMs = 2000L

    /** هل نجح آخر اختبار للنفق أثناء التفعيل؟ */
    var lastTunnelOk: Boolean = false
        private set

    /** يختبر مرور البيانات فعلاً عبر النفق (ping للبوابة). */
    private suspend fun tunnelPasses(ros: RouterOsClient): Boolean {
        repeat(3) {
            val rows = runCatching {
                ros.exec("/ping", mapOf("address" to GATEWAY, "count" to "3")).rows
            }.getOrDefault(emptyList())
            val received = rows.lastOrNull()?.get("received")?.toIntOrNull()
                ?: rows.count { !it["time"].isNullOrBlank() }
            if (received > 0) return true
            delay(tunnelPollMs)
        }
        return false
    }

    private suspend fun waitRunning(ros: RouterOsClient, path: String): Boolean {
        repeat(8) {
            delay(tunnelPollMs)
            ros.invalidateCache()
            val row = ros.print(path, listOf("running"), listOf("?comment=$TAG")).firstOrNull()
            if (row?.get("running") == "true") return true
        }
        return false
    }

    /** يزيل كل ما أضافه التطبيق على الراوتر للوصول السحابي. */
    suspend fun cleanRouter(ros: RouterOsClient) {
        removeTagged(ros, FWD, exact = false)
        removeTagged(ros, WEB_TAG, exact = true)
        for (path in TUNNEL_TYPES) runCatching { removeClient(ros, path) }
        for (r in ros.print("/ip/firewall/filter", listOf(".id"), listOf("?comment=$TAG"))) {
            ros.exec("/ip/firewall/filter/remove", mapOf(".id" to (r[".id"] ?: "")))
        }
        for (name in listOf("api", "winbox", "www")) {
            val s = ros.print("/ip/service", listOf(".id", "address"), listOf("?name=$name")).firstOrNull() ?: continue
            val addr = s["address"] ?: ""
            if (!addr.contains(GATEWAY)) continue
            ros.exec(
                "/ip/service/set", mapOf(
                    ".id" to (s[".id"] ?: ""),
                    "address" to addr.split(",").filterNot { it.startsWith(GATEWAY) }.joinToString(",")
                )
            )
        }
    }
}
