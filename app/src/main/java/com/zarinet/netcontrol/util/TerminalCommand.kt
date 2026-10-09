package com.zarinet.netcontrol.util

/**
 * تحويل أمر طرفية RouterOS (CLI) إلى جمل RouterOS API.
 * أمثلة:
 *   /ip address print where interface=ether1
 *   /interface disable [find name=ether2]
 *   /ip/hotspot/user/print   (مسار API مباشر + سطور إضافية)
 */
object TerminalCommand {
    /** كلمات عرض في الطرفية لا معنى لها في الـ API — تُتجاهل. */
    private val DISPLAY_FLAGS = setOf("detail", "terse", "brief", "without-paging", "value-list", "as-value", "show-ids", "proplist")

    /**
     * words: جمل الـ API الجاهزة للإرسال.
     * finds: معاملات من نوع [find ...] يجب حلّها أولاً (اسم المعامل ← استعلام) ثم تمرير المعرّفات.
     * path: المسار الأساسي (بدون الأمر) لتنفيذ find عليه.
     */
    data class Plan(val words: List<String>, val finds: Map<String, List<String>> = emptyMap(), val path: String = "")

    class ParseError(msg: String) : Exception(msg)

    // "or" بين شرطين: يُضاف ?#| بعد الشرط التالي
    private var orPending = false

    fun parse(command: String, extraLines: List<String> = emptyList()): Plan {
        orPending = false
        val cmd = command.trim().removePrefix(":").trim()
        if (cmd.isEmpty()) throw ParseError("اكتب الأمر")
        val extra = extraLines.map { it.trim() }.filter { it.isNotEmpty() }.map { apiWord(it) }
        val tokens = tokenize(cmd)

        // مسار API مباشر مثل /ip/address/print
        if (tokens.size == 1 && tokens[0].trim('/').contains('/')) {
            return Plan(listOf(normalizePath(tokens[0])) + extra, path = normalizePath(tokens[0]).substringBeforeLast('/'))
        }

        // أجزاء المسار حتى أول معامل/شرط
        val segs = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "where" || t.contains('=') || t.startsWith("[") || t.startsWith("\"") || t in DISPLAY_FLAGS || t == "count-only") break
            segs += t.trim('/').split('/').filter { it.isNotEmpty() }
            i++
        }
        if (segs.size < 2) throw ParseError("الأمر غير مكتمل — مثال: /ip address print")
        val full = "/" + segs.joinToString("/")
        val path = full.substringBeforeLast('/')

        val words = mutableListOf(full)
        val finds = linkedMapOf<String, List<String>>()
        val queries = mutableListOf<String>()
        var inWhere = false
        while (i < tokens.size) {
            val t = tokens[i]
            i++
            if (t == "where") { inWhere = true; continue }
            if (inWhere) {
                whereWord(t, queries)?.let { queries += it }
                continue
            }
            if (t in DISPLAY_FLAGS) continue
            when {
                t == "count-only" -> words += "=count-only="
                t.startsWith("[") && t.endsWith("]") -> finds[".id"] = findQuery(t)
                t.contains('=') -> {
                    val k = t.substringBefore('=')
                    val v = unquote(t.substringAfter('='))
                    if (v.startsWith("[") && v.endsWith("]")) finds[k] = findQuery(v)
                    else words += "=$k=$v"
                }
                // رقم أو اسم بدون مفتاح (مثل: disable ether1) — كلمة API كما هي
                else -> words += "=$t="
            }
        }
        // numbers في الطرفية = .id في الـ API
        finds.remove("numbers")?.let { finds[".id"] = it }
        val fixed = words.map { if (it.startsWith("=numbers=")) "=.id=" + it.removePrefix("=numbers=") else it }
        return Plan(fixed + queries + extra, finds, path)
    }

    /** [find name=x] → استعلامات API. */
    private fun findQuery(t: String): List<String> {
        var parts = tokenize(t.removePrefix("[").removeSuffix("]").trim())
        if (parts.firstOrNull() == "find") parts = parts.drop(1)
        val out = mutableListOf<String>()
        parts.filter { it != "where" }.forEach { w -> whereWord(w, out)?.let { out += it } }
        return out
    }

    /** شرط واحد من where → استعلام API (null لكلمات الربط and/or). */
    private fun whereWord(t: String, prev: List<String>): List<String>? {
        if (t == "and") return null
        if (t == "or") { orPending = true; return null }
        val q = when {
            t.startsWith("!") && !t.contains('=') -> listOf("?-" + t.drop(1))            // الخاصية غير موجودة
            t.contains("!=") -> listOf("?" + t.substringBefore("!=") + "=" + unquote(t.substringAfter("!=")), "?#!")
            t.contains('~') -> throw ParseError("الشرط ~ (تعبير نمطي) غير مدعوم في RouterOS API — استخدم = للمطابقة التامة")
            t.contains(">") -> listOf("?>" + t.substringBefore('>') + "=" + unquote(t.substringAfter('>')))
            t.contains("<") -> listOf("?<" + t.substringBefore('<') + "=" + unquote(t.substringAfter('<')))
            t.contains('=') -> listOf("?" + t.substringBefore('=') + "=" + unquote(t.substringAfter('=')))
            else -> listOf("?$t")                                                             // الخاصية موجودة/صحيحة
        }
        if (orPending && prev.isNotEmpty()) {
            orPending = false
            return q + "?#|"
        }
        orPending = false
        return q
    }

    /** سطر إضافي: key=value → =key=value (ما لم يكن جاهزاً بصيغة API). */
    private fun apiWord(l: String): String {
        if (l.startsWith("=") || l.startsWith("?") || l.startsWith(".") || !l.contains('=')) return l
        return "=" + l.substringBefore('=') + "=" + unquote(l.substringAfter('='))
    }

    private fun normalizePath(p: String) = "/" + p.trim().trim('/')

    private fun unquote(v: String): String {
        if (v.length < 2 || !v.startsWith("\"") || !v.endsWith("\"")) return v
        return v.substring(1, v.length - 1).replace("\\\"", "\"")
    }

    /** تقسيم بالمسافات مع احترام "..." و [...]. */
    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var depth = 0
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; sb.append(c) }
                !quoted && c == '[' -> { depth++; sb.append(c) }
                !quoted && c == ']' -> { depth--; sb.append(c) }
                !quoted && depth == 0 && c.isWhitespace() -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
