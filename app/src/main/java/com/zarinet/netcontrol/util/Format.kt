package com.zarinet.netcontrol.util

import java.util.Locale

object Fmt {
    /** يعزل النص اللاتيني/الرقمي حتى لا ينقلب ترتيبه داخل نص عربي. */
    fun ltr(s: String) = "\u2066$s\u2069"

    private val units = mapOf('w' to "أسبوع", 'd' to "يوم", 'h' to "ساعة", 'm' to "دقيقة", 's' to "ثانية")

    /** "1w4d20h6m5s" أو "00:48:08" → نص عربي. */
    fun duration(v: String?): String {
        if (v.isNullOrBlank()) return "—"
        if (v.contains(':')) {
            // صيغة v7 مثل 1d02:03:04 أو 00:48:08
            val dayPart = Regex("^(\\d+)d").find(v)?.groupValues?.get(1)
            val t = v.substringAfter('d').split(':').mapNotNull { it.toIntOrNull() }
            val sb = StringBuilder()
            if (dayPart != null) sb.append("$dayPart يوم ")
            if (t.size == 3) {
                if (t[0] > 0) sb.append("${t[0]} ساعة ")
                if (t[1] > 0) sb.append("${t[1]} دقيقة ")
                sb.append("${t[2]} ثانية")
            }
            return sb.toString().trim().ifEmpty { v }
        }
        val parts = Regex("(\\d+)([wdhms])").findAll(v).map { "${it.groupValues[1]} ${units[it.groupValues[2][0]]}" }.toList()
        return if (parts.isEmpty()) v else parts.joinToString(" ")
    }

    /** مدة RouterOS → ثوانٍ. */
    fun seconds(v: String?): Long {
        if (v.isNullOrBlank()) return 0
        var total = 0L
        Regex("(\\d+)([wdhms])").findAll(v).forEach {
            val n = it.groupValues[1].toLong()
            total += n * when (it.groupValues[2]) { "w" -> 604800; "d" -> 86400; "h" -> 3600; "m" -> 60; else -> 1 }
        }
        return total
    }

    fun bytes(v: String?): String = bytes(v?.toLongOrNull() ?: 0L)
    fun bytes(b: Long): String = ltr(bytesRaw(b))
    fun bytesRaw(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var x = b.toDouble() / 1024; var i = 0
        while (x >= 1024 && i < u.size - 1) { x /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", x, u[i])
    }

    fun bps(v: Long): String = ltr(when {
        v < 1000 -> "$v bps"
        v < 1_000_000 -> String.format(Locale.US, "%.1f Kbps", v / 1000.0)
        else -> String.format(Locale.US, "%.1f Mbps", v / 1_000_000.0)
    })

    /** مدة مختصرة: تُسقط الثواني عند تجاوز الساعة. */
    fun durationShort(v: String?): String {
        val sec = seconds(v)
        if (sec < 3600) return duration(v)
        val w = sec / 604800; val d = sec % 604800 / 86400; val h = sec % 86400 / 3600; val m = sec % 3600 / 60
        return listOfNotNull(
            w.takeIf { it > 0 }?.let { "$it أسبوع" }, d.takeIf { it > 0 }?.let { "$it يوم" },
            h.takeIf { it > 0 }?.let { "$it ساعة" }, m.takeIf { it > 0 && w == 0L }?.let { "$it دقيقة" }
        ).joinToString(" ")
    }

    fun mbps(v: Long): String = String.format(Locale.US, "%.1f", v / 1_000_000.0)
}
