package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.subscription.AdminRepository
import com.zarinet.netcontrol.subscription.SubscriptionPlan
import com.zarinet.netcontrol.subscription.SupabaseApi
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** صف في قائمة الإدارة. */
data class AdminRow(
    val email: String?,
    val publicId: String, val status: String, val plan: String?, val planName: String?,
    val trialEnds: Long?, val subStart: Long?, val subEnds: Long?, val createdAt: Long?, val lastSeen: Long?,
    val trialStart: Long? = null,
    /** نهاية الصلاحية الفعلية كما يحسبها الخادم. */
    val endsAt: Long? = null,
    /** الثواني المتبقية (محسوبة على الخادم بوقته). */
    val secondsLeft: Long = 0L,
    /** الجهاز المرتبط بالحساب (جهاز واحد لكل حساب). */
    val deviceName: String? = null,
    val deviceSince: Long? = null,
    val deviceLocked: Boolean = false
) {
    /** تاريخ بداية الفترة الحالية (الاشتراك أو التجربة). */
    val startAt: Long? get() =
        if (status == "ACTIVE" || (status != "TRIAL" && subStart != null)) subStart ?: trialStart else trialStart

    /** تاريخ نهاية الفترة الحالية. */
    val endAt: Long? get() = endsAt ?: (if (status == "TRIAL") trialEnds else subEnds ?: trialEnds)

    companion object {
        fun of(o: JSONObject) = AdminRow(o.optString("email").takeIf { !o.isNull("email") && it.isNotBlank() }, o.getString("public_id"), o.getString("status"),
            o.optString("plan").takeIf { !o.isNull("plan") }, o.optString("plan_name").takeIf { !o.isNull("plan_name") },
            SupabaseApi.ts(o, "trial_ends_at"), SupabaseApi.ts(o, "subscription_started_at"), SupabaseApi.ts(o, "subscription_ends_at"),
            SupabaseApi.ts(o, "created_at"), SupabaseApi.ts(o, "last_seen_at"),
            SupabaseApi.ts(o, "trial_started_at"), SupabaseApi.ts(o, "ends_at"), o.optLong("seconds_left", 0L),
            o.optString("device_name").takeIf { !o.isNull("device_name") && it.isNotBlank() },
            SupabaseApi.ts(o, "device_since"), o.optBoolean("device_locked", false))
    }
}

private fun d(ms: Long?) = ms?.let { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(it)) } ?: "—"
private fun statusAr(s: String) = when (s) { "TRIAL" -> "تجربة"; "ACTIVE" -> "فعال"; "SUSPENDED" -> "موقوف"; else -> "منتهي" }

/** نص الوقت الباقي من عدد الثواني (مثال: «باقي 5 يوم و 3 ساعة»). */
private fun leftText(sec: Long): String = when {
    sec <= 0 -> "انتهى"
    sec < 86_400 -> if (sec >= 3600) "باقي ${sec / 3600} ساعة" else "باقي ${sec / 60} دقيقة"
    else -> {
        val days = sec / 86_400
        val rem = sec % 86_400
        "باقي $days يوم" + (if (rem >= 3600) " و ${rem / 3600} ساعة" else "")
    }
}

/** أقسام تصفية المشتركين حسب الحالة (null = الكل). */
private val ADMIN_SECTIONS: List<Pair<String?, String>> = listOf(
    null to "الكل", "ACTIVE" to "مشتركون", "TRIAL" to "تجريبي", "EXPIRED" to "منتهي", "SUSPENDED" to "موقوف"
)

private fun statusColor(s: String) = when (s) { "TRIAL" -> C.Online; "ACTIVE" -> C.Teal; "SUSPENDED" -> C.Purple; else -> C.Red }

/** إدارة الاشتراكات — تتطلب تسجيل دخول مسؤول (Supabase Auth)؛ الخادم يتحقق من الصلاحية في كل عملية. */
@Composable
fun SubscriptionAdminScreen(onBack: () -> Unit, previewRows: List<AdminRow>? = null, previewPassword: Pair<String, String>? = null,
                            initialTab: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { AdminRepository() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(previewRows ?: emptyList()) }
    var selected by remember { mutableStateOf<AdminRow?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var log by remember { mutableStateOf<List<JSONObject>?>(null) }
    var pwFor by remember { mutableStateOf<String?>(null) }          // بريد المستخدم لتغيير كلمة مروره
    var pwDone by remember { mutableStateOf(previewPassword) }
    var tab by remember { mutableIntStateOf(initialTab) }
    var section by remember { mutableStateOf<String?>(null) }  // تصفية حسب الحالة
    val plans by com.zarinet.netcontrol.subscription.PlanCatalog.all.collectAsState()
    LaunchedEffect(Unit) { if (previewRows == null) runCatching { com.zarinet.netcontrol.subscription.PlanCatalog.refresh() } }

    fun search() {
        busy = true; error = null
        scope.launch {
            try { rows = repo.search(query, 500).map(AdminRow::of) } catch (e: Exception) { error = e.message }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (previewRows == null) search() }
    fun act(msg: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { block(); com.zarinet.netcontrol.ui.components.toast(ctx, msg, Toast.LENGTH_SHORT).show(); rows = repo.search(query, 500).map(AdminRow::of)
                  selected = selected?.let { s -> rows.firstOrNull { it.publicId == s.publicId } }
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("إدارة الاشتراكات", "${rows.size} مشترك • ${plans.count { it.active }} باقة", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث") { search(); scope.launch { runCatching { com.zarinet.netcontrol.subscription.PlanCatalog.refresh() } } }
        }
        SegTabs(listOf("المشتركين", "الباقات"), tab, Modifier.padding(horizontal = D.screen)) { tab = it }
        Spacer(Modifier.height(D.sm))
        if (tab == 1) { PlansAdminTab(repo, plans); return@Column }
        Row(Modifier.padding(horizontal = D.screen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            SearchField(query, { query = it }, "بحث: البريد أو @RG…", Modifier.weight(1f))
            AppButton("بحث", ::search, loading = busy)
        }
        Spacer(Modifier.height(D.sm))
        // ── أقسام الحالة مع العدد ──
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = D.screen),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ADMIN_SECTIONS.forEach { (key, label) ->
                val count = if (key == null) rows.size else rows.count { it.status == key }
                val color = key?.let { statusColor(it) } ?: C.Steel
                val sel = section == key
                Row(
                    Modifier.clip(RoundedCornerShape(D.radiusSmall))
                        .background(if (sel) color else C.Card)
                        .border(1.dp, if (sel) color else color.copy(alpha = 0.45f), RoundedCornerShape(D.radiusSmall))
                        .clickable { section = key }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else C.Text)
                    Spacer(Modifier.width(6.dp))
                    Text("$count", style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else color)
                }
            }
        }
        Spacer(Modifier.height(D.sm))
        val shown = rows.filter { section == null || it.status == section }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)) {
            item { StateView(busy && rows.isEmpty(), error, !busy && error == null && shown.isEmpty(), "لا توجد نتائج", ::search) }
            items(shown, key = { it.publicId }) { r ->
                AdminRowCard(r) { selected = r }
            }
        }
    }

    selected?.let { r ->
        AppDialog(Fmt.ltr(r.publicId), onDismiss = { selected = null }, buttons = {
            AppButton("إغلاق", { selected = null }, kind = BtnKind.Ghost)
        }) {
            KvGrid(listOf(
                "البريد" to (r.email ?: "—"),
                "الحالة" to statusAr(r.status), "الباقة" to (r.planName ?: "—"),
                "نهاية التجربة" to d(r.trialEnds), "بداية الاشتراك" to d(r.subStart),
                "نهاية الاشتراك" to d(r.subEnds), "آخر ظهور" to d(r.lastSeen),
                "الجهاز المسجّل" to if (r.deviceLocked) "${r.deviceName ?: "جهاز"} • منذ ${d(r.deviceSince)}" else "لا يوجد",
            ))
            if (r.deviceLocked) {
                AppButton("📱 فك ربط الجهاز (السماح بالدخول من جهاز آخر)", {
                    confirm = "فك ربط ${r.email ?: r.publicId} من الجهاز ${r.deviceName ?: ""}؟\nسيتمكن من الدخول من جهاز آخر، ويخرج الجهاز الحالي عند أول تحقق." to
                        suspend { repo.releaseDevice(r.publicId); Unit }
                }, Modifier.fillMaxWidth(), BtnKind.Secondary, enabled = !busy)
            }
            Text("تفعيل / تمديد (يُضاف إلى المتبقي إن كان فعالاً)", style = MaterialTheme.typography.titleSmall, color = C.Dim)
            com.zarinet.netcontrol.subscription.PlanCatalog.visible(plans).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    row.forEach { p ->
                        AppButton("${p.nameAr} • ${p.priceSar}", {
                            confirm = "تفعيل ${p.nameAr} (${p.priceSar} ريال) للمعرف ${r.publicId}؟" to suspend { repo.activate(r.publicId, p); Unit }
                        }, Modifier.weight(1f), BtnKind.Secondary, enabled = !busy)
                    }
                }
            }
            r.email?.let { em ->
                AppButton("🔑 تغيير كلمة السر", { pwFor = em }, Modifier.fillMaxWidth(), BtnKind.Warning, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                if (r.status == "SUSPENDED")
                    AppButton("إعادة تفعيل الحساب", { confirm = "إعادة تفعيل الحساب ${r.publicId}؟" to suspend { repo.setSuspended(r.publicId, false); Unit } },
                        Modifier.weight(1f), icon = Icons.Filled.CheckCircle)
                else
                    AppButton("إيقاف الحساب", { confirm = "إيقاف الحساب ${r.publicId}؟" to suspend { repo.setSuspended(r.publicId, true); Unit } },
                        Modifier.weight(1f), BtnKind.Danger, Icons.Filled.Block)
                AppButton("سجل العمليات", {
                    scope.launch { log = runCatching { repo.transactions(r.publicId) }.getOrElse { com.zarinet.netcontrol.ui.components.toast(ctx, it.message, Toast.LENGTH_LONG).show(); null } }
                }, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.History)
            }
            AppButton("🗑 حذف المشترك", {
                confirm = "حذف المشترك ${r.email ?: r.publicId} نهائياً؟\nسيُحذف حسابه وأجهزته المحفوظة، ولن يحصل على تجربة جديدة على نفس الهاتف." to
                    suspend { repo.deleteUser(r.publicId); selected = null; Unit }
            }, Modifier.fillMaxWidth(), BtnKind.Danger, enabled = !busy)
        }
    }
    pwFor?.let { em ->
        var pw by remember(em) { mutableStateOf("") }
        var saving by remember(em) { mutableStateOf(false) }
        var err by remember(em) { mutableStateOf<String?>(null) }
        fun gen() = buildString { val a = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"; val r = java.security.SecureRandom(); repeat(10) { append(a[r.nextInt(a.length)]) } }
        AppDialog("تغيير كلمة السر", onDismiss = { if (!saving) pwFor = null }, buttons = {
            AppButton("إلغاء", { pwFor = null }, kind = BtnKind.Ghost, enabled = !saving)
            AppButton("حفظ كلمة السر", {
                saving = true; err = null
                scope.launch {
                    try { repo.setPassword(em, pw); pwFor = null; pwDone = em to pw }
                    catch (e: Exception) { err = e.message }
                    saving = false
                }
            }, enabled = pw.length >= 6, loading = saving)
        }) {
            Text("الحساب: ${Fmt.ltr(em)}", style = MaterialTheme.typography.bodyMedium, color = C.Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                Field(pw, { pw = it.trim() }, "كلمة السر الجديدة", Modifier.weight(1f), ltr = true)
                AppButton("توليد", { pw = gen() }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Casino)
            }
            if (pw.isNotEmpty() && pw.length < 6) Text("6 أحرف على الأقل", style = MaterialTheme.typography.bodySmall, color = C.Gold)
            Text("سيتم تسجيل خروج المستخدم من كل الأجهزة، ويدخل بكلمة السر الجديدة.", style = MaterialTheme.typography.bodySmall, color = C.Faint)
            err?.let { Text(it, color = C.Red, style = MaterialTheme.typography.bodySmall) }
        }
    }
    pwDone?.let { (em, pw) ->
        val msg = "تم تعيين كلمة مرور جديدة لحسابك في تطبيق ميكروتك الشامل ✅\n📧 البريد: $em\n🔑 كلمة المرور: $pw"
        AppDialog("✅ تم حفظ كلمة السر", onDismiss = { pwDone = null }, buttons = { AppButton("تم", { pwDone = null }, kind = BtnKind.Ghost) }) {
            KvGrid(listOf("البريد" to em, "كلمة السر" to pw))
            AppButton("📱 إرسالها للمستخدم عبر واتساب", {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg) }
                val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess }
                runCatching { ctx.startActivity((if (pkg != null) send.setPackage(pkg) else android.content.Intent.createChooser(send, "إرسال")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.fillMaxWidth())
            AppButton("📋 نسخ", {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("password", msg))
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم النسخ", Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth(), BtnKind.Secondary)
        }
    }
    confirm?.let { (text, block) ->
        ConfirmDialog("تأكيد العملية", text, "تأكيد", danger = text.startsWith("إيقاف") || text.startsWith("حذف"),
            onConfirm = { act("تمت العملية") { block() } }, onDismiss = { confirm = null })
    }
    log?.let { l ->
        AppDialog("سجل العمليات", onDismiss = { log = null }, buttons = { AppButton("إغلاق", { log = null }, kind = BtnKind.Ghost) }) {
            if (l.isEmpty()) Text("لا توجد عمليات", color = C.Dim)
            l.forEach { o ->
                val act = when (o.optString("action")) { "TRIAL_STARTED" -> "بدء التجربة"; "ACTIVATE" -> "تفعيل"; "EXTEND" -> "تمديد"
                    "SUSPEND" -> "إيقاف"; "REACTIVATE" -> "إعادة تفعيل"; "PASSWORD_RESET" -> "تغيير كلمة السر"; "DELETE_USER" -> "حذف المشترك"; else -> o.optString("action") }
                Text("${d(SupabaseApi.ts(o, "created_at"))} • $act" + (SubscriptionPlan.of(o.optString("plan"))?.let { " • ${it.nameAr} ${o.optInt("price_sar")} ر.س" } ?: "") +
                    "\n${d(SupabaseApi.ts(o, "old_expiry"))} ← ${d(SupabaseApi.ts(o, "new_expiry"))}",
                    style = MaterialTheme.typography.bodySmall, color = C.Text)
                HorizontalDivider(color = C.Divider)
            }
        }
    }
}

/** تبويب الباقات: إضافة وتعديل وحذف (السعر المعتمد يُحفظ على الخادم). */
@Composable
private fun PlansAdminTab(repo: AdminRepository, plans: List<SubscriptionPlan>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<SubscriptionPlan?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SubscriptionPlan?>(null) }
    fun reload() = scope.launch { runCatching { com.zarinet.netcontrol.subscription.PlanCatalog.refresh() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.sm)) {
        item {
            AppButton("إضافة باقة", { adding = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
        }
        if (plans.isEmpty()) item { StateView(false, null, true, "لا توجد باقات") }
        items(plans, key = { it.code }) { p ->
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md), border = if (p.active) C.Border else C.Faint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.WorkspacePremium, if (p.active) C.Teal else C.Faint, 38.dp, 20.dp)
                    Spacer(Modifier.width(D.sm))
                    Column(Modifier.weight(1f)) {
                        Text(p.nameAr, style = MaterialTheme.typography.titleMedium, color = C.Text)
                        Text("${p.priceSar} ريال سعودي • ${when { p.months == 1 -> "شهر"; p.months == 2 -> "شهران"; p.months <= 10 -> "${p.months} أشهر"; else -> "${p.months} شهراً" }}", style = MaterialTheme.typography.bodyMedium, color = C.Teal)
                    }
                    Pill(if (p.active) "ظاهرة" else "مخفية", if (p.active) C.Online else C.Faint)
                }
                CardActions(onEdit = { edit = p }, onDelete = { deleting = p })
            }
        }
    }
    if (adding || edit != null) {
        val init = edit
        var name by remember(init) { mutableStateOf(init?.nameAr ?: "") }
        var months by remember(init) { mutableStateOf(init?.months?.toString() ?: "1") }
        var price by remember(init) { mutableStateOf(init?.priceSar?.toString() ?: "") }
        var active by remember(init) { mutableStateOf(init?.active ?: true) }
        val ok = name.isNotBlank() && (months.toIntOrNull() ?: 0) in 1..36 && (price.toIntOrNull() ?: 0) > 0
        FormDialog(if (init == null) "إضافة باقة" else "تعديل ${init.nameAr}", confirmText = "حفظ الباقة", enabled = ok,
            onDismiss = { adding = false; edit = null },
            onConfirm = {
                scope.launch {
                    try {
                        repo.savePlan(init?.code, name.trim(), months.toInt(), price.toInt(), active)
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم حفظ الباقة", Toast.LENGTH_SHORT).show(); adding = false; edit = null; reload()
                    } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                }
            }) {
            Field(name, { name = it.take(40) }, "اسم الباقة", placeholder = "مثال: سنة كاملة")
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                Field(months, { months = it.filter(Char::isDigit).take(2) }, "المدة (أشهر)", Modifier.weight(1f), number = true)
                Field(price, { price = it.filter(Char::isDigit).take(6) }, "السعر (ريال سعودي)", Modifier.weight(1f), number = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ظاهرة للمشتركين", style = MaterialTheme.typography.bodyLarge, color = C.Text, modifier = Modifier.weight(1f))
                Switch(active, { active = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal, checkedThumbColor = C.OnTeal))
            }
            if (init != null) Text("تعديل السعر لا يغيّر اشتراكات فُعّلت سابقاً.", style = MaterialTheme.typography.bodySmall, color = C.Faint)
        }
    }
    deleting?.let { p ->
        ConfirmDialog("حذف الباقة", "حذف الباقة \"${p.nameAr}\"؟\nإن كانت مستخدمة في اشتراكات سابقة ستُخفى فقط حفاظاً على السجل.", "حذف",
            onConfirm = {
                scope.launch {
                    try {
                        val deleted = repo.deletePlan(p.code)
                        com.zarinet.netcontrol.ui.components.toast(ctx, if (deleted) "تم حذف الباقة" else "الباقة مستخدمة سابقاً — تم إخفاؤها", Toast.LENGTH_LONG).show(); reload()
                    } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                    deleting = null
                }
            }, onDismiss = { deleting = null })
    }
}

/** بطاقة مشترك في قائمة الإدارة: البريد والمعرف والحالة، ثم الباقة والوقت الباقي وتاريخا البداية والانتهاء. */
@Composable
fun AdminRowCard(r: AdminRow, onClick: () -> Unit) {
    val t = MaterialTheme.typography
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, border = statusColor(r.status).copy(alpha = 0.45f), padding = PaddingValues(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.email ?: "بدون حساب", style = t.titleSmall, color = if (r.email != null) C.Teal else C.Faint, maxLines = 1)
                Text(Fmt.ltr(r.publicId), style = t.labelSmall, color = C.Dim, maxLines = 1)
            }
            Pill(statusAr(r.status), statusColor(r.status))
        }
        Spacer(Modifier.height(6.dp))
        KvGrid(listOf(
            "الباقة" to (r.planName ?: if (r.status == "TRIAL") "تجربة مجانية" else "—"),
            "الوقت الباقي" to leftText(r.secondsLeft),
            "تاريخ البداية" to d(r.startAt),
            "تاريخ الانتهاء" to d(r.endAt),
        ))
    }
}
