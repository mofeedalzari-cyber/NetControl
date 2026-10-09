package com.zarinet.netcontrol.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zarinet.netcontrol.R

/**
 * اللغة: العربية (الأصل) أو الإنجليزية.
 * النصوص العربية في الكود تُترجم عند العرض من قاموس res/raw/i18n_en.tsv (الجملة كاملة أولاً ثم العبارات الأطول فالأقصر).
 * بيانات الراوتر والمستخدم (أسماء، ملاحظات) لا تُترجم إلا إن طابقت عبارة كاملة في القاموس.
 */
object L10n {
    var lang by mutableStateOf("ar")
    val isEn: Boolean get() = lang == "en"

    private var exact: Map<String, String> = emptyMap()
    private var phrases: List<Pair<String, String>> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 2000
    }

    fun init(ctx: Context) {
        lang = runCatching { ctx.getSharedPreferences("mikroadmin_ui", Context.MODE_PRIVATE).getString("lang", "ar") }.getOrNull() ?: "ar"
        runCatching { load(ctx.resources.openRawResource(R.raw.i18n_en).bufferedReader(Charsets.UTF_8).readText()) }
    }

    fun set(ctx: Context, l: String) {
        lang = l
        runCatching { ctx.getSharedPreferences("mikroadmin_ui", Context.MODE_PRIVATE).edit().putString("lang", l).apply() }
    }

    fun load(tsv: String) {
        val rows = tsv.lineSequence().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }.toList()
        exact = rows.toMap()
        phrases = rows.filter { it.first.length >= 2 }.sortedByDescending { it.first.length }
        synchronized(cache) { cache.clear() }
    }

    private fun isAr(c: Char) = c in 'ء'..'ي' || c in '٠'..'ۿ'

    /** ترجمة نص للعرض. */
    fun t(s: String): String {
        // بالعربية: علامة RLM قبل القوس حتى لا ينقلب ترتيب "(...)" بعد نص عربي
        if (!isEn) return if (s.contains(" (") && s.any(::isAr)) s.replace(" (", " \u200f(") else s
        if (s.isEmpty() || s.none(::isAr)) return s
        synchronized(cache) { cache[s]?.let { return it } }
        val out = translate(s)
        synchronized(cache) { cache[s] = out }
        return out
    }

    fun translate(s: String): String {
        exact[s.trim()]?.let { return s.replace(s.trim(), it) }
        var r = s
        for ((ar, en) in phrases) {
            if (r.none(::isAr)) break
            var i = r.indexOf(ar)
            while (i >= 0) {
                val before = if (i > 0) r[i - 1] else ' '
                val after = if (i + ar.length < r.length) r[i + ar.length] else ' '
                if (!isAr(before) && !isAr(after) && before != 'ـ') {
                    r = r.substring(0, i) + en + r.substring(i + ar.length)
                    i = r.indexOf(ar, i + en.length)
                } else i = r.indexOf(ar, i + ar.length)
            }
        }
        return r.replace('،', ',').replace('؟', '?').replace('؛', ';')
    }
}
