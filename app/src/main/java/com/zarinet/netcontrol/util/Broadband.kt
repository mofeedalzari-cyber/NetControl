package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.ApiResult
import com.zarinet.netcontrol.api.RouterOsException
import com.zarinet.netcontrol.data.Session
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * حساب برودباند (PPPoE secret) + بيانات الجلسة النشطة إن وُجدت.
 * البيانات الإضافية (هاتف، سعر، تاريخ انتهاء، باقة جيجا، مدفوع...) تُحفظ داخل التعليق
 * بصيغة: "ملاحظة;phone=...;exp=yyyy-MM-dd;price=...".
 */
data class PppAccount(
    val id: String,
    val name: String,
    val password: String,
    val profile: String,
    val disabled: Boolean,
    val note: String,
    val meta: Map<String, String>,
    val lastLoggedOut: Long?,
    val online: Boolean = false,
    val address: String = "",
    val uptime: String = "",
    val callerId: String = "",
    val rateBps: Long = 0L,
    val usage: Long = -1L,
    val service: String = "any",
    /** جلسة نشطة ليس لها secret محلي (غالباً من يوزر منجر / RADIUS). */
    val external: Boolean = false,
    val download: Long = 0L,
    val upload: Long = 0L,
    val umRemainingMs: Long? = null,
    val umLeftBytes: Long? = null
) {
    val phone: String get() = meta["phone"] ?: ""
    val price: String get() = meta["price"] ?: ""
    val exp: String get() = meta["exp"] ?: ""
    val gb: Long get() = meta["gb"]?.toLongOrNull() ?: 0L
    val paid: Boolean get() = meta["paid"] == "1"

    /** الاستهلاك: من عداد الجيجا إن توفر، وإلا من التعليق (used=). */
    val usedBytes: Long
        get() = usage.takeIf { it >= 0 } ?: meta["used"]?.toLongOrNull() ?: 0L

    /** نهاية يوم الانتهاء (23:59:59). */
    val expMs: Long?
        get() = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(exp)?.time
        }.getOrNull()?.let { it + 86_399_000L }

    /** لم يدخل أبداً (أو تاريخ آخر خروج غير صالح). */
    val neverUsed: Boolean
        get() = !online && (lastLoggedOut == null || lastLoggedOut < 946_684_800_000L)
}

enum class PppFilter(val label: String) {
    ALL("الكل"),
    ONLINE("نشط"),
    SLEEP("نائم"),
    DISABLED("معطل"),
    UNUSED("غير مستخدم"),
    PHONE("له هاتف"),
    NO_PHONE("بدون هاتف"),
    HAS_EXP("له تاريخ انتهاء"),
    NO_EXP("بدون انتهاء"),
    TOMORROW("ينتهي غداً"),
    EXPIRED("منتهي"),
    PAID("مدفوع"),
    UNPAID("غير مدفوع");

    fun match(a: PppAccount, now: Long = System.currentTimeMillis()): Boolean = when (this) {
        ALL -> true
        ONLINE -> a.online
        SLEEP -> !a.online && !a.disabled && !a.neverUsed
        DISABLED -> a.disabled
        UNUSED -> a.neverUsed
        PHONE -> a.phone.isNotBlank()
        NO_PHONE -> a.phone.isBlank()
        HAS_EXP -> a.expMs != null
        NO_EXP -> a.expMs == null
        TOMORROW -> a.expMs?.let { it >= now && it - now <= 172_800_000L } == true
        EXPIRED -> a.expMs?.let { it < now } == true
        PAID -> a.paid
        UNPAID -> !a.paid
    }
}

/**
 * إعدادات البرودباند على الراوتر.
 * @param onExpire ماذا يحدث عند الانتهاء: "disable" تعطيل، "page" تحويل لبروفايل EXPIRED، "none" لا شيء.
 */
data class PppSettings(
    val autoSpeed: Boolean = true,
    val gbCounter: Boolean = false,
    val onExpire: String = "disable"
)

object Broadband {
    const val EXPIRED_PROFILE = "EXPIRED"
    private const val EXPIRY_SCRIPT = "shamel-ppp-expiry"
    private const val GB_SCRIPT = "shamel-ppp-gb"

    fun parseComment(c: String): Pair<String, Map<String, String>> {
        val parts = c.split(";")
        val meta = parts.drop(1).mapNotNull { p ->
            p.indexOf('=').takeIf { it > 0 }?.let { i ->
                p.substring(0, i).trim() to p.substring(i + 1).trim()
            }
        }.toMap()
        return parts.first().trim() to meta
    }

    fun buildComment(note: String, meta: Map<String, String>): String =
        (listOf(note.replace(";", ",").trim()) +
            meta.filterValues { it.isNotBlank() }.map { (k, v) -> "$k=" + v.replace(";", ",") })
            .joinToString(";")

    // ---- كاش القراءة: الـ secrets والاستهلاك يُقرآن كاملاً كل دقيقة فقط، والجلسات النشطة كل مرة ----
    private val prevBytes = HashMap<String, Pair<Long, Long>>()
    private var cacheDev: String? = null
    private var cacheAt = 0L
    private var cSecrets: List<Map<String, String>> = emptyList()
    private var cUsage: Map<String, Long> = emptyMap()
    private val cUmProfile = HashMap<String, String>()
    private val cUmInfo = HashMap<String, Pair<CardInfo, Long>>()
    private val cUmTried = HashSet<String>()

    @Volatile
    var lastAccounts: List<PppAccount> = emptyList()
        private set

    private const val FULL_EVERY = 60_000L

    /** آخر قائمة محمّلة للجهاز الحالي فقط. */
    fun cached(): List<PppAccount> =
        if (cacheDev == Session.device?.id) lastAccounts else emptyList()

    fun invalidate() {
        cacheAt = 0L
    }

    suspend fun accounts(withSpeed: Boolean, full: Boolean = false): List<PppAccount> {
        val api = Session.api()
        val now = System.currentTimeMillis()
        val dev = Session.device?.id
        if (dev != cacheDev) {
            cacheDev = dev
            cacheAt = 0L
            cUmProfile.clear(); cUmInfo.clear(); cUmTried.clear()
            lastAccounts = emptyList()
        }
        val needFull = full || cacheAt == 0L || now - cacheAt > FULL_EVERY

        val active = runCatching {
            api.print("/ppp/active", listOf("name", "address", "uptime", "caller-id", "service"))
                .ifEmpty { api.print("/ppp/active") }
        }.getOrDefault(emptyList()).associateBy { it["name"] ?: "" }

        // السرعة والتحميل/الرفع من واجهات pppoe-in
        val rates = HashMap<String, Long>()
        val bytes = HashMap<String, Pair<Long, Long>>()
        if (active.isNotEmpty()) runCatching {
            api.print("/interface", listOf("name", "rx-byte", "tx-byte"), listOf("?type=pppoe-in")).forEach { r ->
                val n = (r["name"] ?: "").removePrefix("<pppoe-").removeSuffix(">")
                val rx = r["rx-byte"]?.toLongOrNull() ?: 0L
                val tx = r["tx-byte"]?.toLongOrNull() ?: 0L
                // من جهة الراوتر: tx = تنزيل المشترك، rx = رفعه
                bytes[n] = tx to rx
                if (withSpeed) {
                    val total = rx + tx
                    val prev = prevBytes[n]
                    if (prev != null && now > prev.second && total >= prev.first) {
                        rates[n] = (total - prev.first) * 8000 / (now - prev.second)
                    }
                    prevBytes[n] = total to now
                }
            }
        }

        if (needFull) {
            cUsage = runCatching { gigaUsage() }.getOrDefault(emptyMap())
            var secrets = runCatching {
                api.print(
                    "/ppp/secret",
                    listOf(".id", "name", "password", "profile", "disabled", "comment", "last-logged-out", "service")
                )
            }.getOrDefault(emptyList())
            // بعض الإصدارات ترجع قائمة فارغة مع proplist
            if (secrets.isEmpty() && api.count("/ppp/secret") > 0) secrets = api.print("/ppp/secret")
            cSecrets = secrets
            cacheAt = now
        }

        val usage = cUsage
        var firstErr: Throwable? = null
        val list = cSecrets.mapIndexedNotNull { i, s ->
            runCatching {
                val name = s["name"] ?: ""
                val (note, meta) = parseComment(s["comment"] ?: "")
                val act = active[name]
                PppAccount(
                    id = s[".id"]?.takeIf { it.isNotBlank() } ?: "#$i",
                    name = name,
                    password = s["password"] ?: "",
                    profile = s["profile"] ?: "",
                    disabled = s["disabled"] == "true" || s["disabled"] == "yes",
                    note = note,
                    meta = meta,
                    lastLoggedOut = RosTime.parse(s["last-logged-out"], now),
                    online = act != null,
                    address = act?.get("address") ?: "",
                    uptime = act?.get("uptime") ?: "",
                    callerId = act?.get("caller-id") ?: "",
                    rateBps = rates[name] ?: 0L,
                    usage = usage[name] ?: -1L,
                    service = (s["service"] ?: "").ifBlank { "any" },
                    download = if (act != null) bytes[name]?.first ?: 0L else 0L,
                    upload = if (act != null) bytes[name]?.second ?: 0L else 0L
                )
            }.onFailure { if (firstErr == null) firstErr = it }.getOrNull()
        }
        if (list.isEmpty() && cSecrets.isNotEmpty()) {
            throw RouterOsException("تعذّر قراءة حسابات PPP: " + (firstErr?.message ?: "بيانات غير متوقعة"))
        }

        // جلسات نشطة بلا secret محلي (يوزر منجر / RADIUS)
        val names = list.map { it.name }.toHashSet()
        val ext = active.values
            .filter { (it["name"] ?: "").isNotBlank() && it["name"] !in names }
            .map { a ->
                val n = a["name"] ?: ""
                PppAccount(
                    id = "active:$n",
                    name = n,
                    password = "",
                    profile = "",
                    disabled = false,
                    note = "",
                    meta = emptyMap(),
                    lastLoggedOut = null,
                    online = true,
                    address = a["address"] ?: "",
                    uptime = a["uptime"] ?: "",
                    callerId = a["caller-id"] ?: "",
                    rateBps = rates[n] ?: 0L,
                    usage = usage[n] ?: -1L,
                    service = (a["service"] ?: "").ifBlank { "pppoe" },
                    external = true,
                    download = bytes[n]?.first ?: 0L,
                    upload = bytes[n]?.second ?: 0L
                )
            }

        // بروفايل/متبقي يوزر منجر — يُسأل مرة واحدة لكل اسم
        val toAsk = ext.map { it.name }.filter { it !in cUmTried }
        if (toAsk.isNotEmpty()) {
            cUmTried.addAll(toAsk)
            runCatching { UserManager.activeInfo(toAsk) }.getOrNull()?.let { m ->
                for ((n, info) in m) {
                    cUmProfile[n] = info.profile
                    cUmInfo[n] = info to now
                }
            }
        }
        val ext2 = ext.map { a ->
            val info = cUmInfo[a.name]
            val base = cUmProfile[a.name]?.let { a.copy(profile = it) } ?: a
            if (info == null) base
            else base.copy(
                umRemainingMs = info.first.remainingMs?.let { (it - (now - info.second)).coerceAtLeast(0L) },
                umLeftBytes = info.first.leftBytes
            )
        }

        val result = (list + ext2).distinctBy { it.id }
        lastAccounts = result
        return result
    }

    suspend fun profiles(): List<Map<String, String>> =
        Session.api().print("/ppp/profile", listOf(".id", "name", "rate-limit", "local-address", "remote-address", "comment"))

    /** (الاسم، النطاقات) لكل IP pool. */
    suspend fun pools(): List<Pair<String, String>> =
        Session.api().print("/ip/pool", listOf("name", "ranges")).map { (it["name"] ?: "") to (it["ranges"] ?: "") }

    /** إضافة (existing = null) أو تعديل حساب. */
    suspend fun save(
        existing: PppAccount?,
        name: String,
        password: String,
        profile: String,
        note: String,
        meta: Map<String, String>
    ) {
        invalidate()
        val api = Session.api()
        // نحفظ البروفايل الأصلي (plan) حتى نرجعه بعد التجديد إن حُوّل إلى EXPIRED
        val merged = ((existing?.meta ?: emptyMap()) + meta + ("plan" to profile)).toMap(LinkedHashMap())
        val p = linkedMapOf(
            "name" to name,
            "password" to password,
            "profile" to profile,
            "service" to "pppoe",
            "comment" to buildComment(note, merged)
        )
        if (existing == null) api.exec("/ppp/secret/add", p)
        else api.exec("/ppp/secret/set", p + (".id" to existing.id))
        val gb = merged["gb"]?.toLongOrNull() ?: 0L
        if (gb > 0 || (existing?.gb ?: 0L) > 0) runCatching { setGigaLimit(name, gb) }
    }

    /** تعديل حقول التعليق فقط (القيمة الفارغة تحذف المفتاح). */
    suspend fun setMeta(a: PppAccount, changes: Map<String, String>) {
        invalidate()
        val m = a.meta.toMutableMap()
        changes.forEach { (k, v) -> if (v.isBlank()) m.remove(k) else m[k] = v }
        Session.api().exec("/ppp/secret/set", mapOf(".id" to a.id, "comment" to buildComment(a.note, m)))
    }

    /** إجراء جماعي: enable / disable / remove / kick. */
    suspend fun bulk(action: String, all: List<PppAccount>) {
        invalidate()
        val api = Session.api()
        // حسابات يوزر منجر لا تُعدَّل من هنا، لكن يمكن فصل جلساتها
        val list = if (action != "kick") all.filter { !it.external } else all
        if (action != "kick" && list.isEmpty()) {
            if (all.isEmpty()) return
            throw RouterOsException("هذه الحسابات من يوزر منجر — عدّلها من قسم يوزر منجر")
        }
        val ids = list.joinToString(",") { it.id }
        when (action) {
            "enable" -> api.exec("/ppp/secret/enable", mapOf(".id" to ids))
            "disable" -> {
                api.exec("/ppp/secret/disable", mapOf(".id" to ids))
                kick(list)
            }
            "remove" -> {
                kick(list)
                api.exec("/ppp/secret/remove", mapOf(".id" to ids))
            }
            "kick" -> kick(list)
        }
    }

    private suspend fun kick(list: List<PppAccount>) {
        val api = Session.api()
        val ids = api.print("/ppp/active", listOf(".id", "name"))
            .filter { r -> list.any { it.name == r["name"] } }
            .mapNotNull { it[".id"] }
        if (ids.isEmpty()) return
        api.exec("/ppp/active/remove", mapOf(".id" to ids.joinToString(",")))
    }

    /** تجديد: يمدد من تاريخ الانتهاء (أو من اليوم إن كان منتهياً) ويصفّر الاستهلاك ويعيد التفعيل. */
    suspend fun renew(a: PppAccount, days: Int) {
        val now = System.currentTimeMillis()
        val base = maxOf(now, a.expMs ?: 0L)
        val cal = Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_YEAR, days)
        }
        setMeta(a, mapOf("exp" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time), "used" to ""))
        val api = Session.api()
        if (a.profile == EXPIRED_PROFILE && !a.meta["plan"].isNullOrBlank()) {
            api.exec("/ppp/secret/set", mapOf(".id" to a.id, "profile" to a.meta["plan"]!!))
        }
        runCatching { resetGiga(a.name) }
        if (a.disabled) api.exec("/ppp/secret/enable", mapOf(".id" to a.id))
    }

    suspend fun addProfile(name: String, rate: String, pool: String, local: String) {
        val p = linkedMapOf(
            "name" to name,
            "rate-limit" to rate,
            "remote-address" to pool,
            "local-address" to local,
            "only-one" to "yes"
        )
        Session.api().exec("/ppp/profile/add", p.filterValues { it.isNotBlank() })
    }

    suspend fun updateProfile(id: String, name: String, rate: String, pool: String, local: String): ApiResult =
        Session.api().exec(
            "/ppp/profile/set",
            mapOf(".id" to id, "name" to name, "rate-limit" to rate, "remote-address" to pool, "local-address" to local)
        )

    suspend fun removeProfile(id: String): ApiResult =
        Session.api().exec("/ppp/profile/remove", mapOf(".id" to id))

    /** عنوان البوابة المحلي من نطاق الـ pool: 10.5.0.2-10.5.0.254 → 10.5.0.1 */
    fun localFor(ranges: String): String {
        val net = ranges.substringBefore('-').substringBefore(',').substringBeforeLast('.', "")
        return if (net.isBlank()) "" else "$net.1"
    }

    /** سرعات جاهزة: (الاسم، rate-limit). */
    val PRESETS = listOf(
        "256K" to "256K/256K",
        "512K" to "512K/512K",
        "750K" to "750K/750K",
        "1M" to "1M/1M",
        "1.5M" to "1500K/1500K",
        "2M" to "2M/2M",
        "3M" to "3M/3M",
        "4M" to "4M/4M",
        "5M" to "5M/5M",
        "10M" to "10M/10M"
    )

    suspend fun servers(): List<Map<String, String>> =
        Session.api().print("/interface/pppoe-server/server", listOf(".id", "service-name", "interface", "disabled"))

    /** الواجهات الصالحة لخادم PPPoE (البريدجات أولاً). */
    suspend fun bridges(): List<String> =
        Session.api().print("/interface", listOf("name", "type"))
            .filter { it["type"] in setOf("bridge", "ether", "vlan") }
            .sortedBy { if (it["type"] == "bridge") 0 else 1 }
            .mapNotNull { it["name"] }

    /** تفعيل خادم PPPoE على واجهة (أو إنشاؤه إن لم يوجد). */
    suspend fun enableServer(iface: String) {
        val api = Session.api()
        val existing = servers().firstOrNull { it["interface"] == iface }
        if (existing != null) {
            api.exec("/interface/pppoe-server/server/enable", mapOf(".id" to existing[".id"]!!))
        } else {
            api.exec(
                "/interface/pppoe-server/server/add",
                linkedMapOf(
                    "service-name" to "shamel-$iface",
                    "interface" to iface,
                    "default-profile" to "default",
                    "one-session-per-host" to "yes",
                    "authentication" to "pap,chap,mschap1,mschap2",
                    "disabled" to "no"
                )
            )
        }
    }

    private fun actionCode(onExpire: String): String = when (onExpire) {
        "disable" -> "/ppp secret disable \$s; /ppp active remove [find name=\$n]"
        "page" -> "/ppp secret set \$s profile=EXPIRED; /ppp active remove [find name=\$n]"
        else -> ""
    }

    /** سكربت يفحص exp= في تعليقات الحسابات ويطبّق إجراء الانتهاء. */
    fun expiryScript(onExpire: String): String = """
:local d [/system clock get date]
:local m {"jan"="01";"feb"="02";"mar"="03";"apr"="04";"may"="05";"jun"="06";"jul"="07";"aug"="08";"sep"="09";"oct"="10";"nov"="11";"dec"="12"}
:local today
:if ([:pick ${'$'}d 3 4]="/") do={ :set today ([:pick ${'$'}d 7 11] . (${'$'}m->[:pick ${'$'}d 0 3]) . [:pick ${'$'}d 4 6]) } else={ :set today ([:pick ${'$'}d 0 4] . [:pick ${'$'}d 5 7] . [:pick ${'$'}d 8 10]) }
:foreach s in=[/ppp secret find where comment~"exp=" and disabled=no] do={
  :local c [/ppp secret get ${'$'}s comment]
  :local n [/ppp secret get ${'$'}s name]
  :local p [:find ${'$'}c "exp="]
  :local e [:pick ${'$'}c (${'$'}p+4) (${'$'}p+14)]
  :local ex ([:pick ${'$'}e 0 4] . [:pick ${'$'}e 5 7] . [:pick ${'$'}e 8 10])
  :if ([:tonum ${'$'}ex] < [:tonum ${'$'}today]) do={ ${actionCode(onExpire)} }
}
""".trim()

    /** سكربت عدّاد الجيجا القديم (used= في التعليق) — يُحذف الآن لصالح gigaCounter. */
    fun gbScript(onExpire: String): String = """
:global shamelLast
:global shamelMonth
:if ([:typeof ${'$'}shamelLast]!="array") do={ :set shamelLast [:toarray ""] }
:local mon [:pick [/system clock get date] 0 7]
:if ([:typeof ${'$'}shamelMonth]="str" and ${'$'}shamelMonth!=${'$'}mon) do={
  :foreach s in=[/ppp secret find where comment~";used="] do={
    :local c [/ppp secret get ${'$'}s comment]
    :local p [:find ${'$'}c ";used="]
    :local e [:find ${'$'}c ";" (${'$'}p+1)]
    :if ([:typeof ${'$'}e]!="num") do={ :set e [:len ${'$'}c] }
    /ppp secret set ${'$'}s comment=([:pick ${'$'}c 0 ${'$'}p] . [:pick ${'$'}c ${'$'}e [:len ${'$'}c]])
  }
}
:set shamelMonth ${'$'}mon
:foreach a in=[/ppp active find] do={
  :local n [/ppp active get ${'$'}a name]
  :local ii [/interface find name=("<pppoe-" . ${'$'}n . ">")]
  :if ([:len ${'$'}ii]>0) do={
    :local cur ([/interface get ${'$'}ii rx-byte] + [/interface get ${'$'}ii tx-byte])
    :local delta ${'$'}cur
    :local prev (${'$'}shamelLast->${'$'}n)
    :if ([:typeof ${'$'}prev]="str") do={
      :local k [:find ${'$'}prev ","]
      :if ([:pick ${'$'}prev 0 ${'$'}k]=[:tostr ${'$'}a]) do={ :set delta (${'$'}cur - [:tonum [:pick ${'$'}prev (${'$'}k+1) [:len ${'$'}prev]]]) }
    }
    :set (${'$'}shamelLast->${'$'}n) ([:tostr ${'$'}a] . "," . ${'$'}cur)
    :local s [/ppp secret find name=${'$'}n]
    :if (${'$'}delta>0 and [:len ${'$'}s]>0) do={
      :local c [/ppp secret get ${'$'}s comment]
      :local used 0
      :local p [:find ${'$'}c ";used="]
      :if ([:typeof ${'$'}p]="num") do={
        :local e [:find ${'$'}c ";" (${'$'}p+1)]
        :if ([:typeof ${'$'}e]!="num") do={ :set e [:len ${'$'}c] }
        :set used [:tonum [:pick ${'$'}c (${'$'}p+6) ${'$'}e]]
        :set c ([:pick ${'$'}c 0 ${'$'}p] . [:pick ${'$'}c ${'$'}e [:len ${'$'}c]])
      }
      :set used (${'$'}used + ${'$'}delta)
      /ppp secret set ${'$'}s comment=(${'$'}c . ";used=" . ${'$'}used)
      :local g [:find ${'$'}c ";gb="]
      :if ([:typeof ${'$'}g]="num") do={
        :local ge [:find ${'$'}c ";" (${'$'}g+1)]
        :if ([:typeof ${'$'}ge]!="num") do={ :set ge [:len ${'$'}c] }
        :local gb [:tonum [:pick ${'$'}c (${'$'}g+4) ${'$'}ge]]
        :if (${'$'}gb>0 and ${'$'}used>=(${'$'}gb*1073741824)) do={ ${actionCode(onExpire)} }
      }
    }
  }
}
""".trim()

    private suspend fun installScript(name: String, source: String, interval: String) {
        val api = Session.api()
        api.print("/system/scheduler", listOf(".id"), listOf("?name=$name")).mapNotNull { it[".id"] }
            .forEach { api.exec("/system/scheduler/remove", mapOf(".id" to it)) }
        api.print("/system/script", listOf(".id"), listOf("?name=$name")).mapNotNull { it[".id"] }
            .forEach { api.exec("/system/script/remove", mapOf(".id" to it)) }
        api.exec("/system/script/add", mapOf("name" to name, "source" to source, "policy" to "read,write,test"))
        api.exec(
            "/system/scheduler/add",
            mapOf("name" to name, "interval" to interval, "on-event" to "/system script run $name", "policy" to "read,write,test")
        )
    }

    private suspend fun removeScript(name: String) {
        val api = Session.api()
        api.print("/system/scheduler", listOf(".id"), listOf("?name=$name")).mapNotNull { it[".id"] }
            .forEach { api.exec("/system/scheduler/remove", mapOf(".id" to it)) }
        api.print("/system/script", listOf(".id"), listOf("?name=$name")).mapNotNull { it[".id"] }
            .forEach { api.exec("/system/script/remove", mapOf(".id" to it)) }
    }

    /** تطبيق الإعدادات على الراوتر. */
    suspend fun apply(s: PppSettings) {
        val api = Session.api()
        if (s.onExpire == "page" && api.count("/ppp/profile", listOf("?name=$EXPIRED_PROFILE")) == 0) {
            api.exec(
                "/ppp/profile/add",
                mapOf(
                    "name" to EXPIRED_PROFILE,
                    "rate-limit" to "64k/64k",
                    "address-list" to "ppp-expired",
                    "comment" to "حسابات منتهية — سرعة بطيئة وقائمة ppp-expired لتحويلها لصفحة الانتهاء"
                )
            )
        }
        if (s.onExpire != "none") installScript(EXPIRY_SCRIPT, expiryScript(s.onExpire), "1h")
        else removeScript(EXPIRY_SCRIPT)
        // عدّاد الجيجا صار عبر gigaCounter (/ip proxy direct) — نحذف السكربت القديم دائماً
        removeScript(GB_SCRIPT)
        setGigaCounter(s.gbCounter)
    }

    // ---- عدّاد الجيجا (gigaCounter): سجل /ip proxy direct لكل مستخدم بتعليق <pppoe-name> ----

    private fun num(s: String?, key: String): Long =
        s?.let { Regex("$key:(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L

    /** الاستهلاك الكلي (بايت) لكل مستخدم من سجلات gigaCounter. */
    suspend fun gigaUsage(): Map<String, Long> =
        Session.api().print("/ip/proxy/direct", listOf("comment", "dst-host", "method"))
            .filter { (it["comment"] ?: "").startsWith("<pppoe-") }
            .associate { r ->
                r["comment"]!!.removePrefix("<pppoe-").removeSuffix(">") to
                    (num(r["dst-host"], "rx") + num(r["dst-host"], "tx") + num(r["method"], "rx") + num(r["method"], "tx"))
            }

    suspend fun gigaEnabled(): Boolean = runCatching {
        Session.api().count("/system/scheduler", listOf("?name=gigaCounter")) > 0
    }.getOrDefault(false)

    /** حد الجيجا يُخزَّن في path: /تاريخ-البداية_الحد-بالبايت */
    suspend fun setGigaLimit(user: String, gb: Long) {
        val api = Session.api()
        val iface = "<pppoe-$user>"
        val entry = api.print("/ip/proxy/direct", listOf(".id", "path", "comment"), listOf("?comment=$iface")).firstOrNull()
        val start = entry?.get("path")?.removePrefix("/")?.substringBefore('_')?.ifBlank { null } ?: routerDate()
        val path = if (gb > 0) "/${start}_${gb * 1073741824L}" else "/$start"
        if (entry != null) {
            api.exec("/ip/proxy/direct/set", mapOf(".id" to entry[".id"]!!, "path" to path))
        } else {
            if (gb <= 0) return
            api.exec(
                "/ip/proxy/direct/add",
                linkedMapOf(
                    "dst-host" to "rx:0 tx:0",
                    "method" to "rx:0 tx:0",
                    "path" to path,
                    "dst-port" to "1",
                    "comment" to iface
                )
            )
        }
    }

    /** تصفير عداد الجيجا للمستخدم. */
    suspend fun resetGiga(user: String) {
        val api = Session.api()
        api.print("/ip/proxy/direct", listOf(".id"), listOf("?comment=<pppoe-$user>")).mapNotNull { it[".id"] }
            .forEach {
                api.exec(
                    "/ip/proxy/direct/set",
                    mapOf(".id" to it, "dst-host" to "rx:0 tx:0", "method" to "rx:0 tx:0", "dst-port" to "1")
                )
            }
    }

    private suspend fun routerDate(): String =
        runCatching {
            Session.api().print("/system/clock", listOf("date")).firstOrNull()?.get("date")
        }.getOrNull()
            ?: SimpleDateFormat("MMM/dd/yyyy", Locale.US).format(Date()).lowercase(Locale.ROOT)

    /** تشغيل/إيقاف مجدول gigaCounter. */
    suspend fun setGigaCounter(on: Boolean) {
        val api = Session.api()
        api.print("/system/scheduler", listOf(".id"), listOf("?name=gigaCounter")).mapNotNull { it[".id"] }
            .forEach { api.exec("/system/scheduler/remove", mapOf(".id" to it)) }
        if (!on) return
        api.exec(
            "/system/scheduler/add",
            linkedMapOf(
                "name" to GigaCounterScript.NAME,
                "interval" to "00:20:00",
                "on-event" to GigaCounterScript.SOURCE,
                "policy" to "ftp,reboot,read,write,policy,test,password,sniff,sensitive,romon"
            )
        )
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
