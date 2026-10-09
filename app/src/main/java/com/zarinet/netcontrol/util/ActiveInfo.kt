package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.RouterOsClient
import java.util.Locale

/**
 * معلومات كرت متصل: الباقة، المتبقي من الوقت، المتبقي من البيانات، إجمالي الاستهلاك،
 * وحد السرعة (limit) إن عُرف.
 */
data class CardInfo(
    val profile: String,
    val remainingMs: Long?,
    val leftBytes: Long?,
    val totalUsed: Long? = null,
    val limit: String = "",
    val limitKnown: Boolean = false
)

/** جلب معلومات المتصلين دفعة واحدة (بدل طلب لكل مشترك). */
object ActiveInfo {
    /** استعلام OR لعدة قيم: ?name=a ?name=b ?#| */
    fun orQuery(field: String, values: List<String>): List<String> =
        values.map { "?$field=$it" } + (if (values.size > 1) listOf("?#" + "|".repeat(values.size - 1)) else emptyList())

    /**
     * كروت الهوتسبوت: الباقة، المتبقي من الصلاحية (نظام الزري أو limit-uptime)، والمتبقي من البيانات.
     * sessionBytes: استهلاك الجلسة الحالية (لم يُضف بعد لعدادات المستخدم).
     */
    suspend fun hotspot(
        api: RouterOsClient,
        names: List<String>,
        sessionBytes: Map<String, Long>,
        now: Long = System.currentTimeMillis()
    ): Map<String, CardInfo> {
        val out = HashMap<String, CardInfo>()
        for (chunk in names.distinct().filter { it.isNotBlank() }.chunked(40)) {
            val rows = api.print(
                "/ip/hotspot/user",
                listOf("name", "profile", "comment", "email", "limit-bytes-total", "bytes-in", "bytes-out", "limit-uptime", "uptime"),
                orQuery("name", chunk)
            )
            for (u in rows) {
                val name = u["name"].orEmpty()
                val limit = u["limit-bytes-total"]?.toLongOrNull() ?: 0L
                val used = (u["bytes-in"]?.toLongOrNull() ?: 0L) + (u["bytes-out"]?.toLongOrNull() ?: 0L) + (sessionBytes[name] ?: 0L)
                val upLimit = Fmt.seconds(u["limit-uptime"])
                val remaining = ZariValidity.remainingMs(u["comment"].orEmpty(), u["email"].orEmpty(), now)
                    ?: if (upLimit > 0) ((upLimit - Fmt.seconds(u["uptime"])) * 1000).coerceAtLeast(0) else null
                out[name] = CardInfo(
                    u["profile"].orEmpty(), remaining,
                    if (limit > 0) (limit - used).coerceAtLeast(0) else null, used
                )
            }
        }
        return out
    }

    /** سرعة مختصرة: 2000000 → 2M ، 512000 → 512k */
    fun shortRate(bits: Long): String {
        if (bits <= 0) return "∞"
        if (bits >= 1_000_000 && bits % 1_000_000 == 0L) return "${bits / 1_000_000}M"
        if (bits >= 1_048_576 && bits % 1_048_576 == 0L) return "${bits / 1_048_576}M"
        if (bits >= 1_000_000) return String.format(Locale.US, "%.1fM", bits / 1_000_000.0)
        if (bits >= 1000) return "${bits / 1000}k"
        return bits.toString()
    }

    /** "2M" / "512k" / "1000000" → بت/ثانية. */
    fun parseRate(v: String): Long {
        val s = v.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return 0
        val n = s.trimEnd('k', 'm', 'g').toDoubleOrNull() ?: return 0
        val mul = when {
            s.endsWith("k") -> 1000
            s.endsWith("m") -> 1_000_000
            s.endsWith("g") -> 1_000_000_000
            else -> 1
        }
        return (n * mul).toLong()
    }

    /**
     * حدود السرعة من الطوابير الديناميكية للهوتسبوت (<hotspot-user>).
     * يعيد: (المستخدم ← "↓تنزيل ↑رفع") ومجموعة المستخدمين الذين تم فحصهم بنجاح.
     */
    suspend fun limits(api: RouterOsClient, users: List<String>): Pair<Map<String, String>, Set<String>> {
        val out = HashMap<String, String>()
        val checked = HashSet<String>()
        for (chunk in users.distinct().filter { it.isNotBlank() }.chunked(40)) {
            runCatching {
                val rows = api.print("/queue/simple", listOf("name", "max-limit"), orQuery("name", chunk.map { "<hotspot-$it>" }))
                for (q in rows) {
                    val u = q["name"].orEmpty().removePrefix("<hotspot-").removeSuffix(">")
                    val p = q["max-limit"].orEmpty().split("/")
                    if (p.size == 2) {
                        val up = parseRate(p[0])     // max-limit = رفع/تنزيل
                        val down = parseRate(p[1])
                        if (up <= 0 && down <= 0) continue
                        out[u] = "↓${shortRate(down)} ↑${shortRate(up)}"
                    }
                }
                checked.addAll(chunk)
            }
        }
        return out to checked
    }
}
