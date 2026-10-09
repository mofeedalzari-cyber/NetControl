package com.zarinet.netcontrol.data

import com.zarinet.netcontrol.api.RouterOsClient
import com.zarinet.netcontrol.api.RouterOsException
import com.zarinet.netcontrol.subscription.SubscriptionGuard
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * الجلسة الحالية (جهاز واحد متصل في كل مرة).
 * كل وصول للراوتر يمر من هنا، لذلك حارس الاشتراك موضوع هنا وليس في الشاشات.
 */
object Session {
    var device: Device? = null
    var client: RouterOsClient? = null

    /** هل الاتصال الحالي عبر النفق السحابي (وليس الشبكة المحلية)؟ */
    var viaCloud: Boolean = false
        private set

    /** مصنع العميل (يُستبدل في الاختبارات). */
    var factory: (host: String, port: Int, user: String, pass: String, timeoutMs: Int) -> RouterOsClient =
        { h, p, u, pw, t -> RouterOsClient(h, p, u, pw, t) }

    suspend fun connect(d: Device) {
        connectLock.withLock {
            connectLocked(d)
            lastFail = null
        }
    }

    /** اتصال واحد في كل مرة — يمنع عدة شاشات من فتح اتصالات متزامنة بنفس الراوتر. */
    private val connectLock = Mutex()
    /** آخر فشل اتصال ووقته — لا نعيد المحاولة فوراً حتى لا تتكرر المهلة في كل طلب. */
    private var lastFailAt = 0L
    private var lastFail: Exception? = null
    private const val RETRY_AFTER_MS = 3000L

    private suspend fun connectLocked(d: Device) = kotlinx.coroutines.coroutineScope {
        // التحقق من الاشتراك (قد يحتاج الخادم) يجري بالتوازي مع فتح الاتصال بالراوتر بدل أن يسبقه
        val guard = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { SubscriptionGuard.requireActiveSubscriptionOnline() } }
        disconnect()
        val c = try {
            openClient(d)
        } catch (e: Throwable) {
            guard.await().exceptionOrNull()?.let { throw it }   // خطأ الاشتراك أولى بالعرض
            throw e
        }
        val g = guard.await().exceptionOrNull()
        if (g != null) { c.close(); throw g }
        client = c; device = d
    }

    private suspend fun openClient(d: Device): RouterOsClient {
        val local = { t: Int -> factory(d.host, d.port, d.user, d.password, t) }
        val cloud = { factory(d.cloudHost, d.cloudPort, d.user, d.password, 10_000) }
        if (!d.hasCloud) {
            val c = local(8000)
            c.connect()
            viaCloud = false
            return c
        }
        // المحلي والسحابي معاً بالتوازي: أول اتصال ينجح يُستخدم (بدل انتظار مهلة المحلي خارج الشبكة)
        val winner = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, RouterOsClient>>()
        val errors = java.util.concurrent.ConcurrentHashMap<Boolean, Throwable>()
        val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        // آخر مرة نجحت السحابة: نبدأ بها؛ وإن كان العنوان المحلي غير قابل للوصول (خارج الشبكة) يفشل سريعاً بمهلة قصيرة
        val localTimeout = if (d.id in lastCloud) 2500 else 5000
        for (useCloud in listOf(false, true)) {
            bg.launch {
                val c = if (useCloud) cloud() else local(localTimeout)
                try {
                    c.connect()
                    if (!winner.complete(useCloud to c)) c.close()   // وصل متأخراً: نغلقه
                } catch (e: Throwable) {
                    errors[useCloud] = e
                    if (errors.size == 2) winner.completeExceptionally(
                        // خطأ غير «تعذر الاتصال» (مثل كلمة المرور) أهم من فشل الوصول
                        errors.values.firstOrNull { it.message?.contains("تعذر الاتصال") != true } ?: errors[true] ?: e)
                }
            }
        }
        val (useCloud, c) = winner.await()
        viaCloud = useCloud
        if (useCloud) lastCloud += d.id else lastCloud -= d.id
        return c
    }

    /** أجهزة نجح آخر اتصال بها عبر السحابة (تُجرَّب السحابة أولاً في المرة القادمة). */
    private val lastCloud: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** يعيد العميل، ويعيد الاتصال تلقائياً إن انقطع. */
    suspend fun api(): RouterOsClient {
        SubscriptionGuard.requireActiveSubscription()
        client?.let { if (it.isConnected) return it }
        val d = device ?: throw IllegalStateException("لا يوجد جهاز محدد")
        return connectLock.withLock {
            // ربما أعاد طلب آخر الاتصال أثناء انتظارنا
            val c = client
            if (c != null && c.isConnected && device?.id == d.id) return@withLock c
            val since = System.currentTimeMillis() - lastFailAt
            val f = lastFail
            if (f != null && since < RETRY_AFTER_MS) throw f
            try {
                connectLocked(d)
                lastFail = null
            } catch (e: Exception) {
                lastFail = e; lastFailAt = System.currentTimeMillis()
                throw e
            }
            client!!
        }
    }

    /** بيانات الجهاز لخدمات جانبية (مثل FTP) — تمر بنفس الحارس. */
    fun requireDevice(): Device {
        SubscriptionGuard.requireActiveSubscription()
        return device ?: throw IllegalStateException("لا يوجد جهاز محدد")
    }

    fun disconnect() {
        client?.close()
        client = null
    }
}
