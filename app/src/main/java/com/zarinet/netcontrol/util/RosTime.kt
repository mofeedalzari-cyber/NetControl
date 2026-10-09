package com.zarinet.netcontrol.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** تحويل تواريخ RouterOS (v6: sep/24/2026 10:00:00 — v7: 2026-09-24 10:00:00) إلى وقت، وصياغة "منذ" و"باقي". */
object RosTime {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(s: String?, now: Long = System.currentTimeMillis()): Long? {
        val v = s?.trim()?.lowercase().orEmpty()
        if (v.isEmpty() || v == "never" || v == "unknown") return null
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.MILLISECOND, 0) }
        fun time(t: String?) {
            val p = t?.split(":")?.mapNotNull { it.toIntOrNull() }
            c.set(Calendar.HOUR_OF_DAY, p?.getOrNull(0) ?: 0); c.set(Calendar.MINUTE, p?.getOrNull(1) ?: 0); c.set(Calendar.SECOND, p?.getOrNull(2) ?: 0)
        }
        val parts = v.split(" ").filter { it.isNotBlank() }
        val d = parts[0]
        try {
            when {
                Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d) -> {
                    val (y, m, dd) = d.split("-").map { it.toInt() }
                    c.set(y, m - 1, dd); time(parts.getOrNull(1))
                }
                Regex("^[a-z]{3}/\\d{1,2}(/\\d{4})?$").matches(d) -> {
                    val p = d.split("/")
                    val m = months.indexOf(p[0]); if (m < 0) return null
                    val y = p.getOrNull(2)?.toInt() ?: c.get(Calendar.YEAR)
                    c.set(y, m, p[1].toInt()); time(parts.getOrNull(1))
                }
                // صيغة m/d/yyyy (مثلاً تاريخ أول دخول المكتوب في التعليق)
                Regex("^\\d{1,2}/\\d{1,2}/\\d{4}$").matches(d) -> {
                    val (m, dd, y) = d.split("/").map { it.toInt() }
                    c.set(y, m - 1, dd); time(parts.getOrNull(1))
                }
                Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(d) -> time(d)   // اليوم
                else -> return null
            }
        } catch (_: Exception) { return null }
        return c.timeInMillis
    }

    /** تاريخ مقروء (yyyy-MM-dd HH:mm) مع علامة اتجاه LTR. */
    fun date(t: Long): String = "‎" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))

    /** منذ 5 يوم / منذ 3 ساعة / منذ 10 دقيقة */
    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val m = ((now - t) / 60000).coerceAtLeast(0)
        return when {
            m < 1 -> "الآن"
            m < 60 -> "منذ $m دقيقة"
            m < 1440 -> "منذ ${m / 60} ساعة"
            else -> "منذ ${m / 1440} يوم"
        }
    }

    /** باقي 17 يوم / باقي 5 ساعة */
    fun left(ms: Long): String {
        val m = (ms / 60000).coerceAtLeast(0)
        return when {
            m >= 1440 -> "باقي ${m / 1440} يوم"
            m >= 60 -> "باقي ${m / 60} ساعة"
            else -> "باقي $m دقيقة"
        }
    }
}
