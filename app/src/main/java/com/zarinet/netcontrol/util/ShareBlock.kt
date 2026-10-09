package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.RouterOsClient

/**
 * منع مشاركة الإنترنت (Tethering/Hotspot من جوال العميل):
 * قاعدة mangle في postrouting تضبط TTL=1 على واجهة الخروج، فتسقط الحزم عند أي جهاز يعيد توجيهها.
 */
object ShareBlock {
    const val TAG = "shamel: block-sharing"

    data class Rule(val id: String, val iface: String, val disabled: Boolean)

    /** القواعد المُضافة من التطبيق (بتعليق TAG). */
    suspend fun rules(api: RouterOsClient): List<Rule> =
        api.print("/ip/firewall/mangle", listOf(".id", "out-interface", "disabled"), listOf("?comment=$TAG"))
            .map { Rule(it[".id"].orEmpty(), it["out-interface"].orEmpty(), it["disabled"] == "true") }

    /** الواجهات الثابتة (بدون الديناميكية وloopback). */
    suspend fun interfaces(api: RouterOsClient): List<String> =
        api.print("/interface", listOf("name", "type", "dynamic"))
            .filter { it["dynamic"] != "true" && it["type"] != "loopback" }
            .mapNotNull { it["name"] }

    /** يضيف قاعدة المنع على الواجهة إن لم تكن موجودة. */
    suspend fun block(api: RouterOsClient, iface: String) {
        if (rules(api).any { it.iface == iface }) return
        api.exec(
            "/ip/firewall/mangle/add",
            linkedMapOf(
                "chain" to "postrouting", "out-interface" to iface, "action" to "change-ttl",
                "new-ttl" to "set:1", "passthrough" to "yes", "comment" to TAG
            )
        )
    }

    suspend fun unblock(api: RouterOsClient, id: String) {
        api.exec("/ip/firewall/mangle/remove", mapOf(".id" to id))
    }
}
