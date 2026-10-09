package com.zarinet.netcontrol.subscription

import java.net.URLEncoder

/**
 * باقة اشتراك. القائمة الفعلية تُجلب من جدول subscription_plans على الخادم (المدير يضيف ويعدّل ويحذف)،
 * والسعر المعتمد عند التفعيل يُقرأ دائماً من الخادم؛ القيم هنا للعرض فقط.
 */
data class SubscriptionPlan(val code: String, val months: Int, val priceSar: Int, val nameAr: String, val active: Boolean = true) {
    companion object {
        val MONTHLY_1 = SubscriptionPlan("MONTHLY_1", 1, 30, "شهر واحد")
        val MONTHLY_2 = SubscriptionPlan("MONTHLY_2", 2, 50, "شهران")
        val MONTHLY_3 = SubscriptionPlan("MONTHLY_3", 3, 60, "3 أشهر")
        val MONTHLY_6 = SubscriptionPlan("MONTHLY_6", 6, 100, "6 أشهر")
        /** الباقات الافتراضية (تُستخدم إن تعذر جلب القائمة من الخادم). */
        val entries = listOf(MONTHLY_1, MONTHLY_2, MONTHLY_3, MONTHLY_6)
        fun of(code: String?): SubscriptionPlan? = code?.let { c -> PlanCatalog.all.value.firstOrNull { it.code == c } ?: entries.firstOrNull { it.code == c } }
    }
}

/** قائمة الباقات من الخادم (مع نسخة افتراضية بدون اتصال). */
object PlanCatalog {
    private val _all = kotlinx.coroutines.flow.MutableStateFlow<List<SubscriptionPlan>>(emptyList())
    /** كل الباقات (تشمل المخفية — للمدير). */
    val all: kotlinx.coroutines.flow.StateFlow<List<SubscriptionPlan>> = _all

    /** الباقات المعروضة للمستخدم. */
    fun visible(list: List<SubscriptionPlan> = _all.value): List<SubscriptionPlan> =
        list.filter { it.active }.ifEmpty { if (list.isEmpty()) SubscriptionPlan.entries else emptyList() }

    suspend fun refresh(api: SupabaseApi = SupabaseApi()): List<SubscriptionPlan> {
        val raw = com.zarinet.netcontrol.account.AuthService.authorized { t ->
            api.rest("GET", "subscription_plans?select=*&order=months.asc,price_sar.asc", null, t)
        }
        val list = parse(raw)
        _all.value = list
        return list
    }

    fun parse(raw: String): List<SubscriptionPlan> {
        val a = org.json.JSONArray(raw)
        return (0 until a.length()).map { i -> a.getJSONObject(i) }.map { o ->
            SubscriptionPlan(o.getString("code"), o.getInt("months"), o.getInt("price_sar"), o.getString("name_ar"), o.optBoolean("active", true))
        }
    }

    internal fun setForTests(list: List<SubscriptionPlan>) { _all.value = list }
}

object SubscriptionConfig {
    const val TRIAL_DAYS = 7
    const val WHATSAPP_NUMBER = "967778492884"          // +967 778 492 884
    const val WHATSAPP_DISPLAY = "+967778492884"
    /** أقصى مدة يُسمح فيها بالعمل بدون تحقق من الخادم. */
    const val OFFLINE_GRACE_MS = 72L * 3600_000L
    /** إعادة التحقق من الخادم في الخلفية كل 6 ساعات أثناء الاستخدام. */
    const val REVALIDATE_MS = 6L * 3600_000L
    /** تنبيهات قرب الانتهاء (بالأيام). */
    val WARN_DAYS = listOf(7, 3, 1)
}

/** الحالة كما يفهمها التطبيق. */
enum class SubscriptionState { TRIAL_ACTIVE, SUBSCRIPTION_ACTIVE, EXPIRED, SUSPENDED, UNVERIFIED, NOT_CONFIGURED }

/** بيانات الاشتراك كما أعادها الخادم (الأوقات بالميلي ثانية UTC ووقت الخادم هو المرجع). */
data class Subscription(
    val publicId: String,
    val serverStatus: String,          // TRIAL | ACTIVE | EXPIRED | SUSPENDED
    val plan: SubscriptionPlan?,
    val planName: String?,
    val priceSar: Int?,
    val trialEndsAt: Long?,
    val subscriptionStartedAt: Long?,
    val subscriptionEndsAt: Long?,
    val endsAt: Long?,
    val serverTime: Long
) {
    val state: SubscriptionState get() = when (serverStatus) {
        "TRIAL" -> SubscriptionState.TRIAL_ACTIVE
        "ACTIVE" -> SubscriptionState.SUBSCRIPTION_ACTIVE
        "SUSPENDED" -> SubscriptionState.SUSPENDED
        else -> SubscriptionState.EXPIRED
    }
}

/** القرار النهائي الذي تستخدمه الواجهة والحارس. */
data class SubscriptionStatus(
    val state: SubscriptionState,
    val sub: Subscription?,
    val publicId: String?,
    /** وقت الخادم المقدَّر الآن (ليس وقت الهاتف). */
    val estimatedServerNow: Long?,
    val lastVerifiedAt: Long?,
    val offline: Boolean,
    val message: String? = null
) {
    val allowed: Boolean get() = state == SubscriptionState.TRIAL_ACTIVE || state == SubscriptionState.SUBSCRIPTION_ACTIVE
    val msLeft: Long get() = if (sub?.endsAt != null && estimatedServerNow != null) (sub.endsAt - estimatedServerNow).coerceAtLeast(0) else 0
    /** الأيام المتبقية مقرّبة للأعلى (5.2 يوم → 6). */
    val daysLeft: Int get() = ((msLeft + 86_399_999L) / 86_400_000L).toInt()
    /** مستوى التنبيه حسب العتبات 7 / 3 / 1 يوم (null = لا تنبيه). */
    val warnLevel: Int? get() = when {
        !allowed -> null
        // التجربة كلها 7 أيام، فننبه فيها من 3 أيام فقط حتى لا يظهر التنبيه طوال التجربة
        state == SubscriptionState.TRIAL_ACTIVE && daysLeft > 3 -> null
        else -> SubscriptionConfig.WARN_DAYS.sorted().firstOrNull { daysLeft <= it }
    }

    /** نص التنبيه، مثال: «⚠️ اشتراكك سينتهي خلال 3 أيام.» */
    val warning: String? get() {
        warnLevel ?: return null
        val subject = if (state == SubscriptionState.TRIAL_ACTIVE) "التجربة المجانية ستنتهي" else "اشتراكك سينتهي"
        return "⚠️ $subject خلال ${Days.ar(daysLeft)}."
    }
}

class SubscriptionRequiredException(val status: SubscriptionStatus) : Exception(
    when (status.state) {
        SubscriptionState.EXPIRED -> "🔒 انتهى اشتراكك — قم بتجديد الاشتراك للاستمرار"
        SubscriptionState.SUSPENDED -> "⛔ تم إيقاف حسابك — تواصل معنا عبر واتساب"
        SubscriptionState.NOT_CONFIGURED -> "لم يتم إعداد خادم الاشتراكات في هذه النسخة"
        else -> status.message ?: "تعذر التحقق من الاشتراك — تأكد من اتصال الإنترنت"
    }
)

object Days {
    fun ar(n: Int): String = when {
        n == 1 -> "يوم واحد"
        n == 2 -> "يومين"
        n in 3..10 -> "$n أيام"
        else -> "$n يوم"
    }
}

/** رسائل ورابط واتساب (المعرف يُدرج ديناميكياً). */
object WhatsApp {
    fun subscribeMessage(publicId: String, plan: SubscriptionPlan): String =
        "السلام عليكم، أرغب في الاشتراك في تطبيق ميكروتك الشامل.\n" +
            "🆔 معرفي: $publicId\n" +
            "📦 الباقة: ${plan.nameAr}\n" +
            "💰 السعر: ${plan.priceSar} ريال سعودي"

    fun renewMessage(publicId: String): String =
        "السلام عليكم، أرغب في تجديد اشتراكي في تطبيق ميكروتك الشامل.\n" +
            "🆔 معرفي: $publicId"

    fun generalMessage(publicId: String): String =
        "السلام عليكم، أرغب في الاشتراك في تطبيق ميكروتك الشامل.\n🆔 معرفي: $publicId"

    /** رابط wa.me مع ترميز النص بالكامل (UTF-8، والمسافات %20). */
    fun url(text: String, number: String = SubscriptionConfig.WHATSAPP_NUMBER): String =
        "https://wa.me/$number?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
