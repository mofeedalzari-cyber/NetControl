package com.zarinet.netcontrol.util

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** كرت واحد كما هو على الراوتر (مختصر للتقرير). */
data class CardRec(val comment: String, val profile: String, val used: Boolean)

/** سجل دفعة أنشأها التطبيق (يُحفظ محلياً لمعرفة الكروت التي حُذفت لاحقاً من الراوتر). */
data class SaleLog(val tag: String, val target: String, val pos: String, val count: Int, val price: Long, val profile: String, val date: Long,
                   val exported: Boolean = false) {
    fun toJson() = JSONObject().apply {
        put("tag", tag); put("target", target); put("pos", pos); put("count", count); put("price", price); put("profile", profile); put("date", date); put("exported", exported)
    }
    companion object {
        fun fromJson(o: JSONObject) = SaleLog(o.optString("tag"), o.optString("target"), o.optString("pos"), o.optInt("count"),
            o.optLong("price"), o.optString("profile"), o.optLong("date"), o.optBoolean("exported"))
        fun listFrom(s: String): List<SaleLog> = try {
            val a = JSONArray(s); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        fun listTo(l: List<SaleLog>) = JSONArray(l.map { it.toJson() }).toString()
    }
}

data class BatchRep(
    val tag: String, val pos: String, val date: Long?, val profile: String,
    val issued: Int, val found: Int, val used: Int, val price: Long
) {
    val missing get() = (issued - found).coerceAtLeast(0)        // حُذفت من الراوتر بعد الطباعة (انتهت) = مباعة
    val sold get() = used + missing
    val remaining get() = (found - used).coerceAtLeast(0)
    val revenue get() = sold * price
    val stockValue get() = remaining * price
}

data class PosRep(val pos: String, val batches: List<BatchRep>) {
    val issued get() = batches.sumOf { it.issued }
    val sold get() = batches.sumOf { it.sold }
    val remaining get() = batches.sumOf { it.remaining }
    val revenue get() = batches.sumOf { it.revenue }
    val stockValue get() = batches.sumOf { it.stockValue }
}

object Sales {
    const val NO_POS = "بدون نقطة بيع"
    private val tagDate = Regex("vc-(\\d{6})-(\\d{4})")

    /** يفكك الملاحظة: vc-260924-1200;valid=1d;pos=علي;price=200 */
    fun parseComment(c0: String): Triple<String, String, Long> {
        // يُزال أولاً ما قبل «<» (وقت أول دخول يكتبه ZariValidity) ثم تُفكك الحقول
        val parts = ZariValidity.body(c0).split(';')
        val kv = parts.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).trim() to p.substring(it + 1).trim() } }.toMap()
        return Triple(parts.first().trim(), kv["pos"].orEmpty(), kv["price"]?.filter(Char::isDigit)?.toLongOrNull() ?: 0L)
    }

    fun dateOfTag(tag: String): Long? {
        val m = tagDate.find(tag) ?: return null
        return try { SimpleDateFormat("yyMMddHHmm", Locale.US).parse(m.groupValues[1] + m.groupValues[2])?.time } catch (_: Exception) { null }
    }

    /** الرقم الأول في نص السعر: "200 ريال" → 200 */
    fun priceOf(text: String): Long = Regex("\\d+").find(text.replace(",", ""))?.value?.toLongOrNull() ?: 0L

    /**
     * يبني التقرير.
     * @param profilePrices سعر احتياطي لكل باقة (من يوزر منجر أو يدخله المستخدم) للدفعات التي لا تحمل سعراً
     * @param since أقدم تاريخ إصدار يُحتسب (null = الكل)
     */
    fun report(cards: List<CardRec>, logs: List<SaleLog>, profilePrices: Map<String, Long>, since: Long?): List<PosRep> {
        val logByTag = logs.associateBy { it.tag }
        data class Acc(var pos: String = "", var profile: String = "", var price: Long = 0, var found: Int = 0, var used: Int = 0)
        val acc = LinkedHashMap<String, Acc>()
        for (c in cards) {
            if (c.comment.isBlank()) continue
            val (tag, pos, price) = parseComment(c.comment)
            if (tag.isBlank()) continue
            val a = acc.getOrPut(tag) { Acc() }
            if (a.pos.isBlank()) a.pos = pos
            if (a.profile.isBlank()) a.profile = c.profile
            if (price > a.price) a.price = price
            a.found++
            if (c.used) a.used++
        }
        // دفعات مسجلة محلياً لم يبق منها أي كرت على الراوتر
        logs.forEach { l -> if (l.tag !in acc) acc[l.tag] = Acc(l.pos, l.profile, l.price) }

        val batches = acc.map { (tag, a) ->
            val log = logByTag[tag]
            val pos = a.pos.ifBlank { log?.pos.orEmpty() }.ifBlank { NO_POS }
            val profile = a.profile.ifBlank { log?.profile.orEmpty() }
            val price = a.price.takeIf { it > 0 } ?: log?.price?.takeIf { it > 0 } ?: profilePrices[profile] ?: 0L
            // دفعة صُدّرت كسكربت ولم تُضف للراوتر بعد: لا تُحتسب كمباعة
            val issued = if (log != null && log.exported && a.found == 0) 0 else maxOf(log?.count ?: 0, a.found)
            BatchRep(tag, pos, log?.date ?: dateOfTag(tag), profile, issued, a.found, a.used, price)
        }.filter { it.issued > 0 }.filter { b -> since == null || (b.date != null && b.date >= since) }
        // الدفعات بلا نقطة بيع ولا سجل ولا نمط vc- (مثل ملاحظات عادية) تُستبعد من التقرير
            .filter { b -> b.pos != NO_POS || b.tag.startsWith("vc-") || logByTag.containsKey(b.tag) }

        return batches.groupBy { it.pos }
            .map { (pos, l) -> PosRep(pos, l.sortedByDescending { it.date ?: 0 }) }
            .sortedWith(compareByDescending<PosRep> { it.revenue }.thenByDescending { it.sold })
    }

    fun shareText(title: String, period: String, reps: List<PosRep>): String = buildString {
        append("📊 ").append(title).append(" — ").append(period).append('\n')
        append("━━━━━━━━━━━━\n")
        reps.forEach { r ->
            append("📍 ").append(r.pos).append('\n')
            append("   مُصدَر: ").append(r.issued).append(" | مباع: ").append(r.sold).append(" | متبقي: ").append(r.remaining).append('\n')
            append("   المبيعات: ").append(money(r.revenue)).append('\n')
        }
        append("━━━━━━━━━━━━\n")
        append("الإجمالي: مباع ").append(reps.sumOf { it.sold }).append(" كرت — ").append(money(reps.sumOf { it.revenue })).append('\n')
        append("قيمة المتبقي: ").append(money(reps.sumOf { it.stockValue })).append('\n')
        append("— ميكروتك الشامل")
    }

    fun money(v: Long): String = String.format(Locale.US, "%,d", v)
}
