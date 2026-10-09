package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.RouterOsClient
import kotlinx.coroutines.sync.withLock

import com.zarinet.netcontrol.api.ApiResult
import com.zarinet.netcontrol.api.RouterOsException
import com.zarinet.netcontrol.data.Session
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** حالة مشترك يوزر منجر. */
enum class UmStatus { USED, EXPIRED, UNUSED }

data class UmUserRow(
    val id: String, val name: String, val password: String, val profile: String,
    val status: UmStatus, val disabled: Boolean, val comment: String, val info: String,
    val lastSeen: Long? = null, val online: Boolean = false, val usedBytes: Long = 0, val remainingMs: Long? = null,
    /** وقت أول دخول (بداية احتساب الصلاحية). */
    val firstLogin: Long? = null,
    /** وقت انتهاء الصلاحية المتوقع. */
    val expiresAt: Long? = null,
    /** حد البيانات للباقة بالبايت (0 = مفتوح). */
    val limitBytes: Long = 0
)

data class UmPackageRow(
    val id: String, val name: String, val validity: String, val price: String,
    val transfer: Long, val rate: String, val limitationIds: List<String>, val linkIds: List<String>,
    val rateRx: String = "", val rateTx: String = ""
)

/**
 * طبقة موحدة ليوزر منجر تعمل على الإصدارين:
 * - v6: /tool user-manager (مع customer، والمستخدم username، وتفعيل الباقة create-and-activate-profile)
 * - v7: /user-manager (المستخدم name، والباقة عبر user-profile)
 * يُكتشف الإصدار تلقائياً عند أول استخدام لكل جهاز.
 */
object UserManager {
    enum class Ver { V6, V7 }

    private var cachedFor: String? = null
    private var ver: Ver? = null
    private var customer: String = "admin"

    private val verLock = kotlinx.coroutines.sync.Mutex()

    /**
     * يكتشف الإصدار مرة واحدة لكل جهاز: الفحوص الثلاثة تُرسل دفعة واحدة (رحلة واحدة للراوتر)،
     * والطلبات المتزامنة تنتظر نفس الاكتشاف بدل تكراره.
     */
    suspend fun version(): Ver {
        val key = Session.device?.id
        ver?.let { if (key == cachedFor) return it }
        return verLock.withLock {
            ver?.let { if (key == cachedFor) return@withLock it }
            val api = Session.api()
            val r = api.batch(listOf(
                RouterOsClient.Cmd("/tool/user-manager/customer/print", proplist = listOf("login")),
                RouterOsClient.Cmd("/user-manager/user/print", mapOf("count-only" to ""))
            ))
            val v6 = r[0].getOrNull()
            val v = when {
                v6 != null -> {
                    // v6: نختار عميل الدخول (customer) — يفضّل نفس اسم مستخدم الجهاز ثم admin
                    val customers = v6.rows.mapNotNull { it["login"] }
                    customer = customers.firstOrNull { it == Session.device?.user } ?: customers.firstOrNull { it == "admin" }
                        ?: customers.firstOrNull() ?: "admin"
                    Ver.V6
                }
                r[1].isSuccess -> Ver.V7
                else -> {
                    // خطأ اتصال (وليس «أمر غير معروف») يُعرض كما هو
                    val e = r[0].exceptionOrNull()
                    if (e != null && e !is RouterOsException) throw e
                    throw RouterOsException("يوزر منجر غير مثبت على هذا الراوتر.\nثبّت حزمة user-manager (v6) أو فعّل User Manager (v7).")
                }
            }
            ver = v; cachedFor = key
            v
        }
    }

    suspend fun isV6() = version() == Ver.V6
    fun customerName() = customer

    private fun base(v: Ver) = if (v == Ver.V6) "/tool/user-manager" else "/user-manager"

    // ─────────── المشتركون ───────────

    suspend fun users(): List<UmUserRow> {
        val v = version(); val api = Session.api()
        val now = System.currentTimeMillis()
        // v6: كل القراءات المستقلة في رحلة واحدة (Pipelining) — أسرع كثيراً عبر السحابة
        val pre: List<List<Map<String, String>>>? = if (v == Ver.V6) api.batch(listOf(
            RouterOsClient.Cmd("/tool/user-manager/profile/limitation", proplist = listOf("name", "transfer-limit", "uptime-limit")),
            RouterOsClient.Cmd("/tool/user-manager/profile/profile-limitation", proplist = listOf("profile", "limitation")),
            RouterOsClient.Cmd("/tool/user-manager/session", proplist = listOf("user", "from-time", "till-time", "active")),
            RouterOsClient.Cmd("/tool/user-manager/profile", proplist = listOf("name", "validity")),
            RouterOsClient.Cmd("/tool/user-manager/user", proplist = listOf(".id", "username", "password", "disabled", "comment",
                "actual-profile", "last-seen", "uptime-used", "download-used", "upload-used")),
        ).map { it.copy(command = it.command + "/print") }).mapIndexed { i, r ->
            if (i == 4) r.getOrThrow().rows else r.getOrNull()?.rows ?: emptyList()   // فشل قائمة الكروت يُعرض كخطأ
        } else null
        // v7: نفس الفكرة — القراءات الخمس في رحلة واحدة بدل خمس رحلات متتالية
        val pre7: List<List<Map<String, String>>>? = if (v == Ver.V7) api.batch(listOf(
            RouterOsClient.Cmd("/user-manager/limitation/print", proplist = listOf("name", "transfer-limit")),
            RouterOsClient.Cmd("/user-manager/profile-limitation/print", proplist = listOf("profile", "limitation")),
            RouterOsClient.Cmd("/user-manager/user-profile/print", proplist = listOf("user", "profile", "state", "end-time")),
            RouterOsClient.Cmd("/user-manager/session/print", proplist = listOf("user", "started", "ended", "status", "download", "upload")),
            RouterOsClient.Cmd("/user-manager/user/print", proplist = listOf(".id", "name", "password", "disabled", "comment")),
        )).mapIndexed { i, r -> if (i == 2 || i == 4) r.getOrThrow().rows else r.getOrNull()?.rows ?: emptyList() } else null
        val limitOf: Map<String, Long> = if (pre != null) {
            val lim = pre[0].associate { it["name"].orEmpty() to (it["transfer-limit"]?.toLongOrNull() ?: 0L) }
            pre[1].groupBy { it["profile"].orEmpty() }
                .mapValues { (_, links) -> dataLimit(links.mapNotNull { it["limitation"] }.distinct().map { lim[it] ?: 0L }) }
        } else {
            val lim = pre7!![0].associate { it["name"].orEmpty() to (it["transfer-limit"]?.toLongOrNull() ?: 0L) }
            pre7[1].groupBy { it["profile"].orEmpty() }
                .mapValues { (_, links) -> dataLimit(links.mapNotNull { it["limitation"] }.distinct().map { lim[it] ?: 0L }) }
        }

        return if (v == Ver.V6 && pre != null) {
            val sessions = pre[2].groupBy { it["user"].orEmpty() }
            val validity = pre[3].associate { it["name"].orEmpty() to Fmt.seconds(it["validity"]) }
            val uptimeLimits: Map<String, Long> = run {
                val lim = pre[0].associate { it["name"].orEmpty() to Fmt.seconds(it["uptime-limit"]) }
                pre[1].groupBy { it["profile"].orEmpty() }
                    .mapValues { (_, links) -> links.mapNotNull { l -> lim[l["limitation"]]?.takeIf { it > 0 } }.minOrNull() ?: 0L }
            }
            val list = pre[4]

            // أقدم بداية جلسة لكل مستخدم
            val first = HashMap<String, Long>()
            fun addFirst(user: String, t: Long?) {
                if (t != null) first[user] = minOf(first[user] ?: Long.MAX_VALUE, t)
            }
            sessions.forEach { (user, ss) -> ss.forEach { addFirst(user, RosTime.parse(it["from-time"], now)) } }
            // قائمة الجلسات قد تكون مقطوعة — نستعلم فقط عن الكروت المستخدمة التي لا نعرف بدايتها
            // (لا من ملاحظة الكرت ولا من الجلسات)، وكل الاستعلامات في دفعة واحدة
            val activeNames = list.filter { u ->
                !u["actual-profile"].isNullOrBlank() && u["last-seen"].orEmpty().let { it.isNotBlank() && it != "never" } &&
                    startFromComment(u["comment"], now) == null && u["username"] !in first
            }.mapNotNull { it["username"] }
            val chunks = activeNames.chunked(40)
            if (chunks.isNotEmpty()) runCatching {
                api.batch(chunks.map { RouterOsClient.Cmd("/tool/user-manager/session/print", proplist = listOf("user", "from-time"),
                    queries = ActiveInfo.orQuery("user", it)) })
            }.getOrNull()?.forEachIndexed { i, r ->
                val chunk = chunks[i].toSet()
                r.getOrNull()?.rows?.forEach { s -> s["user"]?.takeIf { it in chunk }?.let { addFirst(it, RosTime.parse(s["from-time"], now)) } }
            }

            // المرشّحات الثلاثة لحساب المتبقي + وقت أول دخول
            fun cand(u: Map<String, String>): Pair<V6Cand, Long?> {
                val name = u["username"].orEmpty()
                val actual = u["actual-profile"].orEmpty()
                val start = startFromComment(u["comment"], now) ?: first[name]
                val valid = validity[actual] ?: 0L
                val validRem = if (start != null && valid > 0) (start + 1000 * valid - now).coerceAtLeast(0) else null
                return v6Cand(validRem, valid, uptimeLimits[actual] ?: 0L, Fmt.seconds(u["uptime-used"])) to start
            }

            list.map { u ->
                val name = u["username"].orEmpty()
                val actual = u["actual-profile"].orEmpty()
                val seenRaw = u["last-seen"].orEmpty()
                val never = seenRaw.isBlank() || seenRaw == "never"
                val st = when { never -> UmStatus.UNUSED; actual.isNotBlank() -> UmStatus.USED; else -> UmStatus.EXPIRED }
                val used = (u["download-used"]?.toLongOrNull() ?: 0) + (u["upload-used"]?.toLongOrNull() ?: 0)
                val online = sessions[name].orEmpty().any { it["active"] == "true" || it["active"] == "yes" }
                val (c, firstLogin) = cand(u)
                val remaining = if (st == UmStatus.USED) v6Remaining(c, null) else null
                val expiresAt = remaining?.let { now + it }
                UmUserRow(u[".id"].orEmpty(), name, u["password"].orEmpty(), actual, st,
                    u["disabled"] == "true", u["comment"].orEmpty(), "",
                    lastSeen = RosTime.parse(seenRaw, now), online = online, usedBytes = used, remainingMs = remaining,
                    firstLogin = firstLogin, expiresAt = expiresAt, limitBytes = limitOf[actual] ?: 0L)
            }
        } else {
            val byUser = pre7!![2].groupBy { it["user"].orEmpty() }
            // الجلسات: آخر ظهور، المتصل الآن، والاستهلاك
            val sessions = pre7[3].groupBy { it["user"].orEmpty() }
            pre7[4].map { u ->
                val name = u["name"].orEmpty()
                val links = byUser[name].orEmpty()
                val states = links.map { it["state"].orEmpty() }
                val st = when {
                    states.any { it.startsWith("running") } -> UmStatus.USED
                    states.any { it == "used" } -> UmStatus.EXPIRED
                    else -> UmStatus.UNUSED
                }
                val ss = sessions[name].orEmpty()
                val online = ss.any { s -> s["ended"].isNullOrBlank() && !s["status"].orEmpty().contains("stop") } ||
                    states.any { it == "running-active" }
                val last = ss.mapNotNull { RosTime.parse(it["ended"]?.ifBlank { null } ?: it["started"], now) }.maxOrNull()
                val used = ss.sumOf { (it["download"]?.toLongOrNull() ?: 0) + (it["upload"]?.toLongOrNull() ?: 0) }
                val end = RosTime.parse(links.lastOrNull()?.get("end-time").orEmpty(), now)
                val remaining = if (st == UmStatus.USED && end != null) (end - now).coerceAtLeast(0) else null
                val firstLogin = ss.mapNotNull { RosTime.parse(it["started"], now) }.minOrNull()
                val profile = links.lastOrNull()?.get("profile").orEmpty()
                UmUserRow(u[".id"].orEmpty(), name, u["password"].orEmpty(), profile, st,
                    u["disabled"] == "true", u["comment"].orEmpty(), "",
                    lastSeen = last, online = online, usedBytes = used, remainingMs = remaining,
                    firstLogin = firstLogin, expiresAt = end, limitBytes = limitOf[profile] ?: 0L)
            }
        }
    }

    /**
     * تاريخ أول دخول المكتوب في التعليق (قبل ;) مثل "sep/24/2026 10:00:00" أو "2026-09-24_10:00".
     * يُرفض التاريخ المستقبلي بأكثر من يوم.
     */
    internal fun startFromComment(comment: String?, now: Long = System.currentTimeMillis()): Long? {
        val c = comment?.substringBefore(';')?.trim().orEmpty()
        val s = Regex("(?<=\\d)[_T](?=\\d{1,2}:)").replace(c, " ")
        if (!Regex("^([a-zA-Z]{3}/\\d{1,2}/\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4})( \\d{1,2}:\\d{2}(:\\d{2})?)?$").matches(s)) return null
        val t = RosTime.parse(s, now) ?: return null
        return if (t <= now + 86_400_000L) t else null
    }

    /** حد الوقت (uptime-limit) لكل باقة v6 بالثواني (أصغر حد موجب). */
    private suspend fun v6UptimeLimits(): Map<String, Long> = runCatching {
        val api = Session.api()
        val lim = api.print("/tool/user-manager/profile/limitation", listOf("name", "uptime-limit"))
            .associate { it["name"].orEmpty() to Fmt.seconds(it["uptime-limit"]) }
        api.print("/tool/user-manager/profile/profile-limitation", listOf("profile", "limitation")).groupBy { it["profile"].orEmpty() }
            .mapValues { (_, links) -> links.mapNotNull { l -> lim[l["limitation"]]?.takeIf { it > 0 } }.minOrNull() ?: 0L }
    }.getOrDefault(emptyMap())

    /** الوقت المتبقي لجلسات الهوتسبوت النشطة (session-time-left) بالمللي ثانية. */
    private suspend fun hotspotTimeLeft(): Map<String, Long> = runCatching {
        Session.api().print("/ip/hotspot/active", listOf("user", "session-time-left")).mapNotNull { a ->
            Fmt.seconds(a["session-time-left"]).takeIf { it > 0 }?.let { a["user"].orEmpty() to it * 1000 }
        }.toMap()
    }.getOrDefault(emptyMap())

    // ─── v6: حساب المتبقي ───
    // v6 لا يعطي تاريخ الانتهاء مباشرة، فنحسبه بإحدى ثلاث طرق:
    // FIRST: من أول دخول + مدة الصلاحية، VALID_UPTIME: الصلاحية − وقت الاستخدام، LIMIT_UPTIME: حد الوقت − وقت الاستخدام.

    enum class V6Method { FIRST, VALID_UPTIME, LIMIT_UPTIME }

    /** الطريقة الأنسب لكل جهاز (بعد المعايرة مع قيم فعلية). */
    private val v6Method = HashMap<String, V6Method>()

    internal class V6Cand(val first: Long?, val validUptime: Long?, val limitUptime: Long?) {
        fun of(m: V6Method): Long? = when (m) {
            V6Method.FIRST -> first
            V6Method.VALID_UPTIME -> validUptime
            V6Method.LIMIT_UPTIME -> limitUptime
        }
    }

    internal fun v6Cand(validityRem: Long?, validitySecs: Long, uptimeLimit: Long, uptimeUsed: Long) = V6Cand(
        validityRem,
        if (validitySecs > 0) (validitySecs - uptimeUsed).coerceAtLeast(0) * 1000 else null,
        if (uptimeLimit > 0) (uptimeLimit - uptimeUsed).coerceAtLeast(0) * 1000 else null
    )

    /** يختار الطريقة التي تطابق أكبر عدد من العينات (فرق ≤ ساعة أو 2%). */
    internal fun pickV6Method(samples: List<Pair<V6Cand, Long>>): V6Method? {
        if (samples.isEmpty()) return null
        return V6Method.entries.associateWith { m ->
            samples.count { (c, real) -> c.of(m)?.let { abs(it - real) <= max(3_600_000L, real / 50) } == true }
        }.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
    }

    private fun calibrateV6(samples: List<Pair<V6Cand, Long>>) {
        pickV6Method(samples)?.let { v6Method[Session.device?.id.orEmpty()] = it }
    }

    private fun v6Remaining(c: V6Cand, live: Long?): Long? = live ?: c.first ?: c.limitUptime

    /** فهرس كل مشتركي v6 (يُخزّن 10 دقائق) — احتياط لمن لم يُعثر عليه بالاستعلام المباشر. */
    private suspend fun v6Index(limitOf: Map<String, Long>): Map<String, CardInfo> {
        ScreenCache.get<Map<String, CardInfo>>("um:v6idx")?.takeIf { ScreenCache.fresh("um:v6idx", 600_000L) }?.let { return it }
        // إن كانت قائمة المشتركين محمّلة حديثاً نبني الفهرس منها بدون طلبات
        val rows = ScreenCache.get<List<UmUserRow>>("um:users")?.takeIf { ScreenCache.fresh("um:users", 600_000L) }
        if (rows != null) {
            val idx = rows.associate {
                it.name to CardInfo(it.profile, it.remainingMs,
                    if (it.limitBytes > 0) (it.limitBytes - it.usedBytes).coerceAtLeast(0) else null, it.usedBytes)
            }
            ScreenCache.put("um:v6idx", idx)
            return idx
        }
        val uptimeLimits = v6UptimeLimits()
        val validity = runCatching {
            Session.api().print("/tool/user-manager/profile", listOf("name", "validity"))
                .associate { it["name"].orEmpty() to Fmt.seconds(it["validity"]) }
        }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        val idx = Session.api().print("/tool/user-manager/user",
            listOf("username", "actual-profile", "download-used", "upload-used", "uptime-used", "comment")).associate { u ->
            val profile = u["actual-profile"].orEmpty()
            val used = (u["download-used"]?.toLongOrNull() ?: 0L) + (u["upload-used"]?.toLongOrNull() ?: 0L)
            val limit = limitOf[profile] ?: 0L
            val valid = validity[profile] ?: 0L
            val validRem = startFromComment(u["comment"], now)?.takeIf { valid > 0 }?.let { (it + 1000 * valid - now).coerceAtLeast(0) }
            val rem = v6Remaining(v6Cand(validRem, valid, uptimeLimits[profile] ?: 0L, Fmt.seconds(u["uptime-used"])), null)
            u["username"].orEmpty() to CardInfo(profile, rem, if (limit > 0) (limit - used).coerceAtLeast(0) else null, used)
        }
        ScreenCache.put("um:v6idx", idx)
        return idx
    }

    /** معلومات كروت يوزر منجر المتصلة (الباقة، المتبقي من الوقت والبيانات) باستعلامات مجمّعة. */
    suspend fun activeInfo(names: List<String>, now: Long = System.currentTimeMillis()): Map<String, CardInfo> {
        val v = version(); val api = Session.api()
        val list = names.distinct().filter { it.isNotBlank() }
        if (list.isEmpty()) return emptyMap()
        val limPath = if (v == Ver.V6) "/tool/user-manager/profile/limitation" else "/user-manager/limitation"
        val linkPath = if (v == Ver.V6) "/tool/user-manager/profile/profile-limitation" else "/user-manager/profile-limitation"
        val chunks = list.chunked(40)
        // كل القراءات (القيود، الروابط، الصلاحيات، ثم جلسات وكروت كل مجموعة) في دفعة واحدة — رحلة واحدة للراوتر
        val cmds = ArrayList<RouterOsClient.Cmd>()
        cmds += RouterOsClient.Cmd("$limPath/print", proplist = listOf("name", "transfer-limit", "uptime-limit"))
        cmds += RouterOsClient.Cmd("$linkPath/print", proplist = listOf("profile", "limitation"))
        cmds += if (v == Ver.V6) RouterOsClient.Cmd("/tool/user-manager/profile/print", proplist = listOf("name", "validity"))
            else RouterOsClient.Cmd("/user-manager/profile/print", proplist = listOf("name"))
        for (chunk in chunks) {
            if (v == Ver.V6) {
                cmds += RouterOsClient.Cmd("/tool/user-manager/session/print", proplist = listOf("user", "from-time"), queries = ActiveInfo.orQuery("user", chunk))
                cmds += RouterOsClient.Cmd("/tool/user-manager/user/print",
                    proplist = listOf("username", "actual-profile", "download-used", "upload-used", "uptime-used", "comment"),
                    queries = ActiveInfo.orQuery("username", chunk))
            } else {
                cmds += RouterOsClient.Cmd("/user-manager/session/print", proplist = listOf("user", "download", "upload"), queries = ActiveInfo.orQuery("user", chunk))
                cmds += RouterOsClient.Cmd("/user-manager/user-profile/print", proplist = listOf("user", "profile", "state", "end-time"),
                    queries = ActiveInfo.orQuery("user", chunk))
            }
        }
        val res = api.batch(cmds, 60)
        fun rows(i: Int) = res[i].getOrNull()?.rows ?: emptyList()
        val limRows = rows(0); val linkRows = rows(1)
        val limitOf: Map<String, Long> = run {
            val lim = limRows.associate { it["name"].orEmpty() to (it["transfer-limit"]?.toLongOrNull() ?: 0L) }
            linkRows.groupBy { it["profile"].orEmpty() }
                .mapValues { (_, links) -> dataLimit(links.mapNotNull { it["limitation"] }.distinct().map { lim[it] ?: 0L }) }
        }
        val out = HashMap<String, CardInfo>()

        if (v == Ver.V6) {
            val validity = rows(2).associate { it["name"].orEmpty() to Fmt.seconds(it["validity"]) }
            val uptimeLimits: Map<String, Long> = run {
                val lim = limRows.associate { it["name"].orEmpty() to Fmt.seconds(it["uptime-limit"]) }
                linkRows.groupBy { it["profile"].orEmpty() }
                    .mapValues { (_, links) -> links.mapNotNull { l -> lim[l["limitation"]]?.takeIf { it > 0 } }.minOrNull() ?: 0L }
            }
            chunks.forEachIndexed { ci, chunk ->
                // أول دخول لكل كرت من جلساته
                val first = rows(3 + ci * 2).groupBy { it["user"].orEmpty() }
                    .mapValues { (_, ss) -> ss.mapNotNull { RosTime.parse(it["from-time"], now) }.minOrNull() }
                for (u in rows(4 + ci * 2)) {
                    val name = u["username"].orEmpty()
                    val profile = u["actual-profile"].orEmpty()
                    if (name !in chunk) continue
                    val used = (u["download-used"]?.toLongOrNull() ?: 0L) + (u["upload-used"]?.toLongOrNull() ?: 0L)
                    val valid = validity[profile] ?: 0L
                    val start = startFromComment(u["comment"], now) ?: first[name]
                    val validRem = start?.takeIf { valid > 0 }?.let { (it + 1000 * valid - now).coerceAtLeast(0) }
                    val rem = v6Remaining(v6Cand(validRem, valid, uptimeLimits[profile] ?: 0L, Fmt.seconds(u["uptime-used"])), null)
                    val limit = limitOf[profile] ?: 0L
                    out[name] = CardInfo(profile, rem, if (limit > 0) (limit - used).coerceAtLeast(0) else null, used)
                }
            }
            // من لم يُعثر عليه (اختلاف حالة الأحرف مثلاً) نبحث عنه في الفهرس الكامل
            val missing = list.filter { !out.containsKey(it) }
            if (missing.isEmpty()) return out
            val idx = runCatching { v6Index(limitOf) }.getOrNull() ?: return out
            missing.forEach { n -> idx[n]?.let { out[n] = it } }
            return out
        } else {
            chunks.forEachIndexed { ci, _ ->
                val used = rows(3 + ci * 2).groupBy { it["user"].orEmpty() }
                    .mapValues { (_, ss) -> ss.sumOf { (it["download"]?.toLongOrNull() ?: 0L) + (it["upload"]?.toLongOrNull() ?: 0L) } }
                rows(4 + ci * 2).groupBy { it["user"].orEmpty() }
                    .forEach { (name, links) ->
                        // الباقة الجارية أولاً، وإلا آخر باقة
                        val link = links.firstOrNull { it["state"].orEmpty().startsWith("running") } ?: links.last()
                        val profile = link["profile"].orEmpty()
                        val rem = RosTime.parse(link["end-time"].orEmpty(), now)?.let { (it - now).coerceAtLeast(0) }
                        val limit = limitOf[profile] ?: 0L
                        out[name] = CardInfo(profile, rem,
                            if (limit > 0) (limit - (used[name] ?: 0L)).coerceAtLeast(0) else null, used[name])
                    }
            }
            return out
        }
    }

    // ─────────── الدفعات ───────────

    /** دفعة كروت: الملاحظة الكاملة (مفتاح الاستعلام)، الاسم المختصر للعرض، وعدد الكروت. */
    data class UmBatch(val comment: String, val label: String, val count: Int)

    /** اسم الدفعة للعرض: الجزء قبل ; (مثل vc-260930-1200) مع نقطة البيع إن وُجدت. */
    fun batchLabel(comment: String): String {
        val parts = comment.split(';').map { it.trim() }
        val pos = parts.firstOrNull { it.startsWith("pos=") }?.removePrefix("pos=").orEmpty()
        return parts.first().ifBlank { comment } + if (pos.isNotBlank()) " • $pos" else ""
    }

    /** كل الدفعات (حسب ملاحظة الكرت) بقراءة خفيفة لحقل واحد؛ الأحدث أولاً. تُستبعد ملاحظات تاريخ أول دخول. */
    suspend fun batches(): List<UmBatch> {
        val now = System.currentTimeMillis()
        return Session.api().print("${base(version())}/user", listOf("comment"))
            .mapNotNull { it["comment"]?.trim()?.takeIf(String::isNotBlank) }
            .filter { startFromComment(it, now) == null }
            .groupingBy { it }.eachCount()
            .map { (c, n) -> UmBatch(c, batchLabel(c), n) }
            .sortedWith(compareByDescending<UmBatch> { it.label.startsWith("vc-") }.thenByDescending { it.label })
    }

    /** كروت دفعة واحدة مع حالتها (مستخدمة/جديدة/منتهية/معطلة) — يُجلب فقط ما يخص الدفعة. */
    suspend fun batchCards(comment: String): List<UmUserRow> {
        val v = version(); val api = Session.api()
        val now = System.currentTimeMillis()
        if (v == Ver.V6) {
            return api.print("/tool/user-manager/user", listOf(".id", "username", "password", "disabled", "comment", "actual-profile",
                "last-seen", "download-used", "upload-used"), listOf("?comment=$comment")).map { u ->
                val seenRaw = u["last-seen"].orEmpty()
                val never = seenRaw.isBlank() || seenRaw == "never"
                val actual = u["actual-profile"].orEmpty()
                val st = when { never -> UmStatus.UNUSED; actual.isNotBlank() -> UmStatus.USED; else -> UmStatus.EXPIRED }
                UmUserRow(u[".id"].orEmpty(), u["username"].orEmpty(), u["password"].orEmpty(), actual, st,
                    u["disabled"] == "true", u["comment"].orEmpty(), "",
                    lastSeen = if (never) null else RosTime.parse(seenRaw, now),
                    usedBytes = (u["download-used"]?.toLongOrNull() ?: 0) + (u["upload-used"]?.toLongOrNull() ?: 0))
            }.sortedBy { it.name }
        }
        val users = api.print("/user-manager/user", listOf(".id", "name", "password", "disabled", "comment"), listOf("?comment=$comment"))
        val names = users.mapNotNull { it["name"] }
        val chunks = names.chunked(40)
        val res = if (chunks.isEmpty()) emptyList() else api.batch(chunks.map {
            RouterOsClient.Cmd("/user-manager/user-profile/print", proplist = listOf("user", "profile", "state", "end-time"),
                queries = ActiveInfo.orQuery("user", it))
        }, 60)
        val byUser = res.flatMap { it.getOrNull()?.rows ?: emptyList() }.groupBy { it["user"].orEmpty() }
        return users.map { u ->
            val name = u["name"].orEmpty()
            val links = byUser[name].orEmpty()
            val states = links.map { it["state"].orEmpty() }
            val st = when {
                states.any { it.startsWith("running") } -> UmStatus.USED
                states.any { it == "used" } -> UmStatus.EXPIRED
                else -> UmStatus.UNUSED
            }
            val end = RosTime.parse(links.lastOrNull()?.get("end-time").orEmpty(), now)
            UmUserRow(u[".id"].orEmpty(), name, u["password"].orEmpty(), links.lastOrNull()?.get("profile").orEmpty(), st,
                u["disabled"] == "true", u["comment"].orEmpty(), "",
                online = states.any { it == "running-active" },
                remainingMs = if (st == UmStatus.USED && end != null) (end - now).coerceAtLeast(0) else null, expiresAt = end)
        }.sortedBy { it.name }
    }

    /** تنفيذ جماعي (حذف/تعطيل/تفعيل) على عدة معرّفات دفعة واحدة. */
    suspend fun bulk(action: String, ids: List<String>, progress: (Int) -> Unit = {}) {
        val path = "${base(version())}/user/$action"
        var done = 0
        ids.chunked(100).forEach { chunk ->
            Session.api().exec(path, mapOf(".id" to chunk.joinToString(",")))
            done += chunk.size; progress(done)
        }
    }

    suspend fun userNames(): Set<String> {
        val v = version()
        val field = if (v == Ver.V6) "username" else "name"
        return Session.api().print("${base(v)}/user", listOf(field)).mapNotNull { it[field] }.toHashSet()
    }

    /** أسماء الكروت الموجودة فعلاً من بين [names] — بدون تحميل كل الكروت. */
    suspend fun existing(names: List<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val v = version()
        val field = if (v == Ver.V6) "username" else "name"
        val chunks = names.chunked(40)
        return Session.api().batch(chunks.map {
            RouterOsClient.Cmd("${base(v)}/user/print", proplist = listOf(field), queries = ActiveInfo.orQuery(field, it))
        }, 60).flatMap { r -> r.getOrThrow().rows.mapNotNull { it[field] } }.toHashSet()
    }

    suspend fun comments(): List<String> =
        Session.api().print("${base(version())}/user", listOf("comment")).mapNotNull { it["comment"]?.takeIf(String::isNotBlank) }.distinct().sorted()

    suspend fun byComment(comment: String): List<Voucher> {
        val v = version()
        val field = if (v == Ver.V6) "username" else "name"
        return Session.api().print("${base(v)}/user", listOf(field, "password"), listOf("?comment=$comment"))
            .map { Voucher(it[field].orEmpty(), it["password"].orEmpty()) }
    }

    suspend fun addUser(name: String, pass: String, profile: String, comment: String, bind: Boolean) {
        val v = version(); val api = Session.api()
        if (v == Ver.V6) {
            val p = linkedMapOf("customer" to customer, "username" to name, "password" to pass)
            if (comment.isNotBlank()) p["comment"] = comment
            if (bind) p["caller-id-bind-on-first-use"] = "yes"
            val id = addWithFallback("/tool/user-manager/user/add", p).done["ret"] ?: name
            if (profile.isNotBlank())
                api.exec("/tool/user-manager/user/create-and-activate-profile", mapOf("numbers" to id, "customer" to customer, "profile" to profile))
        } else {
            val p = linkedMapOf("name" to name, "password" to pass)
            if (comment.isNotBlank()) p["comment"] = comment
            if (bind) p["caller-id-bind-on-first-use"] = "yes"
            addWithFallback("/user-manager/user/add", p)
            if (profile.isNotBlank()) api.exec("/user-manager/user-profile/add", mapOf("user" to name, "profile" to profile))
        }
    }

    /**
     * إضافة كروت كثيرة بسرعة: الأوامر تُرسل دفعات (Pipelining) بدل رحلة ذهاب وإياب لكل كرت.
     * [progress] يُستدعى بعدد الكروت المنتهية. يرمي خطأ إن فشل أي كرت (بعد إضافة الباقي).
     */
    suspend fun addUsers(cards: List<Pair<String, String>>, profile: String, comment: String, bind: Boolean,
                         progress: (Int) -> Unit = {}) {
        if (cards.isEmpty()) return
        val v = version(); val api = Session.api()
        val chunk = 50
        var done = 0
        val errors = ArrayList<String>()
        fun params(name: String, pass: String, withBind: Boolean) = linkedMapOf<String, String>().apply {
            if (v == Ver.V6) { put("customer", customer); put("username", name) } else put("name", name)
            put("password", pass)
            if (comment.isNotBlank()) put("comment", comment)
            if (withBind && bind && !bindUnsupported) put("caller-id-bind-on-first-use", "yes")
        }
        val addPath = if (v == Ver.V6) "/tool/user-manager/user/add" else "/user-manager/user/add"
        for (part in cards.chunked(chunk)) {
            var res = api.batch(part.map { (n, pw) -> RouterOsClient.Cmd(addPath, params(n, pw, true)) }, chunk)
            // إصدار لا يدعم الربط عند أول استخدام: نعيد الفاشل منها بدونه
            if (bind && res.any { it.exceptionOrNull()?.message.orEmpty().contains("unknown parameter") }) {
                bindUnsupported = true
                val retryIdx = res.indices.filter { res[it].exceptionOrNull()?.message.orEmpty().contains("unknown parameter") }
                val again = api.batch(retryIdx.map { i -> RouterOsClient.Cmd(addPath, params(part[i].first, part[i].second, false)) }, chunk)
                res = res.toMutableList().also { l -> retryIdx.forEachIndexed { k, i -> l[i] = again[k] } }
            }
            val ok = part.indices.filter { res[it].isSuccess }
            res.forEachIndexed { i, r -> r.exceptionOrNull()?.let { errors += "${part[i].first}: ${it.message}" } }
            if (profile.isNotBlank() && ok.isNotEmpty()) {
                val links = ok.map { i ->
                    if (v == Ver.V6) RouterOsClient.Cmd("/tool/user-manager/user/create-and-activate-profile",
                        mapOf("numbers" to (res[i].getOrNull()?.done?.get("ret") ?: part[i].first), "customer" to customer, "profile" to profile))
                    else RouterOsClient.Cmd("/user-manager/user-profile/add", mapOf("user" to part[i].first, "profile" to profile))
                }
                api.batch(links, chunk).forEachIndexed { k, r -> r.exceptionOrNull()?.let { errors += "${part[ok[k]].first}: ${it.message}" } }
            }
            done += part.size
            progress(done)
        }
        if (errors.isNotEmpty()) throw RouterOsException("فشل ${errors.size} كرت: " + errors.take(3).joinToString(" | "))
    }

    /** إن لم يدعم الإصدار خيار الربط عند أول استخدام نضيف الكرت بدونه بدل الفشل. */
    var bindUnsupported = false
        private set

    private suspend fun addWithFallback(path: String, p: Map<String, String>): ApiResult = try {
        Session.api().exec(path, p)
    } catch (e: RouterOsException) {
        if (p.containsKey("caller-id-bind-on-first-use") && e.message.orEmpty().contains("unknown parameter")) {
            bindUnsupported = true
            Session.api().exec(path, p - "caller-id-bind-on-first-use")
        } else throw e
    }

    suspend fun setDisabled(id: String, disabled: Boolean) =
        Session.api().exec("${base(version())}/user/${if (disabled) "disable" else "enable"}", mapOf(".id" to id))

    /** حذف مشترك باسمه (بعد إنشائه مباشرة مثلاً). */
    suspend fun removeByName(name: String) {
        val v = version()
        val field = if (v == Ver.V6) "username" else "name"
        Session.api().print("${base(v)}/user", listOf(".id"), listOf("?$field=$name")).mapNotNull { it[".id"] }
            .forEach { Session.api().exec("${base(v)}/user/remove", mapOf(".id" to it)) }
    }

    /**
     * نقل الكروت غير المستخدمة إلى باقة أخرى (يُتجاهل المستخدم منها ومن هو على نفس الباقة).
     * v7: حذف روابط الباقات غير المفعّلة ثم ربط الباقة الجديدة.
     * v6: لا يمكن تغيير باقة مفعّلة، فيُعاد إنشاء الكرت بنفس بياناته ثم تفعيل الباقة الجديدة.
     * يعيد عدد الكروت المنقولة.
     */
    suspend fun moveToProfile(rows: List<UmUserRow>, profile: String, progress: (Int) -> Unit = {}): Int {
        val v = version(); val api = Session.api()
        val targets = rows.filter { it.status == UmStatus.UNUSED && it.profile != profile }
        var done = 0
        if (v == Ver.V7) {
            val links = api.print("/user-manager/user-profile", listOf(".id", "user", "state")).groupBy { it["user"].orEmpty() }
            for (r in targets) {
                links[r.name].orEmpty()
                    .filter { !it["state"].orEmpty().startsWith("running") && it["state"] != "used" }
                    .mapNotNull { it[".id"] }
                    .forEach { api.exec("/user-manager/user-profile/remove", mapOf(".id" to it)) }
                api.exec("/user-manager/user-profile/add", mapOf("user" to r.name, "profile" to profile))
                done++; progress(done)
            }
        } else {
            // الحقول التي تُنقل كما هي إلى الكرت المُعاد إنشاؤه
            val keep = listOf("password", "comment", "caller-id", "caller-id-bind-on-first-use", "shared-users",
                "first-name", "last-name", "email", "phone", "location", "ip-address")
            for (r in targets) {
                val u = api.print("/tool/user-manager/user", null, listOf("?.id=${r.id}")).firstOrNull() ?: continue
                val p = linkedMapOf("customer" to (u["customer"]?.takeIf { it.isNotBlank() } ?: customer), "username" to r.name)
                keep.forEach { k -> u[k]?.takeIf { it.isNotBlank() }?.let { p[k] = it } }
                api.exec("/tool/user-manager/user/remove", mapOf(".id" to r.id))
                val res = try {
                    api.exec("/tool/user-manager/user/add", p)
                } catch (_: RouterOsException) {
                    // إن رُفض أحد الحقول نعيد الإضافة بالحد الأدنى حتى لا يضيع الكرت
                    val min = linkedMapOf("customer" to p["customer"]!!, "username" to r.name, "password" to r.password)
                    if (r.comment.isNotBlank()) min["comment"] = r.comment
                    api.exec("/tool/user-manager/user/add", min)
                }
                val id = res.done["ret"] ?: r.name
                api.exec("/tool/user-manager/user/create-and-activate-profile",
                    mapOf("numbers" to id, "customer" to p["customer"]!!, "profile" to profile))
                if (r.disabled) runCatching { api.exec("/tool/user-manager/user/disable", mapOf(".id" to id)) }
                done++; progress(done)
            }
        }
        return done
    }

    suspend fun removeUser(id: String) = Session.api().exec("${base(version())}/user/remove", mapOf(".id" to id))

    // ─────────── الباقات ───────────

    suspend fun profileNames(): List<String> =
        Session.api().print("${base(version())}/profile", listOf("name")).mapNotNull { it["name"] }

    /** نصوص الكرت لكل باقة: الصلاحية، الوقت، البيانات (للطباعة). */
    data class CardTexts(val valid: String, val time: String, val data: String)

    suspend fun cardTexts(): Map<String, CardTexts> {
        val v = version(); val api = Session.api()
        val limPath = if (v == Ver.V6) "/tool/user-manager/profile/limitation" else "/user-manager/limitation"
        val linkPath = if (v == Ver.V6) "/tool/user-manager/profile/profile-limitation" else "/user-manager/profile-limitation"
        // القراءات الثلاث في رحلة واحدة
        val r = api.batch(listOf(
            RouterOsClient.Cmd("$limPath/print", proplist = listOf("name", "transfer-limit", "uptime-limit")),
            RouterOsClient.Cmd("$linkPath/print", proplist = listOf("profile", "limitation")),
            RouterOsClient.Cmd("${base(v)}/profile/print", proplist = listOf("name", "validity"))
        ))
        val lims = (r[0].getOrNull()?.rows ?: emptyList()).associateBy { it["name"].orEmpty() }
        val links = (r[1].getOrNull()?.rows ?: emptyList()).groupBy { it["profile"].orEmpty() }
        return r[2].getOrThrow().rows.associate { p ->
            val name = p["name"].orEmpty()
            val l = links[name].orEmpty().mapNotNull { lims[it["limitation"]] }
            name to CardTexts(
                durationText(p["validity"]),
                durationText(l.firstNotNullOfOrNull { lim -> lim["uptime-limit"]?.takeIf { durationSecs(it) > 0 } }),
                dataText(dataLimit(l.distinctBy { it["name"] }.map { it["transfer-limit"]?.toLongOrNull() ?: 0L }))
            )
        }
    }

    /** حد البيانات الفعلي = أصغر حد موجب (0 = مفتوح). */
    internal fun dataLimit(values: List<Long>): Long = values.filter { it > 0 }.minOrNull() ?: 0L

    /** مدة RouterOS (30d أو 1d02:00:00 أو unlimited) → ثوانٍ. */
    fun durationSecs(v: String?): Long {
        if (v.isNullOrBlank() || v == "unlimited") return 0
        val base = Fmt.seconds(v.replace(Regex("\\d+:\\d+:\\d+"), ""))
        val m = Regex("(\\d+):(\\d+):(\\d+)").find(v) ?: return base
        return base + m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
    }

    /** 30 يوم / 5 أيام / 12 ساعة / 90 دقيقة */
    fun durationText(v: String?): String {
        val s = durationSecs(v)
        if (s <= 0) return ""
        if (s % 86400 == 0L) {
            val d = s / 86400
            return "$d " + if (d in 3..10) "أيام" else "يوم"
        }
        if (s % 3600 == 0L) {
            val h = s / 3600
            return "$h " + if (h in 3..10) "ساعات" else "ساعة"
        }
        return "${s / 60} دقيقة"
    }

    /** نص حجم البيانات للكرت: 5 جيجا / 1.5 جيجا / 500 ميجا */
    fun dataText(b: Long): String {
        if (b <= 0) return ""
        fun clean(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
        val gib = b / 1_073_741_824.0
        val gb = b / 1_000_000_000.0
        val mib = b / 1_048_576.0
        return when {
            gib >= 1 && (gib * 10) % 1.0 < 1e-6 -> "${clean(gib)} جيجا"
            gb >= 1 && (gb * 10) % 1.0 < 1e-6 -> "${clean(gb)} جيجا"
            gib >= 1 -> "${clean(Math.round(gib * 10) / 10.0)} جيجا"
            else -> "${clean(Math.round(mib).toDouble())} ميجا"
        }
    }

    /** عدد الكروت لكل باقة بقراءة خفيفة (حقل واحد) بدل تحميل كل بيانات الكروت. */
    suspend fun profileCounts(): Map<String, Int> {
        val v = version(); val api = Session.api()
        return if (v == Ver.V6) api.print("/tool/user-manager/user", listOf("actual-profile"))
            .groupingBy { it["actual-profile"].orEmpty() }.eachCount()
        else api.print("/user-manager/user-profile", listOf("user", "profile"))
            .groupBy { it["user"].orEmpty() }.values.mapNotNull { it.lastOrNull()?.get("profile") }
            .groupingBy { it }.eachCount()
    }

    suspend fun packages(): List<UmPackageRow> {
        val v = version(); val api = Session.api()
        val limPath = if (v == Ver.V6) "/tool/user-manager/profile/limitation" else "/user-manager/limitation"
        val linkPath = if (v == Ver.V6) "/tool/user-manager/profile/profile-limitation" else "/user-manager/profile-limitation"
        // الثلاث قراءات في رحلة واحدة
        val r = api.batch(listOf(RouterOsClient.Cmd("$limPath/print"),
            RouterOsClient.Cmd("$linkPath/print", proplist = listOf(".id", "profile", "limitation")),
            RouterOsClient.Cmd("${base(v)}/profile/print")))
        val lims = (r[0].getOrNull()?.rows ?: emptyList()).associateBy { it["name"].orEmpty() }
        val pl = r[1].getOrNull()?.rows ?: emptyList()
        return r[2].getOrThrow().rows.map { p ->
            val name = p["name"].orEmpty()
            val links = pl.filter { it["profile"] == name }
            val l = links.mapNotNull { lims[it["limitation"]] }
            UmPackageRow(p[".id"].orEmpty(), name, p["validity"].orEmpty(), p["price"].orEmpty(),
                dataLimit(l.distinctBy { it["name"] }.map { it["transfer-limit"]?.toLongOrNull() ?: 0L }),
                l.firstNotNullOfOrNull { lim ->
                    val rx = lim["rate-limit-rx"].orEmpty(); val tx = lim["rate-limit-tx"].orEmpty()
                    if ((rx.isBlank() || rx == "0") && (tx.isBlank() || tx == "0")) null else "${fmtRate(rx)} / ${fmtRate(tx)}"
                }.orEmpty(),
                l.mapNotNull { it[".id"] }, links.mapNotNull { it[".id"] },
                l.firstOrNull()?.get("rate-limit-rx").orEmpty(), l.firstOrNull()?.get("rate-limit-tx").orEmpty())
        }
    }

    /** تعديل باقة: المدة والسعر على الـ profile، والبيانات والسرعة على الـ limitation (تُنشأ إن لم توجد). */
    suspend fun updatePackage(p: UmPackageRow, name: String, days: Int, mb: Long, speed: String, price: String) {
        val v = version(); val api = Session.api()
        val profPath = "${base(v)}/profile"
        val limPath = if (v == Ver.V6) "/tool/user-manager/profile/limitation" else "/user-manager/limitation"
        val linkPath = if (v == Ver.V6) "/tool/user-manager/profile/profile-limitation" else "/user-manager/profile-limitation"
        val set = linkedMapOf(".id" to p.id, "name" to name, "name-for-users" to name,
            "validity" to if (days > 0) "${days}d" else if (v == Ver.V6) "0s" else "unlimited",
            "price" to price.ifBlank { "0" })
        api.exec("$profPath/set", set)

        val parts = speed.split("/").map { it.trim() }.filter { it.isNotBlank() }
        val rx = parts.getOrNull(0) ?: "0"
        val tx = parts.getOrNull(1) ?: parts.getOrNull(0) ?: "0"
        val limVals = mapOf("transfer-limit" to (mb * 1048576).toString(), "rate-limit-rx" to rx, "rate-limit-tx" to tx)
        if (p.limitationIds.isNotEmpty()) {
            api.exec("$limPath/set", limVals + (".id" to p.limitationIds.first()))
        } else if (mb > 0 || speed.isNotBlank()) {
            val limName = "lim-$name"
            val l = linkedMapOf("name" to limName) + limVals.filterValues { it != "0" }
            api.exec("$limPath/add", if (v == Ver.V6) l + ("owner" to customer) else l)
            api.exec("$linkPath/add", mapOf("profile" to name, "limitation" to limName))
        }
    }

    /** إضافة باقة = profile + limitation + ربطهما. days=0 بلا مدة، mb=0 مفتوح، speed فارغ = بلا قيود ("1M/4M" = رفع/تنزيل). */
    suspend fun addPackage(name: String, days: Int, mb: Long, speed: String, price: String) {
        val v = version(); val api = Session.api()
        val limName = "lim-$name"
        val needLim = mb > 0 || speed.isNotBlank()
        val l = linkedMapOf("name" to limName)
        if (mb > 0) l["transfer-limit"] = (mb * 1048576).toString()
        if (speed.isNotBlank()) {
            val parts = speed.split("/").map { it.trim() }
            l["rate-limit-rx"] = parts[0]                       // استقبال الراوتر = رفع المشترك
            l["rate-limit-tx"] = parts.getOrElse(1) { parts[0] } // إرسال الراوتر = تنزيل المشترك
        }
        if (v == Ver.V6) {
            val p = linkedMapOf("name" to name, "owner" to customer, "name-for-users" to name, "starts-at" to "logon",
                "validity" to if (days > 0) "${days}d" else "0s")
            if (price.isNotBlank()) p["price"] = price
            api.exec("/tool/user-manager/profile/add", p)
            if (needLim) {
                api.exec("/tool/user-manager/profile/limitation/add", l + ("owner" to customer))
                api.exec("/tool/user-manager/profile/profile-limitation/add", mapOf("profile" to name, "limitation" to limName))
            }
        } else {
            val p = linkedMapOf("name" to name, "name-for-users" to name, "starts-when" to "first-auth",
                "validity" to if (days > 0) "${days}d" else "unlimited")
            if (price.isNotBlank()) p["price"] = price
            api.exec("/user-manager/profile/add", p)
            if (needLim) {
                api.exec("/user-manager/limitation/add", l)
                api.exec("/user-manager/profile-limitation/add", mapOf("profile" to name, "limitation" to limName))
            }
        }
    }

    suspend fun removePackage(p: UmPackageRow) {
        val v = version(); val api = Session.api()
        val limPath = if (v == Ver.V6) "/tool/user-manager/profile/limitation" else "/user-manager/limitation"
        val linkPath = if (v == Ver.V6) "/tool/user-manager/profile/profile-limitation" else "/user-manager/profile-limitation"
        p.linkIds.forEach { api.exec("$linkPath/remove", mapOf(".id" to it)) }
        api.exec("${base(v)}/profile/remove", mapOf(".id" to p.id))
        p.limitationIds.forEach { runCatching { api.exec("$limPath/remove", mapOf(".id" to it)) } }
    }

    fun fmtRate(v: String): String {
        val n = v.toLongOrNull() ?: return v.ifBlank { "∞" }
        if (n == 0L) return "∞"
        return if (n >= 1_000_000) "${n / 1_000_000}M" else if (n >= 1000) "${n / 1000}k" else "$n"
    }
}
