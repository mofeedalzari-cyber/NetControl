package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.RosTime
import com.zarinet.netcontrol.util.ScreenCache
import com.zarinet.netcontrol.util.UmStatus
import com.zarinet.netcontrol.util.UmUserRow
import com.zarinet.netcontrol.util.UserManager
import kotlinx.coroutines.launch

/** حالات العرض وألوانها: رمادي معطل، أحمر منتهي، أخضر مستخدم، أصفر جديد. */
enum class UmView(val label: String, val c1: Color, val c2: Color) {
    ALL("الكل", Color(0xFF2A7FB8), Color(0xFF3FA3E0)),
    USED("المستخدمة", Color(0xFF0F7A2A), Color(0xFF2BD14A)),
    UNUSED("غير المستخدمة", Color(0xFFB8900A), Color(0xFFF2D21C)),
    EXPIRED("المنتهية", Color(0xFFB8407F), Color(0xFFF0147A)),
    DISABLED("المعطلة", Color(0xFF626A6E), Color(0xFFB4BCBE));

    val solid get() = c2

    /** اسم مختصر يتسع في شريحة الفلتر. */
    val short: String
        get() = when (this) {
            ALL -> "الكل"
            USED -> "مستخدمة"
            UNUSED -> "جديدة"
            EXPIRED -> "منتهية"
            DISABLED -> "معطلة"
        }
}

fun UmUserRow.view(): UmView = when {
    disabled -> UmView.DISABLED
    status == UmStatus.EXPIRED -> UmView.EXPIRED
    status == UmStatus.USED -> UmView.USED
    else -> UmView.UNUSED
}

private val NameBlue = Color(0xFF2FA8DE)
private val StatusPurple = Color(0xFFC06BF0)

@Composable
fun UmSubscribersTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = ScreenCache
    var users by remember { mutableStateOf(cache.get<List<UmUserRow>>("um:users") ?: emptyList()) }
    var loading by remember { mutableStateOf(users.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, List<UmUserRow>>?>(null) }  // (action, rows)
    var details by remember { mutableStateOf<UmUserRow?>(null) }
    var menu by remember { mutableStateOf<UmUserRow?>(null) }
    var editing by remember { mutableStateOf<UmUserRow?>(null) }
    var moving by remember { mutableStateOf<List<UmUserRow>?>(null) }
    var clearSel by remember { mutableStateOf(0) }

    /**
     * @param force تجاوز الذاكرة المؤقتة وإعادة الجلب من الراوتر
     * @param quick عند عدم الإجبار: تُعتبر البيانات حديثة إن كان عمرها أقل من 3 ثوانٍ (وإلا 30 ثانية)
     */
    fun load(force: Boolean = true, quick: Boolean = false) {
        if (!force && cache.fresh("um:users", if (quick) 3000L else 30000L)) { loading = false; return }
        if (force) Session.client?.invalidateCache()
        loading = users.isEmpty(); error = null
        scope.launch {
            try {
                users = UserManager.users()
                cache.put("um:users", users)
            } catch (e: Exception) {
                if (users.isEmpty()) error = e.message ?: "خطأ"
            }
            loading = false
        }
    }
    fun run(action: String, rows: List<UmUserRow>, onDone: () -> Unit = {}) {
        val label = when (action) { "remove" -> "حذف"; "disable" -> "تعطيل"; else -> "تفعيل" }
        busy = "جاري $label ${rows.size} كرت..."
        scope.launch {
            try {
                UserManager.bulk(action, rows.map { it.id }) { busy = "جاري $label: $it / ${rows.size}" }
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم $label ${rows.size} كرت", Toast.LENGTH_SHORT).show()
                onDone(); load()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }
    LaunchedEffect(Unit) { load(force = false, quick = true) }

    UmSubscribersContent(
        users = users, loading = loading, error = error, busy = busy, onRefresh = ::load,
        onAction = { action, rows -> if (action == "enable") run(action, rows) else confirm = action to rows },
        onOpen = { details = it },
        onMenu = { menu = it },
        onMove = { moving = it },
        byPackage = true,
        clearSelection = clearSel
    )
    moving?.let { rows ->
        UmMoveDialog(rows, onDismiss = { moving = null }) { target ->
            moving = null
            val fresh = rows.count { it.status == UmStatus.UNUSED && it.profile != target }
            busy = "جاري النقل إلى $target..."
            scope.launch {
                try {
                    val moved = UserManager.moveToProfile(rows, target) { busy = "جاري النقل: $it / $fresh" }
                    val skipped = rows.size - moved
                    com.zarinet.netcontrol.ui.components.toast(ctx,
                        "تم نقل $moved كرت إلى $target" + if (skipped > 0) " (تم تجاوز $skipped غير جديد أو في نفس الباقة)" else "",
                        Toast.LENGTH_LONG).show()
                    clearSel++
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                busy = null
                load()
            }
        }
    }
    menu?.let { u -> CardMenuHost(u, com.zarinet.netcontrol.util.UserManagerOps, onClose = { menu = null }, onChanged = ::load, onEdit = { editing = it }) }
    editing?.let { u -> UmEditDialog(u, onDismiss = { editing = null }, onDone = ::load) }

    confirm?.let { (action, rows) ->
        val del = action == "remove"
        ConfirmDialog(if (del) "حذف الكروت" else "تعطيل الكروت",
            "هل أنت متأكد أنك تريد ${if (del) "حذف" else "تعطيل"} ${rows.size} من البطاقات؟" + if (del) "\nلا يمكن التراجع." else "",
            if (del) "حذف" else "تعطيل", danger = true,
            onConfirm = { run(action, rows) }, onDismiss = { confirm = null })
    }
    details?.let { u ->
        AppDialog(u.name, onDismiss = { details = null }, buttons = {
            UmDetailsButtons(u,
                onEdit = { details = null; editing = u },
                onDelete = { details = null; confirm = "remove" to listOf(u) },
                onToggle = {
                    details = null
                    if (u.disabled) run("enable", listOf(u)) else confirm = "disable" to listOf(u)
                },
                onClose = { details = null })
        }) {
            KvGrid(umDetails(u))
        }
    }
}

/**
 * نقل الكروت الجديدة (غير المستخدمة) إلى باقة أخرى.
 * @param profiles قائمة الباقات إن كانت محمّلة مسبقاً، وإلا تُجلب من يوزر منجر
 */
@Composable
fun UmMoveDialog(rows: List<UmUserRow>, profiles: List<String>? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var list by remember { mutableStateOf(profiles ?: emptyList()) }
    LaunchedEffect(Unit) {
        if (profiles == null) list = runCatching { UserManager.profileNames() }.getOrDefault(emptyList())
    }
    val fresh = rows.filter { it.status == UmStatus.UNUSED }
    val from = fresh.map { it.profile }.filter { it.isNotBlank() }.distinct()
    var target by remember(list) { mutableStateOf(list.firstOrNull { it !in from }.orEmpty()) }
    FormDialog("نقل إلى باقة أخرى", confirmText = "نقل", enabled = target.isNotBlank() && fresh.isNotEmpty(),
        onConfirm = { onConfirm(target) }, onDismiss = onDismiss) {
        Text("الكروت الجديدة: ${fresh.size} من ${rows.size}" + if (from.isEmpty()) "" else " • من: " + from.joinToString("، "),
            style = MaterialTheme.typography.bodyMedium, color = C.Text)
        Dropdown("الباقة الجديدة", target, list) { target = it }
        Text("يُنقل الكرت الجديد فقط (لم يُستخدم بعد) ويبقى رقمه وكلمة مروره والملاحظة كما هي. الكروت المستخدمة أو المنتهية لا تتغير.",
            style = MaterialTheme.typography.bodySmall, color = C.Dim)
    }
}

/** أزرار نافذة تفاصيل الكرت: تعديل، حذف، تفعيل/تعطيل، إغلاق. */
@Composable
fun RowScope.UmDetailsButtons(u: UmUserRow, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit) {
    AppButton("تعديل", onEdit, Modifier.weight(1f))
    AppButton("حذف", onDelete, Modifier.weight(1f), BtnKind.Danger)
    AppButton(if (u.disabled) "تفعيل" else "تعطيل", onToggle, Modifier.weight(1f), BtnKind.Warning)
    AppButton("إغلاق", onClose, Modifier.weight(1f), BtnKind.Ghost)
}

/** صفوف تفاصيل الكرت (نافذة التفاصيل). */
fun umDetails(u: UmUserRow): List<Pair<String, String>> = listOfNotNull(
    "الحالة" to u.view().label.removePrefix("ال"),
    "الباقة" to u.profile.ifBlank { "—" },
    "كلمة المرور" to u.password.ifBlank { "بدون" },
    "آخر ظهور" to statusText(u),
    "تاريخ تسجيل الدخول" to (u.firstLogin?.let { RosTime.date(it) } ?: "لم يُستخدم"),
    "تاريخ الانتهاء" to (u.expiresAt?.let { RosTime.date(it) } ?: "—"),
    u.remainingMs?.let { "الوقت المتبقي" to RosTime.left(it).removePrefix("باقي ") },
    "الرصيد المستهلك" to Fmt.bytes(u.usedBytes),
    "الرصيد الباقي" to if (u.limitBytes > 0) Fmt.bytes((u.limitBytes - u.usedBytes).coerceAtLeast(0L)) else "غير محدود",
    u.comment.substringBefore(';').takeIf { it.isNotBlank() }?.let { "الدفعة" to it },
)

fun statusText(u: UmUserRow): String = when {
    u.online -> "متصل الآن"
    u.lastSeen != null -> RosTime.ago(u.lastSeen)
    u.info.isNotBlank() -> u.info
    u.status == UmStatus.UNUSED -> "جديد"
    else -> "—"
}

/**
 * جدول المشتركين مع الفلاتر والتحديد والإجراءات الجماعية.
 * @param onMove نقل الكروت المحددة لباقة أخرى (null يخفي الزر)
 * @param byPackage التجميع في القائمة المنسدلة حسب الباقة بدل الدفعة (الملاحظة)
 * @param clearSelection زيادة هذا العدّاد تمسح التحديد
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UmSubscribersContent(
    users: List<UmUserRow>,
    loading: Boolean,
    error: String?,
    busy: String?,
    onRefresh: () -> Unit,
    onAction: (String, List<UmUserRow>) -> Unit,
    onOpen: (UmUserRow) -> Unit,
    onMenu: (UmUserRow) -> Unit = {},
    onMove: ((List<UmUserRow>) -> Unit)? = null,
    byPackage: Boolean = false,
    clearSelection: Int = 0,
    initialSelected: Set<String> = emptySet(),
    initialPackage: String = ALL
) {
    val t = MaterialTheme.typography
    var filter by remember { mutableStateOf(UmView.ALL) }
    var query by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf(initialPackage) }
    var selected by remember { mutableStateOf(initialSelected) }
    // عرض حسب الباقة أو حسب الدفعة (قابل للتبديل من الشاشة)
    var byPkg by remember { mutableStateOf(byPackage) }
    val keyOf: (UmUserRow) -> String = remember(byPkg) {
        if (byPkg) { u -> u.profile.trim() } else { u -> u.comment.substringBefore(';').trim() }
    }
    val batches = remember(users, keyOf) { listOf(ALL) + users.map(keyOf).filter { it.isNotBlank() }.distinct().sorted() }
    val inBatch = remember(users, pkg, keyOf) { if (pkg == ALL) users else users.filter { keyOf(it) == pkg } }
    val counts = remember(inBatch) { inBatch.groupingBy { it.view() }.eachCount() }
    val filtered = remember(inBatch, filter, query) {
        inBatch.filter { u ->
            (filter == UmView.ALL || u.view() == filter) &&
                (query.isBlank() || u.name.contains(query, true) || u.profile.contains(query, true) || u.comment.contains(query, true))
        }
    }
    // عند إعادة التحميل نُبقي فقط المحدد الموجود
    LaunchedEffect(users) { selected = selected intersect users.map { it.id }.toSet() }
    LaunchedEffect(clearSelection) { if (clearSelection > 0) selected = emptySet() }
    val selRows = filtered.filter { it.id in selected }
    val allSelected = filtered.isNotEmpty() && filtered.all { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        // ── شرائح الحالات مع الأعداد ──
        Row(Modifier.fillMaxWidth().padding(horizontal = D.screen, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            UmView.entries.forEach { v ->
                val n = if (v == UmView.ALL) inBatch.size else counts[v] ?: 0
                val sel = v == filter
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(D.radiusSmall))
                        .background(Brush.verticalGradient(if (sel) listOf(v.c2, v.c1) else listOf(C.Card, C.Card)))
                        .border(1.dp, if (sel) Color.Transparent else v.solid.copy(alpha = 0.55f), RoundedCornerShape(D.radiusSmall))
                        .clickable { filter = v }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$n", style = t.titleSmall.copy(fontWeight = FontWeight.Bold), color = if (sel) Color.White else v.solid, maxLines = 1)
                    Text(v.short, style = t.labelSmall, color = if (sel) Color.White else C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.padding(horizontal = D.screen), horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
            CompactSearch(query, { query = it }, "رقم الكرت...", Modifier.weight(1f))
            if (batches.size > 1) CompactDropdown(if (byPkg) "الباقة:" else "الدفعة:", pkg, batches, Modifier.widthIn(max = 150.dp)) { pkg = it }
        }
        // ── تبديل العرض: حسب الباقات / حسب الدفعات ──
        Row(Modifier.fillMaxWidth().padding(horizontal = D.screen, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(true to "حسب الباقات", false to "حسب الدفعات").forEach { (v, label) ->
                val sel = byPkg == v
                Text(label, style = t.labelLarge, color = if (sel) Color.White else C.Dim, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(D.radiusSmall))
                        .background(if (sel) C.Teal else C.Card).border(1.dp, if (sel) Color.Transparent else C.Border, RoundedCornerShape(D.radiusSmall))
                        .clickable { if (byPkg != v) { byPkg = v; pkg = ALL; selected = emptySet() } }.padding(vertical = 7.dp))
            }
        }
        Spacer(Modifier.height(D.sm))

        // ── رأس الجدول + تحديد الكل ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = D.screen).clip(RoundedCornerShape(topStart = D.radiusSmall, topEnd = D.radiusSmall))
                .background(Brush.verticalGradient(listOf(Color(0xFF0E6E5E), Color(0xFF0A2A2A)))).padding(horizontal = D.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("الحالة", style = t.labelMedium, color = C.Gold, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
            Text("الاسم", style = t.labelMedium, color = C.Gold, modifier = Modifier.weight(1f))
            Text("آخر ظهور", style = t.labelMedium, color = C.Gold, modifier = Modifier.width(88.dp), textAlign = TextAlign.Center)
            Checkbox(allSelected, { on ->
                selected = if (on) selected + filtered.map { it.id } else selected - filtered.map { it.id }.toSet()
            }, colors = CheckboxDefaults.colors(checkedColor = C.Teal, uncheckedColor = C.Dim, checkmarkColor = C.OnTeal))
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = D.screen)
                .clip(RoundedCornerShape(bottomStart = D.radiusSmall, bottomEnd = D.radiusSmall)).background(C.Card)
        ) {
            item {
                StateView(loading && users.isEmpty(), error, !loading && error == null && filtered.isEmpty(),
                    if (query.isBlank()) "لا توجد كروت في \"${filter.label}\"" else "لا نتائج لـ \"$query\"", onRefresh)
            }
            items(filtered, key = { it.id }) { u ->
                val isSel = u.id in selected
                UmRow(u, isSel,
                    onToggle = { selected = if (isSel) selected - u.id else selected + u.id },
                    onLongClick = { onMenu(u) },
                    onClick = { if (selected.isEmpty()) onOpen(u) else selected = if (isSel) selected - u.id else selected + u.id })
            }
        }

        // ── شريط الإجراءات الجماعية ──
        if (busy != null) {
            Row(Modifier.fillMaxWidth().padding(D.screen), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
                Spacer(Modifier.width(D.sm)); Text(busy, style = t.bodyMedium, color = C.Dim)
            }
        } else if (selRows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(C.CardHi).padding(horizontal = D.screen, vertical = D.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("محدد: ${selRows.size}", style = t.titleSmall, color = C.Text, modifier = Modifier.weight(1f))
                    TextButton({ selected = emptySet() }) { Text("إلغاء التحديد", color = C.Dim) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    AppButton("تفعيل", { onAction("enable", selRows); selected = emptySet() }, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.CheckCircle)
                    AppButton("تعطيل", { onAction("disable", selRows); selected = emptySet() }, Modifier.weight(1f), BtnKind.Warning, Icons.Filled.Block)
                    AppButton("حذف", { onAction("remove", selRows); selected = emptySet() }, Modifier.weight(1f), BtnKind.Danger, Icons.Filled.DeleteOutline)
                }
                if (onMove != null) {
                    Spacer(Modifier.height(6.dp))
                    AppButton("نقل الجديدة إلى باقة أخرى", { onMove(selRows) }, Modifier.fillMaxWidth(), BtnKind.Primary, Icons.Filled.SwapHoriz)
                }
            }
        } else Spacer(Modifier.height(D.sm))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UmRow(u: UmUserRow, selected: Boolean, onToggle: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    val t = MaterialTheme.typography
    val v = u.view()
    Column(Modifier.fillMaxWidth().background(if (selected) C.Teal.copy(alpha = 0.10f) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.fillMaxWidth().padding(start = D.sm, top = 8.dp, end = 0.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // الدائرة: اسم الباقة (أو "منتهي") بلون الحالة
            Box(Modifier.size(50.dp).clip(CircleShape).background(Brush.linearGradient(listOf(v.c1, v.c2))), contentAlignment = Alignment.Center) {
                val label = if (v == UmView.EXPIRED) "منتهي" else u.profile.ifBlank { "—" }
                // حجم ثابت بالـ dp حتى لا يكبر مع تكبير خط النظام فيخرج من الدائرة
                val px = when { label.length <= 4 -> 14f; label.length <= 6 -> 12f; label.length <= 8 -> 10f; else -> 8.5f }
                val size = with(LocalDensity.current) { px.dp.toSp() }
                Text(label, Modifier.padding(horizontal = 3.dp), color = Color.White, fontSize = size, fontWeight = FontWeight.Bold,
                    fontFamily = t.titleSmall.fontFamily, textAlign = TextAlign.Center, lineHeight = size * 1.1f,
                    overflow = TextOverflow.Ellipsis, softWrap = label.contains(' '), maxLines = 2)
            }
            Spacer(Modifier.width(D.sm))
            Column(Modifier.weight(1f)) {
                Text(Fmt.ltr(u.name), style = t.titleMedium.copy(fontWeight = FontWeight.Bold), color = NameBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                u.remainingMs?.let { Text(RosTime.left(it), style = t.bodySmall, color = C.Online, maxLines = 1) }
            }
            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(statusText(u), style = t.labelLarge, color = if (u.online) C.Online else StatusPurple, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (u.usedBytes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DataUsage, null, Modifier.size(12.dp), tint = C.Dim)
                    Spacer(Modifier.width(3.dp))
                    Text(Fmt.bytes(u.usedBytes), style = t.labelSmall, color = C.Dim, maxLines = 1)
                }
            }
            Checkbox(selected, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = C.Teal, uncheckedColor = C.Dim, checkmarkColor = C.OnTeal))
        }
        HorizontalDivider(Modifier.padding(horizontal = D.md), color = C.Divider)
    }
}
