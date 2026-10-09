package com.zarinet.netcontrol.util

import com.zarinet.netcontrol.api.RouterOsException
import com.zarinet.netcontrol.data.Session

/** عمليات الكرت الواحد (قائمة الضغط المطوّل) للهوتسبوت ويوزر منجر v6/v7. */
interface CardOps {
    suspend fun renew(u: UmUserRow, profile: String)
    suspend fun reset(u: UmUserRow)
    suspend fun bind(u: UmUserRow)
    suspend fun unbind(u: UmUserRow)
    suspend fun setDisabled(u: UmUserRow, disabled: Boolean)
    suspend fun remove(u: UmUserRow)
    suspend fun profiles(): List<String>
}

object HotspotOps : CardOps {
    private const val P = "/ip/hotspot/user"
    private const val NO_MAC = "00:00:00:00:00:00"

    /** تجديد: تصفير العدادات + حذف مؤقت الصلاحية القديم (يبدأ من جديد عند أول دخول) + تفعيل + الباقة المختارة. */
    override suspend fun renew(u: UmUserRow, profile: String) {
        val api = Session.api()
        api.exec("$P/reset-counters", mapOf(".id" to u.id))
        runCatching {
            api.print("/system/scheduler", listOf(".id"), listOf("?name=exp-${u.name}")).forEach {
                api.exec("/system/scheduler/remove", mapOf(".id" to it[".id"]!!))
            }
        }
        val p = linkedMapOf("disabled" to "no")
        if (profile.isNotBlank()) p["profile"] = profile
        api.exec("$P/set", p + (".id" to u.id))
    }

    override suspend fun reset(u: UmUserRow) { Session.api().exec("$P/reset-counters", mapOf(".id" to u.id)) }

    /**
     * ربط عند أول استخدام (نظام صلاحية الزري): يُثبَّت النظام إن لم يكن مفعّلاً، ثم تُوضع علامة الربط
     * في البريد "<الأيام>@bind.net" مع مسح MAC ليرتبط بأول جهاز يدخل.
     */
    override suspend fun bind(u: UmUserRow) {
        val api = Session.api()
        if (!ZariValidity.isEnabled()) ZariValidity.install()
        val email = api.print(P, listOf("email"), listOf("?.id=${u.id}")).firstOrNull()?.get("email").orEmpty()
        val days = email.substringBefore('@', "0")
        api.exec(
            "$P/set",
            mapOf(".id" to u.id, "email" to "${days.ifBlank { "0" }}@bind.net", "mac-address" to NO_MAC)
        )
    }

    /** فك الربط: يمسح MAC المربوط فيرتبط بأول جهاز يدخل لاحقاً (إن بقيت علامة الربط). */
    override suspend fun unbind(u: UmUserRow) {
        Session.api().exec("$P/set", mapOf(".id" to u.id, "mac-address" to NO_MAC))
    }

    override suspend fun setDisabled(u: UmUserRow, disabled: Boolean) {
        Session.api().exec("$P/${if (disabled) "disable" else "enable"}", mapOf(".id" to u.id))
    }

    override suspend fun remove(u: UmUserRow) { Session.api().exec("$P/remove", mapOf(".id" to u.id)) }

    override suspend fun profiles() = Session.api().print("$P/profile", listOf("name")).mapNotNull { it["name"] }
}

object UserManagerOps : CardOps {
    private suspend fun base() = if (UserManager.isV6()) "/tool/user-manager" else "/user-manager"

    /** تجديد: إضافة الباقة من جديد للمشترك (تبدأ عند أول دخول). */
    override suspend fun renew(u: UmUserRow, profile: String) {
        val api = Session.api()
        val p = profile.ifBlank { u.profile }
        if (p.isBlank()) throw RouterOsException("اختر باقة للتجديد")
        if (UserManager.isV6()) {
            api.exec("/tool/user-manager/user/create-and-activate-profile",
                mapOf("numbers" to u.id, "customer" to UserManager.customerName(), "profile" to p))
        } else {
            api.exec("/user-manager/user-profile/add", mapOf("user" to u.name, "profile" to p))
        }
        if (u.disabled) setDisabled(u, false)
    }

    override suspend fun reset(u: UmUserRow) {
        try {
            Session.api().exec("${base()}/user/reset-counters", mapOf(".id" to u.id))
        } catch (e: RouterOsException) {
            if (e.message.orEmpty().contains("no such command"))
                throw RouterOsException("تصفير العدادات غير مدعوم في يوزر منجر لهذا الإصدار — استخدم تجديد الاشتراك")
            throw e
        }
    }

    override suspend fun bind(u: UmUserRow) {
        Session.api().exec("${base()}/user/set", mapOf(".id" to u.id, "caller-id" to "", "caller-id-bind-on-first-use" to "yes"))
    }

    override suspend fun unbind(u: UmUserRow) {
        Session.api().exec("${base()}/user/set", mapOf(".id" to u.id, "caller-id" to ""))
    }

    override suspend fun setDisabled(u: UmUserRow, disabled: Boolean) = UserManager.setDisabled(u.id, disabled).let { }

    override suspend fun remove(u: UmUserRow) = UserManager.removeUser(u.id).let { }

    override suspend fun profiles() = UserManager.profileNames()
}
