package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.data.Session

/** جهاز محظور: من IP Binding (type=blocked) أو من مضيفي الهوتسبوت (blocked). */
data class BlockedDev(
    val id: String,
    val source: String,        // binding | host
    val mac: String,
    val address: String,
    val toAddress: String,
    val server: String,
    val reason: String,
    val owner: String,         // رقم الكرت المرتبط بهذا الجهاز إن وُجد
    val hostName: String
)

suspend fun loadBlocked(): List<BlockedDev> {
    val api = Session.api()
    val bindings = runCatching {
        api.print("/ip/hotspot/ip-binding", listOf(".id", "mac-address", "address", "to-address", "server", "type", "comment"))
            .filter { it["type"] == "blocked" }
    }.getOrDefault(emptyList())
    val hosts = runCatching {
        api.print("/ip/hotspot/host", listOf(".id", "mac-address", "address", "to-address", "server", "blocked", "comment"))
            .filter { it["blocked"] == "true" }
    }.getOrDefault(emptyList())
    if (bindings.isEmpty() && hosts.isEmpty()) return emptyList()

    // مالك الجهاز: الكرت المرتبط بنفس MAC (من الكوكيز، أو ربط الكرت، أو الجلسات النشطة)
    val owners = HashMap<String, String>()
    runCatching { api.print("/ip/hotspot/cookie", listOf("user", "mac-address")).forEach { owners[it["mac-address"].orEmpty().uppercase()] = it["user"].orEmpty() } }
    runCatching {
        api.print("/ip/hotspot/user", listOf("name", "mac-address")).forEach { u ->
            val m = u["mac-address"].orEmpty().uppercase()
            if (m.isNotBlank() && m != "00:00:00:00:00:00") owners.putIfAbsent(m, u["name"].orEmpty())
        }
    }
    runCatching { api.print("/ip/hotspot/active", listOf("user", "mac-address")).forEach { owners.putIfAbsent(it["mac-address"].orEmpty().uppercase(), it["user"].orEmpty()) } }
    val names = runCatching {
        api.print("/ip/dhcp-server/lease", listOf("mac-address", "host-name")).associate { it["mac-address"].orEmpty().uppercase() to it["host-name"].orEmpty() }
    }.getOrDefault(emptyMap())

    fun dev(m: Map<String, String>, src: String): BlockedDev {
        val mac = m["mac-address"].orEmpty().uppercase()
        return BlockedDev(m[".id"].orEmpty(), src, mac, m["address"].orEmpty(), m["to-address"].orEmpty(), m["server"].orEmpty(),
            m["comment"].orEmpty().ifBlank { if (src == "host") "محظور من الهوتسبوت" else "بدون سبب" }, owners[mac].orEmpty(), names[mac].orEmpty())
    }
    return bindings.map { dev(it, "binding") } + hosts.map { dev(it, "host") }
}

suspend fun unblock(list: List<BlockedDev>) {
    val api = Session.api()
    list.filter { it.source == "binding" }.map { it.id }.chunked(100).forEach { api.exec("/ip/hotspot/ip-binding/remove", mapOf(".id" to it.joinToString(","))) }
    list.filter { it.source == "host" }.map { it.id }.chunked(100).forEach { api.exec("/ip/hotspot/host/remove", mapOf(".id" to it.joinToString(","))) }
}


/** يوحّد صيغة MAC: يقبل 9ad031c02911 أو 9A-D0-31-C0-29-11 أو 9a:d0:31:c0:29:11. يعيد null إن كانت غير صالحة. */
fun normalizeMac(input: String): String? {
    val hex = input.trim().uppercase().filter { it.isDigit() || it in 'A'..'F' }
    if (hex.length != 12) return null
    return hex.chunked(2).joinToString(":")
}

/** حظر جهاز يدوياً: IP Binding بنوع blocked، ثم فصله إن كان متصلاً وحذف مضيفه ليُطبَّق الحظر فوراً. */
suspend fun blockMac(mac: String, reason: String, server: String) {
    val api = Session.api()
    val exists = api.print("/ip/hotspot/ip-binding", listOf(".id", "mac-address", "type"))
        .firstOrNull { it["mac-address"].orEmpty().equals(mac, true) }
    val params = linkedMapOf("mac-address" to mac, "type" to "blocked", "comment" to reason.ifBlank { "يدوي" }, "server" to server.ifBlank { "all" })
    if (exists != null) api.exec("/ip/hotspot/ip-binding/set", params + (".id" to exists[".id"]!!))
    else api.exec("/ip/hotspot/ip-binding/add", params)
    runCatching {
        api.print("/ip/hotspot/active", listOf(".id", "mac-address")).filter { it["mac-address"].orEmpty().equals(mac, true) }
            .forEach { api.exec("/ip/hotspot/active/remove", mapOf(".id" to it[".id"]!!)) }
    }
    runCatching {
        api.print("/ip/hotspot/host", listOf(".id", "mac-address", "blocked")).filter { it["mac-address"].orEmpty().equals(mac, true) && it["blocked"] != "true" }
            .forEach { api.exec("/ip/hotspot/host/remove", mapOf(".id" to it[".id"]!!)) }
    }
}
