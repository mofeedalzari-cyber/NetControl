package com.zarinet.netcontrol.subscription

import android.content.Context
import android.util.Log
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * خدمة الاشتراك المركزية — مصدر القرار الوحيد في التطبيق.
 * القرار يعتمد وقت الخادم؛ وقت الهاتف يُستخدم للعرض ولقياس المهلة المحلية فقط.
 */
object SubscriptionService {
    private const val TAG = "Subscription"
    private var repo: SubscriptionRepository? = null
    private var cache: SubscriptionCache? = null
    private var deviceHash: String? = null
    private var app: Context? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastOnlineCheck = 0L   // elapsedRealtime-independent: نستخدمه فقط لجدولة إعادة التحقق

    private val _status = MutableStateFlow<SubscriptionStatus?>(null)
    val status: StateFlow<SubscriptionStatus?> = _status

    fun init(ctx: Context, repository: SubscriptionRepository = SubscriptionRepository()) {
        if (repo != null) return
        val app = ctx.applicationContext
        this.app = app
        repo = repository
        cache = SubscriptionCache(app)
        deviceHash = DeviceIdentity.deviceHash(app)
        _status.value = offlineDecision()
        if (com.zarinet.netcontrol.account.AuthService.session.value != null) {
            scope.launch { runCatching { getSubscriptionStatus(forceRefresh = true) } }
            scope.launch { com.zarinet.netcontrol.account.AuthService.refreshAdmin(); runCatching { PlanCatalog.refresh() } }
        }
    }

    /** المعرف العام للعرض (من آخر رد للخادم). */
    val publicId: String? get() = _status.value?.publicId ?: cache?.publicId()

    /**
     * يجلب الحالة من الخادم (عند الحاجة) ويعيد القرار.
     * عند فشل الاتصال: آخر حالة موقّعة + مهلة محدودة، بدون أي تمديد محلي.
     */
    suspend fun getSubscriptionStatus(forceRefresh: Boolean = false): SubscriptionStatus = mutex.withLock {
        val r = repo ?: return@withLock notConfigured()
        if (!r.configured) return@withLock notConfigured().also { _status.value = it }
        val cur = _status.value
        val fresh = cur != null && !cur.offline && System.currentTimeMillis() - lastOnlineCheck in 0..SubscriptionConfig.REVALIDATE_MS
        if (!forceRefresh && fresh) return@withLock offlineDecision().copy(offline = false).also { _status.value = it }
        val res = try {
            val (sub, raw) = r.fetch(deviceHash!!)
            val now = System.currentTimeMillis()
            cache?.save(sub, raw, now, com.zarinet.netcontrol.account.AuthService.session.value?.userId.orEmpty())
            lastOnlineCheck = now
            SubscriptionStatus(sub.state, sub, sub.publicId, sub.serverTime, now, offline = false)
        } catch (e: Exception) {
            Log.w(TAG, "server check failed: ${e.message}")
            // الحساب مسجّل دخول على جهاز آخر: خروج إجباري ومسح كل البيانات المحلية لهذا الحساب
            if (e is SupabaseApi.ApiError && e.code == SupabaseApi.DEVICE_IN_USE) {
                com.zarinet.netcontrol.account.AuthService.kick(e.message)
                cache?.clear()
                lastOnlineCheck = 0L
                Session.device = null
                Session.disconnect()
                app?.let { c -> runCatching { DeviceStore(c).save(emptyList()) } }
            }
            offlineDecision()
        }
        forgetDecision()
        _status.value = res
        res
    }

    private fun offlineDecision(): SubscriptionStatus {
        if (repo?.configured == false) return notConfigured()
        val uid = com.zarinet.netcontrol.account.AuthService.session.value?.userId
            ?: return SubscriptionStatus(SubscriptionState.UNVERIFIED, null, null, null, null, true, "سجّل الدخول إلى حسابك أولاً")
        return OfflinePolicy.decide(cache?.load(System.currentTimeMillis(), uid), System.currentTimeMillis())
    }

    private fun notConfigured() = SubscriptionStatus(SubscriptionState.NOT_CONFIGURED, null, null, null, null, true,
        "لم يتم إعداد خادم الاشتراكات (SUPABASE_URL / ANON_KEY)")

    /** عند تسجيل الخروج أو تغيير الحساب: مسح الحالة المحلية بالكامل. */
    fun reset() {
        cache?.clear(); lastOnlineCheck = 0L
        forgetDecision()
        _status.value = null
    }

    /** بعد تسجيل الدخول: تحقق فوري من الخادم. */
    fun onLogin() {
        lastOnlineCheck = 0L
        forgetDecision()
        scope.launch { runCatching { getSubscriptionStatus(forceRefresh = true) } }
        scope.launch { com.zarinet.netcontrol.account.AuthService.refreshAdmin(); runCatching { PlanCatalog.refresh() } }
    }

    /** يعيد التحقق في الخلفية إن مضت مدة كافية (بدون انتظار). */
    fun revalidateInBackground() {
        if (System.currentTimeMillis() - lastOnlineCheck !in 0..SubscriptionConfig.REVALIDATE_MS)
            scope.launch { runCatching { getSubscriptionStatus(forceRefresh = true) } }
    }

    /**
     * آخر قرار محسوب ووقته: قراءة النسخة الموقّعة تمر عبر Android Keystore (HMAC) وتكتب على القرص،
     * فلا نعيدها مع كل أمر للراوتر — نعيد حسابها كل [DECISION_TTL_MS] فقط (أو فوراً عند أي تحديث من الخادم).
     */
    @Volatile private var memo: SubscriptionStatus? = null
    @Volatile private var memoAt = 0L
    private const val DECISION_TTL_MS = 15_000L

    private fun forgetDecision() { memo = null; memoAt = 0L }

    /** قرار سريع من الذاكرة/النسخة الموقّعة (يُستدعى قبل كل أمر للراوتر). */
    fun currentDecision(): SubscriptionStatus {
        val now = android.os.SystemClock.elapsedRealtime()
        val m = memo
        if (m != null && now - memoAt in 0..DECISION_TTL_MS) {
            revalidateInBackground()
            return m
        }
        val d = if (repo == null) notConfigured() else offlineDecision().let { off ->
            val cur = _status.value
            // إن كان آخر رد من الخادم حديثاً نبني عليه (مع حساب الوقت المنقضي بنفس السياسة)
            if (cur != null && !cur.offline) off.copy(offline = false) else off
        }
        memo = d; memoAt = now
        _status.value = d
        revalidateInBackground()
        return d
    }
}

/**
 * الحارس الفعلي: يُستدعى داخل Session قبل أي اتصال أو أمر للراوتر،
 * فلا يمكن تجاوزه بفتح شاشة مباشرة أو استدعاء خدمة من مكان آخر.
 */
object SubscriptionGuard {
    /** قابل للاستبدال في الاختبارات فقط. */
    @Volatile internal var decide: () -> SubscriptionStatus = { SubscriptionService.currentDecision() }
    @Volatile internal var decideAsync: suspend () -> SubscriptionStatus = { SubscriptionService.getSubscriptionStatus() }

    /** تحقق سريع قبل كل أمر (بدون انتظار الشبكة). */
    fun requireActiveSubscription() {
        val s = decide()
        if (!s.allowed) throw SubscriptionRequiredException(s)
    }

    /** تحقق كامل عند الاتصال بالراوتر (يحاول الخادم أولاً). */
    suspend fun requireActiveSubscriptionOnline() {
        val s = decideAsync()
        if (!s.allowed) throw SubscriptionRequiredException(s)
    }
}
