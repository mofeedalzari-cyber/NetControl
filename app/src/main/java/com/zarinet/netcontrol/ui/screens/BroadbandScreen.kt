package com.zarinet.netcontrol.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.AppForeground
import com.zarinet.netcontrol.util.Broadband
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.PppAccount
import com.zarinet.netcontrol.util.PppFilter
import com.zarinet.netcontrol.util.PppSettings
import com.zarinet.netcontrol.util.RosTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ─────────────────────────── الإعدادات المحلية ───────────────────────────

private fun loadSettings(ctx: Context): PppSettings {
    val p = ctx.getSharedPreferences("broadband", Context.MODE_PRIVATE)
    return PppSettings(
        autoSpeed = p.getBoolean("autoSpeed", true),
        gbCounter = p.getBoolean("gb", false),
        onExpire = p.getString("onExpire", "disable") ?: "disable"
    )
}

private fun saveSettings(ctx: Context, s: PppSettings) {
    ctx.getSharedPreferences("broadband", Context.MODE_PRIVATE).edit()
        .putBoolean("autoSpeed", s.autoSpeed)
        .putBoolean("gb", s.gbCounter)
        .putString("onExpire", s.onExpire)
        .apply()
}

/** ترتيب قائمة الحسابات. */
private enum class PppSort(val label: String) {
    STATUS("الحالة"),
    NAME("الاسم"),
    PROFILE("الباقة"),
    UPTIME("المدة")
}

// كاش الباقات للجهاز الحالي حتى لا تظهر فارغة عند العودة للشاشة
private var pppProfilesCache: List<Map<String, String>> = emptyList()
private var pppProfilesDev: String? = null

// ─────────────────────────── الشاشة ───────────────────────────

@Composable
fun BroadbandScreen(onBack: () -> Unit, initialTab: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab) }
    var settings by remember { mutableStateOf(runCatching { loadSettings(ctx) }.getOrDefault(PppSettings())) }
    var accounts by remember { mutableStateOf(Broadband.cached()) }
    var profiles by remember {
        mutableStateOf(pppProfilesCache.takeIf { pppProfilesDev == Session.device?.id } ?: emptyList())
    }
    var loading by remember { mutableStateOf(accounts.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    suspend fun reload(full: Boolean) {
        if (full) Session.client?.invalidateCache()
        try {
            accounts = Broadband.accounts(settings.autoSpeed, full)
            if (full || profiles.isEmpty()) {
                profiles = Broadband.profiles()
                pppProfilesCache = profiles
                pppProfilesDev = Session.device?.id
            }
            error = null
        } catch (e: Exception) {
            if (accounts.isEmpty()) error = e.message ?: "خطأ"
        }
        loading = false
    }

    fun act(msg: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                toast(ctx, msg, Toast.LENGTH_SHORT).show()
                Broadband.invalidate()
                refreshKey++
            } catch (e: Exception) {
                toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(refreshKey, settings.autoSpeed) {
        reload(refreshKey > 0)
        if (settings.autoSpeed) {
            while (true) {
                delay(5000)
                AppForeground.await()
                reload(false)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("البرودباند", "حسابات PPPoE", onBack = onBack) {
            BarAction(Icons.Filled.Settings, "إعدادات PPPoE", C.Dim) { showSettings = true }
            BarAction(Icons.Filled.Refresh, "تحديث") { refreshKey++ }
        }
        SegTabs(listOf("المستخدمون", "الباقات"), tab, Modifier.padding(horizontal = D.screen)) { tab = it }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                PppUsersTab(
                    accounts = accounts,
                    profileNames = profiles.mapNotNull { it["name"] },
                    loading = loading,
                    error = error,
                    onRefresh = { refreshKey++ },
                    limits = profiles.associate { (it["name"] ?: "") to (it["rate-limit"] ?: "") },
                    act = ::act
                )
            } else {
                PppProfilesTab(
                    profiles = profiles,
                    accounts = accounts,
                    loading = loading,
                    error = error,
                    onRefresh = { refreshKey++ },
                    act = ::act
                )
            }
        }
    }

    if (showSettings) {
        PppSettingsDialog(settings, onDismiss = { showSettings = false }) { s ->
            showSettings = false
            settings = s
            runCatching { saveSettings(ctx, s) }
            act("تم حفظ الإعدادات على الراوتر") { Broadband.apply(s) }
        }
    }
}

// ─────────────────────────── تبويب المستخدمين ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PppUsersTab(
    accounts: List<PppAccount>,
    profileNames: List<String>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    limits: Map<String, String> = emptyMap(),
    act: (String, suspend () -> Unit) -> Unit
) {
    val ty = MaterialTheme.typography
    var filter by remember { mutableStateOf(PppFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(PppSort.STATUS) }
    var asc by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<PppAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PppAccount?>(null) }
    var whatsapp by remember { mutableStateOf<List<PppAccount>?>(null) }
    var confirmDel by remember { mutableStateOf<List<PppAccount>?>(null) }

    val now = System.currentTimeMillis()
    val counts = remember(accounts) {
        PppFilter.entries.associateWith { f -> accounts.count { f.match(it, now) } }
    }
    val rows = remember(filter, accounts, query, sort, asc) {
        val list = accounts.filter { a ->
            filter.match(a, now) && (query.isBlank() ||
                a.name.contains(query, true) ||
                a.phone.contains(query) ||
                a.note.contains(query, true) ||
                a.profile.contains(query, true))
        }
        val cmp: Comparator<PppAccount> = when (sort) {
            PppSort.STATUS -> compareBy<PppAccount> { if (it.online) 0 else if (it.disabled) 2 else 1 }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            PppSort.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
            PppSort.PROFILE -> compareBy { it.profile }
            PppSort.UPTIME -> compareBy { Fmt.seconds(it.uptime) }
        }
        list.sortedWith(if (asc) cmp else cmp.reversed())
    }
    val selRows = rows.filter { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        // شرائح الفلترة مع العدد
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(D.screen, 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PppFilter.entries.forEach { f ->
                val sel = f == filter
                val color = when (f) {
                    PppFilter.ONLINE -> C.Online
                    PppFilter.SLEEP -> C.Red
                    PppFilter.DISABLED -> C.Faint
                    PppFilter.UNUSED, PppFilter.PAID -> C.Gold
                    PppFilter.EXPIRED, PppFilter.TOMORROW -> Color(0xFFE0457B)
                    else -> C.Teal
                }
                Row(
                    Modifier.clip(RoundedCornerShape(D.radiusSmall))
                        .background(if (sel) color else C.Card)
                        .border(1.dp, if (sel) color else color.copy(alpha = 0.45f), RoundedCornerShape(D.radiusSmall))
                        .clickable { filter = f }
                        .padding(10.dp, 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(f.label, color = if (sel) Color.White else C.Text, maxLines = 1, style = ty.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${counts[f] ?: 0}", color = if (sel) Color.White else color,
                        style = ty.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // البحث + إضافة
        Row(
            Modifier.padding(D.screen, 4.dp),
            horizontalArrangement = Arrangement.spacedBy(D.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactSearch(query, { query = it }, "بحث: الاسم، الهاتف...", Modifier.weight(1f))
            AppButton("إضافة", { adding = true }, Modifier.height(44.dp), icon = Icons.Filled.Add)
        }

        // تحديد الكل + الترتيب
        Row(
            Modifier.fillMaxWidth().padding(horizontal = D.screen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSel = rows.isNotEmpty() && rows.all { it.id in selected }
            Checkbox(
                allSel,
                { v -> selected = if (v) selected + rows.map { it.id } else selected - rows.map { it.id }.toSet() },
                colors = CheckboxDefaults.colors(checkedColor = C.Teal, uncheckedColor = C.Dim, checkmarkColor = C.OnTeal)
            )
            Text("ترتيب:", color = C.Dim, style = ty.labelMedium)
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PppSort.entries.forEach { s ->
                    val sel = sort == s
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) C.Purple.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                if (sel) asc = !asc
                                else {
                                    sort = s
                                    asc = true
                                }
                            }
                            .padding(10.dp, 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.label, color = if (sel) C.Purple else C.Dim, maxLines = 1, style = ty.labelMedium)
                        if (sel) Icon(
                            if (asc) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, null,
                            Modifier.size(16.dp), tint = C.Purple
                        )
                    }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(D.screen, 2.dp, D.screen, D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item {
                StateView(
                    loading = loading && accounts.isEmpty(),
                    error = error,
                    empty = !loading && error == null && rows.isEmpty(),
                    emptyText = if (accounts.isEmpty()) "لا توجد حسابات برودباند — اضغط إضافة" else "لا نتائج",
                    onRetry = onRefresh
                )
            }
            itemsIndexed(rows, key = { _, a -> a.id }) { i, a ->
                val isSel = a.id in selected
                Box(Modifier.animateItem()) {
                    PppCard(
                        index = i + 1,
                        a = a,
                        limit = limits[a.profile] ?: "",
                        selected = isSel,
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (isSel) selected - a.id else selected + a.id
                            else details = a
                        },
                        onLongClick = { selected = if (isSel) selected - a.id else selected + a.id }
                    )
                }
            }
        }

        // شريط الإجراءات الجماعية
        if (selRows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(C.CardHi).padding(D.screen, 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("محدد: " + selRows.size, Modifier.weight(1f), color = C.Text, style = ty.titleSmall)
                    TextButton({ selected = emptySet() }) { Text("إلغاء التحديد", color = C.Dim) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton("واتساب", { whatsapp = selRows }, Modifier.weight(1f).height(40.dp), BtnKind.Secondary, Icons.Filled.Chat)
                    AppButton("تفعيل", {
                        act("تم التفعيل") { Broadband.bulk("enable", selRows) }
                        selected = emptySet()
                    }, Modifier.weight(1f).height(40.dp), BtnKind.Secondary, Icons.Filled.CheckCircle)
                    AppButton("تعطيل", {
                        act("تم التعطيل") { Broadband.bulk("disable", selRows) }
                        selected = emptySet()
                    }, Modifier.weight(1f).height(40.dp), BtnKind.Warning, Icons.Filled.Block)
                    AppButton("حذف", { confirmDel = selRows }, Modifier.weight(1f).height(40.dp), BtnKind.Danger, Icons.Filled.DeleteOutline)
                }
            }
        }
    }

    if (adding || editing != null) {
        PppAccountDialog(
            editing, profileNames,
            onDismiss = { adding = false; editing = null }
        ) { name, pass, prof, note, meta ->
            val ex = editing
            adding = false
            editing = null
            act(if (ex == null) "تمت إضافة الحساب" else "تم حفظ الحساب") {
                Broadband.save(ex, name, pass, prof, note, meta)
            }
        }
    }

    details?.let { a ->
        AppDialog(a.name, onDismiss = { details = null }, buttons = {
            AppButton("إغلاق", { details = null }, kind = BtnKind.Ghost)
        }) {
            KvGrid(pppDetails(a))
            if (a.external) {
                Text(
                    "متصل عبر يوزر منجر (RADIUS) — ليس له حساب في PPP Secrets. عدّل باقته وبياناته من قسم يوزر منجر.",
                    color = C.Dim, style = MaterialTheme.typography.bodySmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("قطع", Icons.Filled.LinkOff, C.Gold) {
                        details = null
                        act("تم القطع") { Broadband.bulk("kick", listOf(a)) }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallAction("تعديل", Icons.Filled.Edit, C.Steel) {
                        details = null
                        editing = a
                    }
                    SmallAction("تجديد 30 يوم", Icons.Filled.Autorenew, C.Teal) {
                        details = null
                        act("تم التجديد") { Broadband.renew(a, 30) }
                    }
                    SmallAction(if (a.paid) "غير مدفوع" else "مدفوع", Icons.Filled.Paid, C.Gold) {
                        details = null
                        act("تم") { Broadband.setMeta(a, mapOf("paid" to if (a.paid) "" else "1")) }
                    }
                    if (a.phone.isNotBlank()) SmallAction("واتساب", Icons.Filled.Chat, C.Online) {
                        details = null
                        whatsapp = listOf(a)
                    }
                    if (a.online) SmallAction("قطع", Icons.Filled.LinkOff, C.Gold) {
                        details = null
                        act("تم القطع") { Broadband.bulk("kick", listOf(a)) }
                    }
                    SmallAction(
                        if (a.disabled) "تفعيل" else "تعطيل",
                        if (a.disabled) Icons.Filled.CheckCircle else Icons.Filled.Block,
                        if (a.disabled) C.Green else C.Gold
                    ) {
                        details = null
                        act(if (a.disabled) "تم التفعيل" else "تم التعطيل") {
                            Broadband.bulk(if (a.disabled) "enable" else "disable", listOf(a))
                        }
                    }
                    SmallAction("حذف", Icons.Filled.DeleteOutline, C.Red) {
                        details = null
                        confirmDel = listOf(a)
                    }
                }
            }
        }
    }

    confirmDel?.let { list ->
        ConfirmDialog(
            "حذف الحسابات",
            "حذف " + list.size + " من حسابات البرودباند؟ لا يمكن التراجع.",
            "حذف",
            onConfirm = {
                act("تم الحذف") { Broadband.bulk("remove", list) }
                selected = emptySet()
            },
            onDismiss = { confirmDel = null }
        )
    }

    whatsapp?.let { list ->
        PppWhatsAppDialog(list, onDismiss = { whatsapp = null })
    }
}

/** مدة الاتصال بصيغة ساعة (مع الأيام). */
private fun clock(uptime: String): String {
    val s = Fmt.seconds(uptime)
    val d = s / 86400
    val hms = String.format(Locale.US, "%02d:%02d:%02d", (s % 86400) / 3600, (s % 3600) / 60, s % 60)
    return if (d > 0) "${d}d $hms" else hms
}

private fun expText(ms: Long): String {
    val days = Math.floorDiv(ms - System.currentTimeMillis(), 86_400_000L)
    return when {
        days < 0 -> "منتهي"
        days == 0L -> "ينتهي اليوم"
        days == 1L -> "ينتهي غداً"
        else -> "باقي $days يوم"
    }
}

/** تفاصيل الحساب كأزواج (عنوان، قيمة). */
fun pppDetails(a: PppAccount): List<Pair<String, String>> = listOfNotNull(
    "الحالة" to when {
        a.disabled -> "معطل"
        a.online -> "نشط الآن"
        a.neverUsed -> "غير مستخدم"
        else -> "نائم"
    },
    "الباقة" to a.profile.ifBlank { if (a.external) "يوزر منجر" else "—" },
    (!a.external).takeIf { it }?.let { "كلمة المرور" to a.password.ifBlank { "—" } },
    a.phone.takeIf { it.isNotBlank() }?.let { "الهاتف" to "‎$it" },
    a.online.takeIf { it }?.let { "عنوان IP" to a.address },
    a.online.takeIf { it }?.let { "مدة الاتصال" to Fmt.durationShort(a.uptime) },
    a.online.takeIf { it }?.let { "السرعة" to Fmt.bps(a.rateBps) },
    a.callerId.takeIf { it.isNotBlank() }?.let { "MAC" to it },
    (!a.external).takeIf { it }?.let { "تاريخ الانتهاء" to a.exp.ifBlank { "بدون انتهاء" } },
    a.price.takeIf { it.isNotBlank() }?.let { "السعر" to it },
    (!a.external).takeIf { it }?.let { "الدفع" to if (a.paid) "مدفوع" else "غير مدفوع" },
    (a.gb > 0 || a.usedBytes > 0).takeIf { it }?.let { "الاستهلاك" to Fmt.bytes(a.usedBytes) },
    (a.gb > 0).takeIf { it }?.let { "حد الجيجات" to "‎${a.gb} GB" },
    a.note.takeIf { it.isNotBlank() }?.let { "ملاحظة" to it }
)

// ─────────────────────────── بطاقة الحساب ───────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PppCard(
    index: Int,
    a: PppAccount,
    limit: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val ty = MaterialTheme.typography
    val statusColor = when {
        a.disabled -> C.Faint
        a.online -> C.Purple
        a.neverUsed -> C.Gold
        else -> Color(0xFFE0457B)
    }
    val accent = Color(if (a.external) 0xFF8B5CF6 else 0xFF6B2FA3)
    val shape = RoundedCornerShape(D.radiusCard)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
            .background(if (selected) C.Teal.copy(alpha = 0.1f) else C.Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Teal else statusColor.copy(alpha = 0.55f), shape)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
    ) {
        // شريط جانبي ملون
        Box(Modifier.width(5.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(accent, statusColor))))
        Column(Modifier.weight(1f).padding(D.md, D.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = C.Teal)
                    else Text("$index", color = accent, style = ty.labelLarge)
                }
                Spacer(Modifier.width(D.sm))
                Text(
                    Fmt.ltr(a.name), Modifier.weight(1f), color = Color(0xFF2FA8DE),
                    overflow = TextOverflow.Ellipsis, maxLines = 1, style = ty.titleLarge
                )
                if (a.paid) Text("💰", style = ty.titleMedium)
                if (a.phone.isNotBlank()) Icon(Icons.Filled.Phone, "هاتف", Modifier.size(18.dp), tint = C.Dim)
                Icon(Icons.Filled.ChevronLeft, null, tint = C.Dim)
            }

            val left: Long? = a.expMs?.let { it - System.currentTimeMillis() } ?: a.umRemainingMs
            val leftBytes: Long? =
                if (a.gb > 0) (a.gb * 1073741824L - a.usedBytes).coerceAtLeast(0L) else a.umLeftBytes
            Row(
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PChip(a.profile.ifBlank { "بدون باقة" }, accent)
                PChip(
                    left?.let { if (it <= 0) "منتهي" else RosTime.left(it) } ?: "بدون مدة",
                    if ((left ?: Long.MAX_VALUE) < 86_400_000L) C.Red else C.Teal
                )
                PChip(
                    leftBytes?.let { "باقي " + Fmt.bytes(it) } ?: "رصيد مفتوح",
                    if (leftBytes == null || leftBytes >= 524_288_000L) C.Blue else C.Red
                )
                a.service.takeIf { it !in setOf("any", "pppoe") }?.let {
                    PChip(it.uppercase(Locale.ROOT), C.Steel)
                }
            }

            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PInfo(Icons.Filled.ArrowDownward, "تنزيل", if (a.online) Fmt.bytes(a.download) else "—", C.Teal, Modifier.weight(1f))
                PInfo(Icons.Filled.ArrowUpward, "رفع", if (a.online) Fmt.bytes(a.upload) else "—", C.Blue, Modifier.weight(1f))
                PInfo(
                    Icons.Filled.DataUsage, "الإجمالي",
                    when {
                        a.online -> Fmt.bytes(a.download + a.upload)
                        a.usedBytes > 0 -> Fmt.bytes(a.usedBytes)
                        else -> "—"
                    },
                    C.Purple, Modifier.weight(1f)
                )
                PInfo(Icons.Filled.Speed, "السرعة", if (a.online) Fmt.bps(a.rateBps) else "—", C.Gold, Modifier.weight(1f))
                val lim = limit.ifBlank { "مفتوح" }
                PInfo(Icons.Filled.Tune, "المحددة", if (lim != "مفتوح") Fmt.ltr(lim) else lim, C.Red, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val (status, color) = when {
                    a.disabled -> "معطل" to C.Faint
                    a.online -> "متصل • " + Fmt.durationShort(a.uptime) to C.Online
                    a.neverUsed -> "لم يتصل بعد" to C.Gold
                    else -> "غير متصل" + (a.lastLoggedOut?.let { " • " + RosTime.ago(it) } ?: "") to Color(0xFFE0457B)
                }
                Dot(color, 8.dp)
                Spacer(Modifier.width(5.dp))
                Text(status, Modifier.weight(1f), color = color, maxLines = 1, style = ty.bodySmall)
                Text(
                    if (a.external) "يوزر منجر" else "برودباند",
                    Modifier.clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(statusColor, accent)))
                        .padding(10.dp, 3.dp),
                    color = Color.White, style = ty.labelMedium
                )
            }
        }
    }
}

@Composable
private fun PChip(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(9.dp, 2.dp),
        color = color, maxLines = 1, style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun PInfo(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(D.radiusSmall)).background(C.CardHi).padding(2.dp, 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(13.dp), tint = color)
            Spacer(Modifier.width(2.dp))
            Text(label, color = C.Dim, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            value, color = C.Text, overflow = TextOverflow.Ellipsis, maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun StatusBadge(a: PppAccount, modifier: Modifier) {
    val (colors, label) = when {
        a.disabled -> listOf(Color(0xFF8A9499), Color(0xFFB4BCBE)) to "معطل"
        a.online -> listOf(Color(0xFF0F7A2A), Color(0xFF2BD14A)) to Fmt.bps(a.rateBps).replace(" ", "\n")
        a.neverUsed -> listOf(Color(0xFFB8900A), Color(0xFFF2D21C)) to "جديد"
        else -> listOf(Color(0xFFB8407F), Color(0xFFF0147A)) to "نائم"
    }
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = Color.White, textAlign = TextAlign.Center, maxLines = 2,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}

// ─────────────────────────── نافذة الحساب ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PppAccountDialog(
    initial: PppAccount?,
    profiles: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, pass: String, profile: String, note: String, meta: Map<String, String>) -> Unit
) {
    val ctx = LocalContext.current
    val rnd = remember { SecureRandom() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pass by remember {
        mutableStateOf(initial?.password ?: (1..6).map { rnd.nextInt(10) }.joinToString(""))
    }
    var profile by remember {
        mutableStateOf(initial?.profile ?: profiles.firstOrNull { it != Broadband.EXPIRED_PROFILE } ?: "default")
    }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var price by remember { mutableStateOf(initial?.price ?: "") }
    var gb by remember { mutableStateOf(initial?.gb?.takeIf { it > 0 }?.toString() ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var exp by remember { mutableStateOf(initial?.exp ?: "") }
    var paid by remember { mutableStateOf(initial?.paid ?: false) }
    var pickDate by remember { mutableStateOf(false) }

    // اختيار رقم من جهات الاتصال
    val contact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) phone = c.getString(0).filter { it.isDigit() || it == '+' }
            }
        }
    }

    fun plusDays(d: Int) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, d)
        exp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    FormDialog(
        title = if (initial == null) "إضافة حساب برودباند" else "تعديل ${initial.name}",
        enabled = name.isNotBlank() && pass.isNotBlank(),
        onConfirm = {
            onSave(
                name.trim(), pass.trim(), profile, note.trim(),
                mapOf(
                    "phone" to phone.trim(),
                    "price" to price.trim(),
                    "exp" to exp.trim(),
                    "gb" to gb.trim(),
                    "paid" to if (paid) "1" else ""
                )
            )
        },
        onDismiss = onDismiss
    ) {
        Field(name, { name = it.trim() }, "اسم المستخدم", ltr = true)
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
            Field(pass, { pass = it.trim() }, "كلمة المرور", Modifier.weight(1f), ltr = true)
            IconButton({ pass = (1..6).map { rnd.nextInt(10) }.joinToString("") }) {
                Icon(Icons.Filled.Casino, "توليد", tint = C.Teal)
            }
        }
        Dropdown("الباقة", profile, profiles.ifEmpty { listOf("default") }) { profile = it }
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Field(price, { price = it }, "السعر", Modifier.weight(1f), placeholder = "اختياري")
            Field(gb, { gb = it.filter { c -> c.isDigit() } }, "الجيجات شهرياً", Modifier.weight(1f), number = true, placeholder = "بدون حد")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
            Field(phone, { phone = it }, "الهاتف", Modifier.weight(1f), number = true, placeholder = "اختياري", ltr = true)
            AppButton(
                "", { contact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                Modifier.height(48.dp), BtnKind.Secondary, Icons.Filled.Contacts
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
            Field(exp, { exp = it }, "تاريخ الانتهاء", Modifier.weight(1f), placeholder = "بدون انتهاء", ltr = true)
            AppButton("", { pickDate = true }, Modifier.height(48.dp), BtnKind.Secondary, Icons.Filled.CalendarMonth)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30 to "+30 يوم", 7 to "+7 أيام", 1 to "+1 يوم").forEach { (d, label) ->
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(D.radiusSmall))
                        .border(1.dp, C.Teal.copy(alpha = 0.5f), RoundedCornerShape(D.radiusSmall))
                        .clickable { plusDays(d) }
                        .padding(10.dp, 5.dp),
                    color = C.Teal, style = MaterialTheme.typography.labelLarge
                )
            }
            if (exp.isNotBlank()) Text(
                "بدون", Modifier.clickable { exp = "" }.padding(8.dp, 5.dp),
                color = C.Dim, style = MaterialTheme.typography.labelLarge
            )
        }
        Field(note, { note = it }, "ملاحظة", placeholder = "اختياري")
        Row(Modifier.fillMaxWidth().clickable { paid = !paid }, verticalAlignment = Alignment.CenterVertically) {
            Text("مدفوع", Modifier.weight(1f), color = C.Text, style = MaterialTheme.typography.bodyLarge)
            Switch(paid, { paid = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal))
        }
    }

    if (pickDate) {
        val st = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { ms ->
                        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        f.timeZone = TimeZone.getTimeZone("UTC")
                        exp = f.format(Date(ms))
                    }
                    pickDate = false
                }) { Text("اختيار") }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("إلغاء") } }
        ) {
            DatePicker(st)
        }
    }
}

// ─────────────────────────── رسائل واتساب ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PppWhatsAppDialog(rows: List<PppAccount>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var start by remember { mutableStateOf("السلام عليكم") }
    var end by remember { mutableStateOf("شكراً لاشتراككم") }
    var withName by remember { mutableStateOf(true) }
    var withPrice by remember { mutableStateOf(true) }
    var withExp by remember { mutableStateOf(true) }
    val sent: SnapshotStateList<String> = remember { mutableStateListOf() }
    val withPhone = rows.filter { it.phone.isNotBlank() }

    fun msg(a: PppAccount): String = listOfNotNull(
        start.takeIf { it.isNotBlank() },
        if (withName) "الحساب: " + a.name else null,
        if (withPrice && a.price.isNotBlank()) "المبلغ: " + a.price else null,
        if (withExp && a.exp.isNotBlank()) "تاريخ الانتهاء: " + a.exp else null,
        end.takeIf { it.isNotBlank() }
    ).joinToString("\n")

    AppDialog("إرسال رسائل إلى " + withPhone.size + " من المستخدمين", onDismiss, buttons = {
        AppButton("إغلاق", onDismiss, kind = BtnKind.Ghost)
    }) {
        Field(start, { start = it }, "بداية الرسالة")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple("اسم الحساب", withName) { v: Boolean -> withName = v },
                Triple("السعر", withPrice) { v: Boolean -> withPrice = v },
                Triple("تاريخ الانتهاء", withExp) { v: Boolean -> withExp = v }
            ).forEach { (label, v, set) ->
                Row(Modifier.clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(v, { set(it) }, colors = CheckboxDefaults.colors(checkedColor = C.Teal))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Field(end, { end = it }, "شكر أو تنبيه")
        if (withPhone.isEmpty()) {
            Text("لا يوجد رقم هاتف للحسابات المحددة", color = C.Red, style = MaterialTheme.typography.bodyMedium)
        }
        withPhone.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, color = C.Text, style = MaterialTheme.typography.titleSmall)
                    Text("‎" + a.phone, color = C.Dim, style = MaterialTheme.typography.labelSmall)
                }
                val done = a.id in sent
                AppButton(
                    if (done) "أُرسلت ✓" else "إرسال",
                    {
                        sent.add(a.id)
                        openWhatsAppTo(ctx, a.phone, msg(a))
                    },
                    Modifier.height(38.dp),
                    if (done) BtnKind.Ghost else BtnKind.Primary,
                    Icons.Filled.Send
                )
            }
        }
    }
}

// ─────────────────────────── إعدادات PPPoE ───────────────────────────

@Composable
fun PppSettingsDialog(
    initial: PppSettings,
    onDismiss: () -> Unit,
    inline: Boolean = false,
    onSave: (PppSettings) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(initial) }
    var servers by remember { mutableStateOf<List<Map<String, String>>?>(null) }
    var ifaces by remember { mutableStateOf<List<String>>(emptyList()) }
    var iface by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        servers = runCatching { Broadband.servers() }.getOrDefault(emptyList())
        ifaces = runCatching { Broadband.bridges() }.getOrDefault(emptyList())
        if (iface.isBlank()) iface = ifaces.firstOrNull() ?: ""
    }

    LaunchedEffect(Unit) {
        load()
        s = s.copy(gbCounter = Broadband.gigaEnabled())
    }

    val active = (servers ?: emptyList()).filter { it["disabled"] != "true" }

    val body: @Composable ColumnScope.() -> Unit = {
        // حالة خادم PPPoE
        AppCard(Modifier.fillMaxWidth(), background = C.CardHi, padding = PaddingValues(D.md)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (servers == null || busy) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = C.Teal)
                } else if (active.isNotEmpty()) {
                    Text("PPPoE مفعّل ✓", color = C.Online, style = MaterialTheme.typography.titleMedium)
                    active.forEach { srv ->
                        srv["interface"]?.let {
                            Text("على $it", color = C.Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text("PPPoE غير مفعّل", color = C.Red, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(D.sm))
                Text("اختر البريدج أو المنفذ لتفعيل البرودباند عليه:", color = C.Dim, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Dropdown("", iface, ifaces, Modifier.weight(1f)) { iface = it }
                    AppButton(
                        "تفعيل PPPoE",
                        {
                            busy = true
                            scope.launch {
                                try {
                                    Broadband.enableServer(iface)
                                    toast(ctx, "تم تفعيل PPPoE على $iface", Toast.LENGTH_SHORT).show()
                                    load()
                                } catch (e: Exception) {
                                    toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show()
                                }
                                busy = false
                            }
                        },
                        Modifier.height(48.dp),
                        enabled = iface.isNotBlank() && !busy
                    )
                }
            }
        }
        SwitchLine("عرض السرعات تلقائياً", "تحديث سرعة كل حساب كل 3 ثوانٍ", s.autoSpeed) { s = s.copy(autoSpeed = it) }
        SwitchLine(
            "تفعيل عداد الجيجا",
            "ينشئ جدولة gigaCounter في Scheduler كل 20 دقيقة لمتابعة استهلاك كل عميل وتعطيله عند تجاوز حد الجيجات. عند الإيقاف تُحذف الجدولة. ⚠️ لا تفعّله إلا عند الحاجة.",
            s.gbCounter
        ) { s = s.copy(gbCounter = it) }
        Text("ماذا تريد أن يحدث عند انتهاء الحساب أو نفاد الجيجات؟", color = C.Text, style = MaterialTheme.typography.titleSmall)
        listOf(
            "none" to "لا شيء",
            "disable" to "تعطيل الحساب",
            "page" to "نقل لباقة المنتهين (سرعة 64k + قائمة ppp-expired لصفحة الانتهاء)"
        ).forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { s = s.copy(onExpire = k) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(s.onExpire == k, { s = s.copy(onExpire = k) }, colors = RadioButtonDefaults.colors(selectedColor = C.Teal))
                Text(label, color = C.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (inline) {
        Column(Modifier.padding(D.md), verticalArrangement = Arrangement.spacedBy(D.sm)) {
            Text("إعدادات PPPoE", color = C.Text, style = MaterialTheme.typography.titleLarge)
            body()
        }
    } else {
        FormDialog("إعدادات PPPoE", onConfirm = { onSave(s) }, onDismiss = onDismiss, content = body)
    }
}

@Composable
fun SwitchLine(title: String, sub: String?, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!v) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, color = C.Dim, style = MaterialTheme.typography.labelSmall)
        }
        Switch(v, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal))
    }
}

// ─────────────────────────── تبويب الباقات ───────────────────────────

@Composable
fun PppProfilesTab(
    profiles: List<Map<String, String>>,
    accounts: List<PppAccount>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    act: (String, suspend () -> Unit) -> Unit
) {
    var edit by remember { mutableStateOf<Map<String, String>?>(null) }
    var quick by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Map<String, String>?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.sm)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                AppButton("باقات سريعة", { quick = true }, Modifier.weight(1f).height(42.dp), BtnKind.Secondary, Icons.Filled.Bolt)
                AppButton("إضافة باقة", { edit = emptyMap() }, Modifier.weight(1f).height(42.dp), icon = Icons.Filled.Add)
            }
        }
        item {
            StateView(
                loading = loading && profiles.isEmpty(),
                error = error,
                empty = !loading && error == null && profiles.isEmpty(),
                emptyText = "لا توجد باقات",
                onRetry = onRefresh
            )
        }
        items(profiles, key = { it[".id"] ?: it["name"] ?: "" }) { p ->
            val name = p["name"] ?: ""
            PackageCard(
                name = name,
                badge = "${accounts.count { it.profile == name }} حساب",
                badgeColor = C.Purple,
                stats = listOf(
                    "السرعة" to (p["rate-limit"] ?: "").ifBlank { "بلا قيود" },
                    "مجمع العناوين" to (p["remote-address"] ?: "").ifBlank { "—" },
                    "نشط الآن" to accounts.count { it.profile == name && it.online }.toString()
                ),
                // البروفايل الافتراضي (*0) لا يُعدَّل، و default لا يُحذف
                onEdit = if ((p[".id"] ?: "").startsWith("*0")) null else ({ edit = p }),
                onDelete = if (name in setOf("default", "default-encryption")) null else ({ deleting = p })
            )
        }
    }

    edit?.let { p ->
        PppProfileDialog(p, onDismiss = { edit = null }) { name, rate, pool, local ->
            edit = null
            act("تم حفظ الباقة") {
                if (p.isEmpty()) Broadband.addProfile(name, rate, pool, local)
                else Broadband.updateProfile(p[".id"]!!, name, rate, pool, local)
            }
        }
    }

    if (quick) {
        PppQuickProfilesDialog(onDismiss = { quick = false }) { pool, local, items ->
            quick = false
            act("تمت إضافة " + items.size + " باقة") {
                items.forEach { (n, r) -> runCatching { Broadband.addProfile(n, r, pool, local) } }
            }
        }
    }

    deleting?.let { p ->
        ConfirmDialog(
            "حذف باقة",
            "حذف الباقة \"" + p["name"] + "\"؟",
            "حذف",
            onConfirm = { act("تم الحذف") { Broadband.removeProfile(p[".id"]!!) } },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun PppProfileDialog(
    init: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (name: String, rate: String, pool: String, local: String) -> Unit
) {
    var name by remember { mutableStateOf(init["name"] ?: "") }
    var rate by remember { mutableStateOf(init["rate-limit"] ?: "") }
    var pool by remember { mutableStateOf(init["remote-address"] ?: "") }
    var local by remember { mutableStateOf(init["local-address"] ?: "") }
    var pools by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        pools = runCatching { Broadband.pools() }.getOrDefault(emptyList())
    }

    FormDialog(
        title = if (init.isEmpty()) "إضافة باقة برودباند" else "تعديل " + init["name"],
        enabled = name.isNotBlank(),
        onConfirm = { onSave(name.trim(), rate.trim(), pool, local.trim()) },
        onDismiss = onDismiss
    ) {
        Field(name, { name = it }, "اسم الباقة", ltr = true)
        ComboField(
            rate, { rate = it }, "السرعة (رفع/تنزيل)",
            Broadband.PRESETS.map { it.second to it.first },
            placeholder = "2M/4M"
        )
        ComboField(
            pool,
            { v ->
                pool = v
                // عنوان البوابة تلقائياً من نطاق الـ pool
                if (local.isBlank()) pools.firstOrNull { it.first == v }?.let { local = Broadband.localFor(it.second) }
            },
            "مجمع العناوين (IP Pool)",
            pools,
            placeholder = "pool"
        )
        Field(local, { local = it }, "عنوان الراوتر (Local Address)", placeholder = "10.0.11.1", ltr = true)
    }
}

@Composable
private fun PppQuickProfilesDialog(
    onDismiss: () -> Unit,
    onAdd: (pool: String, local: String, items: List<Pair<String, String>>) -> Unit
) {
    var pools by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pool by remember { mutableStateOf("") }
    val rows = remember { Broadband.PRESETS.take(6).map { mutableStateOf(it) }.toMutableStateList() }

    LaunchedEffect(Unit) {
        pools = runCatching { Broadband.pools() }.getOrDefault(emptyList())
        if (pool.isBlank()) pool = pools.firstOrNull()?.first ?: ""
    }

    FormDialog(
        title = "سيتم إضافة الباقات الآتية",
        confirmText = "إضافة الباقات",
        enabled = rows.isNotEmpty(),
        onConfirm = {
            val local = pools.firstOrNull { it.first == pool }?.let { Broadband.localFor(it.second) } ?: ""
            onAdd(pool, local, rows.map { it.value }.filter { it.first.isNotBlank() })
        },
        onDismiss = onDismiss
    ) {
        Dropdown("نطاق الآيبي (IP Pool)", pool, pools.map { it.first }) { pool = it }
        rows.forEachIndexed { i, r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(r.value.first, { r.value = it to r.value.second }, "اسم الباقة", Modifier.weight(1f), ltr = true)
                Field(r.value.second, { r.value = r.value.first to it }, "السرعة", Modifier.weight(1.2f), ltr = true)
                IconButton({ rows.removeAt(i) }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "حذف", tint = C.Red)
                }
            }
        }
        TextButton({ rows.add(mutableStateOf("" to "")) }) {
            Icon(Icons.Filled.Add, null, tint = C.Teal)
            Text("سطر جديد", color = C.Teal)
        }
    }
}

// ─────────────────────────── واتساب ───────────────────────────

/** فتح محادثة واتساب مع رسالة جاهزة (أرقام يمنية 7xxxxxxxx تُسبق بـ 967). */
fun openWhatsAppTo(ctx: Context, phone: String, text: String) {
    var p = phone.filter { it.isDigit() }
    if (p.startsWith("00")) p = p.drop(2)
    else if (p.startsWith("7") && p.length == 9) p = "967$p"
    val uri = Uri.parse("https://wa.me/" + p + "?text=" + Uri.encode(text))
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        toast(ctx, "تعذر فتح واتساب", Toast.LENGTH_LONG).show()
    }
}
