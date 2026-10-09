package com.zarinet.netcontrol.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.subscription.*
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** فتح واتساب على رقم الاشتراك برسالة جاهزة (المعرف مدرج ديناميكياً). */
fun openWhatsApp(ctx: Context, text: String) {
    val uri = Uri.parse(WhatsApp.url(text))
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess }
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { if (pkg != null) setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { ctx.startActivity(intent) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { com.zarinet.netcontrol.ui.components.toast(ctx, "تعذر فتح واتساب", Toast.LENGTH_LONG).show() }
    }
}

fun copyId(ctx: Context, id: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ميكروتك الشامل ID", id))
    com.zarinet.netcontrol.ui.components.toast(ctx, "تم نسخ المعرف", Toast.LENGTH_SHORT).show()
}

private fun fmtDate(ms: Long?): String = ms?.let { SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(it)) } ?: "—"

/** شاشة «الاشتراك في ميكروتك الشامل» — تعرض الحالة والمعرف والباقات، وتصبح شاشة القفل عند الانتهاء. */
@Composable
fun SubscriptionScreen(onBack: () -> Unit, onAdmin: () -> Unit, preview: SubscriptionStatus? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by SubscriptionService.status.collectAsState()
    val st = preview ?: live
    var refreshing by remember { mutableStateOf(false) }
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (preview == null) isAdmin = AdminRepository().isAdmin() }
    fun refresh() {
        refreshing = true
        scope.launch { runCatching { SubscriptionService.getSubscriptionStatus(forceRefresh = true) }; refreshing = false }
    }
    LaunchedEffect(Unit) { if (preview == null) refresh() }

    Column(Modifier.fillMaxSize()) {
        TopBar("الاشتراك في ميكروتك الشامل", "التجربة • الباقات • التجديد", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث") { refresh() }
        }
        SubscriptionContent(st, refreshing, if (isAdmin) onAdmin else null,
            onCopy = { copyId(ctx, it) },
            onWhatsApp = { openWhatsApp(ctx, it) })
    }
}

@Composable
fun SubscriptionContent(
    st: SubscriptionStatus?, loading: Boolean, onAdmin: (() -> Unit)?,
    onCopy: (String) -> Unit, onWhatsApp: (String) -> Unit
) {
    val t = MaterialTheme.typography
    val id = st?.publicId ?: SubscriptionService.publicId
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md)
    ) {
        if (st == null || (loading && st.sub == null)) {
            StateView(true, null, false); return@Column
        }
        // ── الحالة ──
        when (st.state) {
            SubscriptionState.EXPIRED -> LockCard("🔒 انتهى اشتراكك",
                if (st.sub?.subscriptionEndsAt == null) "انتهت التجربة المجانية لتطبيق ميكروتك الشامل." else "انتهت صلاحية اشتراك ميكروتك الشامل.",
                "قم بتجديد الاشتراك للاستمرار في استخدام الخدمات.", C.Red, id,
                "📱 تجديد الاشتراك عبر واتساب") { id?.let { onWhatsApp(WhatsApp.renewMessage(it)) } }
            SubscriptionState.SUSPENDED -> LockCard("⛔ تم إيقاف الحساب", "حسابك في ميكروتك الشامل موقوف حالياً.",
                "تواصل معنا عبر واتساب لإعادة التفعيل.", C.Red, id, "📱 التواصل عبر واتساب") { id?.let { onWhatsApp(WhatsApp.generalMessage(it)) } }
            SubscriptionState.UNVERIFIED, SubscriptionState.NOT_CONFIGURED -> LockCard("⚠️ تعذر التحقق من الاشتراك",
                st.message ?: "اتصل بالإنترنت للتحقق من حالة اشتراكك.", "لا يمكن استخدام الخدمات حتى يتم التحقق من الخادم.", C.Gold, id,
                null) {}
            else -> StatusCard(st)
        }
        st.warning?.let { WarningBar(it, st.daysLeft) { id?.let { i -> onWhatsApp(WhatsApp.renewMessage(i)) } } }

        // ── المعرف ──
        if (id != null) IdCard(id, onCopy = { onCopy(id) }, onWhatsApp = { onWhatsApp(WhatsApp.generalMessage(id)) })

        // ── الباقات ──
        SectionTitle("باقات الاشتراك")
        val catalog by PlanCatalog.all.collectAsState()
        val shown = PlanCatalog.visible(catalog)
        val maxMonths = shown.maxOfOrNull { it.months } ?: 0
        shown.forEach { p ->
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md),
                border = if (st.sub?.plan?.code == p.code && st.state == SubscriptionState.SUBSCRIPTION_ACTIVE) C.Teal else C.Border) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.WorkspacePremium, if (p.months == maxMonths && shown.size > 1) C.Gold else C.Teal, 40.dp, 22.dp)
                    Spacer(Modifier.width(D.sm))
                    Column(Modifier.weight(1f)) {
                        Text(p.nameAr, style = t.titleMedium, color = C.Text)
                        Text("${p.priceSar} ريال سعودي", style = t.bodyMedium, color = C.Teal)
                        val perMonth = p.priceSar / p.months
                        if (p.months > 1) Text("≈ $perMonth ريال / شهر", style = t.bodySmall, color = C.Faint)
                    }
                    AppButton("اشترك الآن", { id?.let { onWhatsApp(WhatsApp.subscribeMessage(it, p)) } }, enabled = id != null)
                }
            }
        }
        Text("التفعيل يتم يدوياً بعد التواصل عبر واتساب ${Fmt.ltr(SubscriptionConfig.WHATSAPP_DISPLAY)}. إذا كان لديك اشتراك فعال تُضاف المدة الجديدة إلى المتبقي.",
            style = t.bodySmall, color = C.Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        st.lastVerifiedAt?.let {
            Text((if (st.offline) "آخر تحقق من الخادم: " else "تم التحقق من الخادم: ") + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(it)),
                style = t.labelSmall, color = C.Faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (onAdmin != null) AppButton("إدارة الاشتراكات", onAdmin, Modifier.fillMaxWidth(), BtnKind.Secondary, Icons.Filled.AdminPanelSettings)
    }
}

@Composable
private fun StatusCard(st: SubscriptionStatus) {
    val t = MaterialTheme.typography
    val trial = st.state == SubscriptionState.TRIAL_ACTIVE
    val color = if (trial) C.Online else C.Teal
    AppCard(Modifier.fillMaxWidth(), border = color.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (trial) "🟢 التجربة المجانية" else "✅ اشتراك فعال", style = t.titleLarge, color = C.Text, modifier = Modifier.weight(1f))
            if (st.offline) Pill("بدون اتصال", C.Gold)
        }
        Spacer(Modifier.height(D.sm))
        val lifetime = !trial && st.daysLeft > 10_000
        Text(if (lifetime) "♾️ اشتراك دائم (حساب المدير)" else (if (trial) "متبقي على التجربة: " else "متبقي على الاشتراك: ") + Days.ar(st.daysLeft),
            style = t.titleMedium, color = color)
        Spacer(Modifier.height(D.sm))
        KvGrid(listOfNotNull(
            "الحالة" to if (trial) "تجربة مجانية" else "مشترك",
            "الباقة" to (st.sub?.planName ?: if (trial) "تجربة ${SubscriptionConfig.TRIAL_DAYS} أيام" else if (lifetime) "دائم" else "—"),
            "تاريخ الانتهاء" to if (lifetime) "بلا انتهاء" else fmtDate(st.sub?.endsAt),
            if (!trial) "بداية الاشتراك" to fmtDate(st.sub?.subscriptionStartedAt) else "نهاية التجربة" to fmtDate(st.sub?.trialEndsAt),
        ))
    }
}

@Composable
private fun LockCard(title: String, line1: String, line2: String, color: Color, id: String?, button: String?, onButton: () -> Unit) {
    val t = MaterialTheme.typography
    AppCard(Modifier.fillMaxWidth(), border = color.copy(alpha = 0.7f)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(D.sm)) {
            IconBadge(Icons.Filled.Lock, color, 56.dp, 30.dp, circle = true)
            Text(title, style = t.titleLarge, color = C.Text)
            Text(line1, style = t.bodyLarge, color = C.Dim, textAlign = TextAlign.Center)
            Text(line2, style = t.bodyMedium, color = C.Dim, textAlign = TextAlign.Center)
            if (button != null) AppButton(button, onButton, Modifier.fillMaxWidth().height(46.dp))
        }
    }
}

@Composable
fun IdCard(id: String, onCopy: () -> Unit, onWhatsApp: () -> Unit) {
    val t = MaterialTheme.typography
    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Badge, null, tint = C.Teal, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(D.sm))
            Column(Modifier.weight(1f)) {
                Text("معرف ميكروتك الشامل", style = t.titleSmall, color = C.Text)
                Text("🔒 معرفك مخفي — انسخه أو أرسله عبر واتساب", style = t.bodySmall, color = C.Dim)
            }
        }
        Spacer(Modifier.height(D.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            AppButton("📋 نسخ المعرف", onCopy, Modifier.weight(1f), BtnKind.Secondary)
            AppButton("📱 الاشتراك عبر واتساب", onWhatsApp, Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun WarningBar(text: String, days: Int, onRenew: () -> Unit) {
    val color = when { days <= 1 -> C.Red; days <= 3 -> C.Gold; else -> C.Cyan }
    AppCard(Modifier.fillMaxWidth(), border = color, background = color.copy(alpha = 0.10f), padding = PaddingValues(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = C.Text, modifier = Modifier.weight(1f))
            AppButton("تجديد الاشتراك", onRenew, kind = BtnKind.Warning)
        }
    }
}

/** شريط صغير يظهر في الشاشة الرئيسية وشاشة الأجهزة: تنبيه قرب الانتهاء أو القفل. */
@Composable
fun SubscriptionBanner(onOpen: () -> Unit, compact: Boolean = false, preview: SubscriptionStatus? = null, previewAdmin: Boolean? = null) {
    val st by SubscriptionService.status.collectAsState()
    val adminLive by com.zarinet.netcontrol.account.AuthService.isAdmin.collectAsState()
    if (previewAdmin ?: adminLive) {
        // المدير: بدلاً من صفحة الاشتراك يظهر مدخل «إدارة الاشتراكات»
        AppCard(Modifier.fillMaxWidth(), onClick = onOpen, border = C.Teal.copy(alpha = 0.7f), background = C.Teal.copy(alpha = 0.10f),
            padding = PaddingValues(horizontal = D.md, vertical = D.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AdminPanelSettings, null, tint = C.Teal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(D.sm))
                Text("🛠 إدارة الاشتراكات — المشتركين والباقات", style = MaterialTheme.typography.bodyMedium, color = C.Text, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronLeft, null, tint = C.Dim)
            }
        }
        return
    }
    val s = preview ?: st ?: return
    val t = MaterialTheme.typography
    val (text, color) = when {
        s.state == SubscriptionState.EXPIRED -> "🔒 انتهى اشتراكك — اضغط للتجديد" to C.Red
        s.state == SubscriptionState.SUSPENDED -> "⛔ الحساب موقوف — اضغط للتفاصيل" to C.Red
        s.state == SubscriptionState.UNVERIFIED -> "⚠️ تعذر التحقق من الاشتراك — اتصل بالإنترنت" to C.Gold
        s.state == SubscriptionState.NOT_CONFIGURED -> "⚠️ خادم الاشتراكات غير مُعد" to C.Gold
        s.warning != null -> s.warning!! to (if (s.daysLeft <= 1) C.Red else if (s.daysLeft <= 3) C.Gold else C.Cyan)
        compact -> return
        s.state == SubscriptionState.TRIAL_ACTIVE -> "🟢 التجربة المجانية — متبقي ${Days.ar(s.daysLeft)}" to C.Online
        s.daysLeft > 10_000 -> "♾️ اشتراك دائم" to C.Teal
        else -> "✅ اشتراك فعال — متبقي ${Days.ar(s.daysLeft)}" to C.Teal
    }
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, border = color.copy(alpha = 0.7f), background = color.copy(alpha = 0.10f),
        padding = PaddingValues(horizontal = D.md, vertical = D.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = t.bodyMedium, color = C.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronLeft, null, tint = C.Dim)
        }
    }
}
