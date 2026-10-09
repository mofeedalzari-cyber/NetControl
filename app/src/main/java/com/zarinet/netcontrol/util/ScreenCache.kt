package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.data.Session
import java.util.concurrent.ConcurrentHashMap

/**
 * ذاكرة مؤقتة بسيطة لبيانات الشاشات (لكل جهاز على حدة) — تُعرض فوراً عند العودة للشاشة
 * ثم تُحدَّث في الخلفية. المفتاح يُربط تلقائياً بمعرّف الجهاز الحالي.
 */
object ScreenCache {
    private class Entry(val at: Long, val value: Any?)

    private val map = ConcurrentHashMap<String, Entry>()

    private fun k(key: String) = "${Session.device?.id}:$key"

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = map[k(key)]?.value as T?

    /** عمر القيمة المخزّنة بالمللي ثانية (Long.MAX_VALUE إن لم توجد). */
    fun age(key: String): Long = map[k(key)]?.let { System.currentTimeMillis() - it.at } ?: Long.MAX_VALUE

    fun fresh(key: String, maxAgeMs: Long): Boolean = age(key) < maxAgeMs

    fun put(key: String, value: Any?) {
        map[k(key)] = Entry(System.currentTimeMillis(), value)
    }

    fun invalidate(key: String) {
        map.remove(k(key))
    }
}
