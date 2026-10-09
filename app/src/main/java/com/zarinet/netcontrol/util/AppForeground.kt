package com.zarinet.netcontrol.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** حالة ظهور التطبيق في المقدمة — تُستخدم لإيقاف التحديث الدوري مؤقتاً أثناء الخلفية. */
object AppForeground {
    val visible = MutableStateFlow(true)

    /** ينتظر حتى يعود التطبيق للمقدمة (يعود فوراً إن كان ظاهراً). */
    suspend fun await() {
        if (visible.value) return
        visible.first { it }
    }
}
