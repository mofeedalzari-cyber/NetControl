package com.zarinet.netcontrol.subscription

/**
 * آخر حالة تحقق ناجحة من الخادم (تُحفظ محلياً موقّعة).
 * @param verifiedWall وقت الهاتف لحظة التحقق (للقياس فقط، لا للقرار).
 * @param monotonicMs مدة استخدام التطبيق منذ التحقق مقاسة بساعة النظام الرتيبة (elapsedRealtime) — لا تتأثر بتغيير التاريخ.
 * @param maxWallSeen أكبر وقت هاتف شوهد منذ التحقق (إرجاع التاريخ للخلف لا يُنقصه).
 */
data class CachedCheck(
    val sub: Subscription,
    val verifiedWall: Long,
    val monotonicMs: Long,
    val maxWallSeen: Long
)

/**
 * قرار العمل بدون إنترنت — دالة نقية قابلة للاختبار.
 * الزمن المنقضي منذ التحقق = الأكبر بين (فرق ساعة الهاتف، العدّاد الرتيب، أكبر وقت شوهد).
 * لذلك تقديم التاريخ يقرّب الانتهاء، وإرجاعه للخلف لا يوقف العدّ، ولا يوجد أي تمديد محلي.
 */
object OfflinePolicy {
    fun elapsedSinceVerify(c: CachedCheck, wallNow: Long): Long =
        maxOf(wallNow - c.verifiedWall, c.monotonicMs, c.maxWallSeen - c.verifiedWall, 0L)

    fun decide(c: CachedCheck?, wallNow: Long, graceMs: Long = SubscriptionConfig.OFFLINE_GRACE_MS): SubscriptionStatus {
        if (c == null) return SubscriptionStatus(SubscriptionState.UNVERIFIED, null, null, null, null, true,
            "تعذر الاتصال بخادم الاشتراكات — اتصل بالإنترنت مرة واحدة للتحقق")
        val elapsed = elapsedSinceVerify(c, wallNow)
        val est = c.sub.serverTime + elapsed
        val base = SubscriptionStatus(c.sub.state, c.sub, c.sub.publicId, est, c.verifiedWall, true)
        return when {
            c.sub.state == SubscriptionState.SUSPENDED || c.sub.state == SubscriptionState.EXPIRED -> base
            c.sub.endsAt == null || est >= c.sub.endsAt -> base.copy(state = SubscriptionState.EXPIRED)
            elapsed > graceMs -> base.copy(state = SubscriptionState.UNVERIFIED,
                message = "مضى أكثر من 72 ساعة بدون تحقق — اتصل بالإنترنت لتأكيد الاشتراك")
            else -> base
        }
    }
}
