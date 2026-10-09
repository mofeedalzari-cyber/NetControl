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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.CardTarget
import com.zarinet.netcontrol.util.UmPackageRow
import com.zarinet.netcontrol.util.UmStatus
import com.zarinet.netcontrol.util.UserManager
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** حالة مشترك يوزر منجر. */
enum class UmState(val label: String, val color: Color) {
    ALL("الكل", C.Cyan), EXPIRED("المنتهية", C.Red), DISABLED("المعطلة", C.Gold),
    USED("المستخدمة", C.Online), UNUSED("غير المستخدمة", C.Purple)
}

data class UmUser(val id: String, val name: String, val password: String, val profile: String, val state: UmState,
                  val disabled: Boolean, val comment: String, val endTime: String)

typealias UmPackage = UmPackageRow

@Composable
fun UserManagerScreen(onBack: () -> Unit, initialTab: Int = 0, printPreview: Pair<VSettings, com.zarinet.netcontrol.util.CardDesign?>? = null) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        var ver by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { ver = try { if (UserManager.isV6()) "RouterOS v6 — /tool user-manager" else "RouterOS v7 — /user-manager" } catch (_: Exception) { "" } }
        TopBar("يوزر منجر", ver.ifBlank { "User Manager" }, onBack)
        SegTabs(listOf("الباقات", "المستخدمين", "طباعة كروت", "بطاقة واحدة", "الدفعات"), tab, Modifier.padding(horizontal = D.screen)) { tab = it }
        Spacer(Modifier.height(D.sm))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> key(0) { SubscribersTab(packagesMode = true) }
                1 -> UmSubscribersTab()
                2 -> VouchersScreen(CardTarget.USERMAN, onBack = null, preview = printPreview)
                4 -> UmBatchesTab()
                else -> SingleCardTab()
            }
        }
    }
}

@Composable
fun SegTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(D.radiusControl)).background(C.Card).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val sel = selected == i
            Box(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 40.dp).clip(RoundedCornerShape(D.radiusSmall))
                    .background(if (sel) C.Teal else Color.Transparent).clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) { Text(label, style = MaterialTheme.typography.labelMedium, color = if (sel) C.OnTeal else C.Dim,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp)) }
        }
    }
}

// ─────────────────────────── المشتركون والباقات ───────────────────────────

@Composable
private fun SubscribersTab(packagesMode: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UmUser>>(emptyList()) }
    var packages by remember { mutableStateOf(com.zarinet.netcontrol.util.ScreenCache.get<List<UmPackage>>("um:pkgs") ?: emptyList()) }
    var pkgCounts by remember { mutableStateOf(com.zarinet.netcontrol.util.ScreenCache.get<Map<String, Int>>("um:pkgCounts") ?: emptyMap()) }
    var loading by remember { mutableStateOf(packages.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(UmState.ALL) }
    val showPackages = packagesMode
    var query by remember { mutableStateOf("") }
    var addPkg by remember { mutableStateOf(false) }
    var editPkg by remember { mutableStateOf<UmPackage?>(null) }
    var deleting by remember { mutableStateOf<UmUser?>(null) }
    var deletingPkg by remember { mutableStateOf<UmPackage?>(null) }

    fun load() {
        loading = if (showPackages) packages.isEmpty() else true; error = null
        scope.launch {
            try {
                if (showPackages) {
                    // صفحة الباقات: تُعرض آخر نسخة فوراً، ثم الباقات (رحلة واحدة)، ثم عدد المشتركين بقراءة خفيفة
                    val sc = com.zarinet.netcontrol.util.ScreenCache
                    packages = UserManager.packages()
                    sc.put("um:pkgs", packages)
                    loading = false
                    runCatching { UserManager.profileCounts() }.getOrNull()?.let { pkgCounts = it; sc.put("um:pkgCounts", it) }
                    return@launch
                }
                users = UserManager.users().map { u ->
                    UmUser(u.id, u.name, u.password, u.profile,
                        when (u.status) { UmStatus.USED -> UmState.USED; UmStatus.EXPIRED -> UmState.EXPIRED; UmStatus.UNUSED -> UmState.UNUSED },
                        u.disabled, u.comment, u.info)
                }
            } catch (e: Exception) {
                error = e.message ?: "خطأ"
            }
            loading = false
        }
    }
    fun act(msg: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block(); com.zarinet.netcontrol.ui.components.toast(ctx, msg, Toast.LENGTH_SHORT).show()
                com.zarinet.netcontrol.util.ScreenCache.invalidate("um:pkgs"); load()
            }
            catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(Unit) {
        // بيانات حديثة (أقل من 20 ثانية) تكفي عند التنقل بين التبويبات بدون طلب جديد
        if (!(showPackages && packages.isNotEmpty() && com.zarinet.netcontrol.util.ScreenCache.fresh("um:pkgs", 20_000L))) load()
    }

    val counts = remember(users) {
        mapOf(UmState.ALL to users.size, UmState.EXPIRED to users.count { it.state == UmState.EXPIRED },
            UmState.DISABLED to users.count { it.disabled }, UmState.USED to users.count { it.state == UmState.USED },
            UmState.UNUSED to users.count { it.state == UmState.UNUSED && !it.disabled })
    }
    val filtered = remember(users, filter, query) {
        users.filter { u ->
            when (filter) {
                UmState.ALL -> true
                UmState.DISABLED -> u.disabled
                UmState.UNUSED -> u.state == UmState.UNUSED && !u.disabled
                else -> u.state == filter
            } && (query.isBlank() || u.name.contains(query, true) || u.profile.contains(query, true) || u.comment.contains(query, true))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.sm)
    ) {
        // إحصائيات
        if (!showPackages) item {
            Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    listOf(UmState.ALL, UmState.USED, UmState.UNUSED).forEach { s ->
                        UmStat(s, counts[s] ?: 0, !showPackages && filter == s, Modifier.weight(1f)) { filter = s }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    listOf(UmState.EXPIRED, UmState.DISABLED).forEach { s ->
                        UmStat(s, counts[s] ?: 0, !showPackages && filter == s, Modifier.weight(1f)) { filter = s }
                    }
                    AppCard(Modifier.weight(1f), padding = PaddingValues(D.sm)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${packages.size}", style = MaterialTheme.typography.titleLarge, color = C.Teal)
                            Text("الباقات", style = MaterialTheme.typography.bodySmall, color = C.Dim)
                        }
                    }
                }
            }
        }
        if (showPackages) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("باقات يوزر منجر", Modifier.weight(1f)) { CountBadge(packages.size, C.Teal) }
                    Spacer(Modifier.width(D.sm))
                    AppButton("إضافة باقة", { addPkg = true }, Modifier.height(40.dp), icon = Icons.Filled.Add)
                }
            }
            item { StateView(loading && packages.isEmpty(), error, !loading && error == null && packages.isEmpty(), "لا توجد باقات", ::load) }
            items(packages, key = { "p" + it.id }) { p ->
                val secs = Fmt.seconds(p.validity)
                PackageCard(
                    p.name, p.price.takeIf { it.isNotBlank() && it != "0" }, C.Gold,
                    listOf(
                        "المدة" to if (p.validity.isBlank() || p.validity == "unlimited" || secs == 0L) "بلا مدة"
                            else if (secs % 86400 == 0L) "${secs / 86400} يوم" else Fmt.durationShort(p.validity),
                        "البيانات" to if (p.transfer > 0) Fmt.bytes(p.transfer) else "مفتوح",
                        "السرعة" to p.rate.ifBlank { "بلا قيود" },
                        "المشتركون" to "${pkgCounts[p.name] ?: users.count { it.profile == p.name }}"
                    ),
                    onEdit = { editPkg = p },
                    onDelete = { deletingPkg = p }
                )
            }
        } else {
            item { SearchField(query, { query = it }, "بحث: الاسم، الباقة...") }
            item {
                Text("${filter.label}: ${filtered.size}", style = MaterialTheme.typography.labelMedium, color = C.Dim)
            }
            item { StateView(loading && users.isEmpty(), error, !loading && error == null && filtered.isEmpty(), "لا يوجد مشتركون", ::load) }
            items(filtered, key = { it.id }) { u ->
                AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md),
                    background = if (u.disabled) C.Card.copy(alpha = 0.6f) else C.Card) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.ConfirmationNumber, u.state.color, 36.dp, 20.dp)
                        Spacer(Modifier.width(D.sm))
                        Column(Modifier.weight(1f)) {
                            Text(u.name, style = MaterialTheme.typography.titleMedium, color = C.Text, maxLines = 1)
                            Text(listOf(u.profile.ifBlank { "بدون باقة" }, u.comment.substringBefore(';')).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Pill(if (u.disabled) "معطل" else u.state.label.removePrefix("ال"), if (u.disabled) C.Gold else u.state.color)
                    }
                    if (u.password.isNotBlank() || u.endTime.isNotBlank()) {
                        Spacer(Modifier.height(D.sm))
                        KvGrid(listOfNotNull(
                            u.password.takeIf { it.isNotBlank() }?.let { "كلمة السر" to it },
                            u.endTime.takeIf { it.isNotBlank() }?.let { it.substringBefore(":") to it.substringAfter(": ") }
                        ))
                    }
                    CardActions(
                        onDelete = { deleting = u }, disabled = u.disabled,
                        onToggle = { act(if (u.disabled) "تم التفعيل" else "تم التعطيل") {
                            UserManager.setDisabled(u.id, !u.disabled)
                        } }
                    )
                }
            }
        }
    }

    editPkg?.let { p ->
        AddPackageDialog(onDismiss = { editPkg = null }, initial = p) { name, days, mb, speed, price ->
            editPkg = null
            act("تم حفظ الباقة") { UserManager.updatePackage(p, name, days, mb, speed, price) }
        }
    }
    if (addPkg) {
        AddPackageDialog(onDismiss = { addPkg = false }) { name, days, mb, speed, price ->
            addPkg = false
            act("تمت إضافة الباقة") { UserManager.addPackage(name, days, mb, speed, price) }
        }
    }
    deleting?.let { u ->
        ConfirmDialog("حذف مشترك", "حذف \"${u.name}\" من يوزر منجر؟", "حذف",
            onConfirm = { act("تم الحذف") { UserManager.removeUser(u.id) } },
            onDismiss = { deleting = null })
    }
    deletingPkg?.let { p ->
        ConfirmDialog("حذف باقة", "حذف الباقة \"${p.name}\" وقيودها؟", "حذف",
            onConfirm = {
                act("تم حذف الباقة") { UserManager.removePackage(p) }
            },
            onDismiss = { deletingPkg = null })
    }
}

@Composable
private fun UmStat(s: UmState, n: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick, border = if (selected) s.color else C.Border,
        background = if (selected) s.color.copy(alpha = 0.12f) else C.Card, padding = PaddingValues(D.sm)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$n", style = MaterialTheme.typography.titleLarge, color = s.color)
            Text(s.label, style = MaterialTheme.typography.bodySmall, color = C.Dim, maxLines = 1)
        }
    }
}

@Composable
fun AddPackageDialog(onDismiss: () -> Unit, initial: UmPackageRow? = null, onSave: (String, Int, Long, String, String) -> Unit) {
    fun rawRate(v: String) = v.takeIf { it.isNotBlank() && it != "0" }?.let { r ->
        r.toLongOrNull()?.let { n -> if (n % 1_000_000 == 0L) "${n / 1_000_000}M" else if (n % 1000 == 0L) "${n / 1000}k" else "$n" } ?: r
    }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var days by remember { mutableStateOf(initial?.let { (Fmt.seconds(it.validity) / 86400).takeIf { d -> d > 0 }?.toString() }.orEmpty()) }
    var mb by remember { mutableStateOf(initial?.let { (it.transfer / 1048576).takeIf { m -> m > 0 }?.toString() }.orEmpty()) }
    var speed by remember { mutableStateOf(initial?.let { listOfNotNull(rawRate(it.rateRx), rawRate(it.rateTx)).joinToString("/") }.orEmpty()) }
    var price by remember { mutableStateOf(initial?.price?.filter(Char::isDigit)?.takeIf { it != "0" }.orEmpty()) }
    FormDialog(if (initial == null) "إضافة باقة يوزر منجر" else "تعديل باقة ${initial.name}", enabled = name.isNotBlank(), onDismiss = onDismiss,
        onConfirm = { onSave(name.trim(), days.toIntOrNull() ?: 0, mb.toLongOrNull() ?: 0, speed.trim(), price.trim()) }) {
        Field(name, { name = it }, "اسم الباقة", placeholder = "مثلاً 15 يوم")
        Field(days, { days = it.filter(Char::isDigit) }, "عدد الأيام", number = true, placeholder = "0 يعني بلا مدة")
        Field(mb, { mb = it.filter(Char::isDigit) }, "عدد الميغات", number = true, placeholder = "0 يعني مفتوح")
        Field(speed, { speed = it }, "السرعة (رفع/تنزيل)", ltr = true, placeholder = "مثال 1M/4M — فارغ يعني بلا قيود")
        Field(price, { price = it.filter(Char::isDigit) }, "السعر (اختياري)", number = true)
    }
}

// ─────────────────────────── بطاقة واحدة ───────────────────────────

@Composable
private fun SingleCardTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rnd = remember { SecureRandom() }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var bind by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var addPkg by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Triple<String, String, String>?>(null) }   // (الكرت، كلمة السر، الفئة)

    created?.let { (card, pw, prof) ->
        CardCreatedDialog(card, pw, prof, onDismiss = { created = null }, onDelete = {
            scope.launch {
                try {
                    UserManager.removeByName(card)
                    com.zarinet.netcontrol.ui.components.toast(ctx, "تم حذف الكرت $card", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                created = null
            }
        })
    }

    if (addPkg) AddPackageDialog(onDismiss = { addPkg = false }) { n, d, mb, sp, pr ->
        addPkg = false
        scope.launch {
            try {
                UserManager.addPackage(n, d, mb, sp, pr)
                profiles = UserManager.profileNames(); profile = n
                com.zarinet.netcontrol.ui.components.toast(ctx, "تمت إضافة الباقة", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }

    fun rand(n: Int) = buildString { repeat(n) { append(rnd.nextInt(10)) } }

    LaunchedEffect(Unit) {
        try {
            profiles = UserManager.profileNames()
            profile = profiles.firstOrNull().orEmpty()
        } catch (e: Exception) { done = "خطأ: ${e.message}" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md)
    ) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(D.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Field(name, { name = it.trim() }, "اسم المستخدم", Modifier.weight(1f), ltr = true)
                    AppButton("توليد", { name = rand(9) }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Casino)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Field(pass, { pass = it }, "كلمة السر (اختياري)", Modifier.weight(1f), ltr = true)
                    AppButton("توليد", { pass = rand(4) }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Casino)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Dropdown("باقة يوزر منجر", profile, profiles, Modifier.weight(1f)) { profile = it }
                    AppButton("باقة", { addPkg = true }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Add)
                }
                Field(comment, { comment = it }, "ملاحظة (اختياري)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ربط عند أول استخدام", style = MaterialTheme.typography.bodyLarge, color = C.Text, modifier = Modifier.weight(1f))
                    Switch(bind, { bind = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal, checkedThumbColor = C.OnTeal))
                }
            }
        }
        AppButton("إضافة البطاقة", {
            busy = true
            scope.launch {
                try {
                    UserManager.addUser(name, pass, profile, comment, bind)
                    created = Triple(name, pass, profile)
                    done = null
                    name = ""; pass = ""
                } catch (e: Exception) { done = "خطأ: ${e.message}" }
                busy = false
            }
        }, Modifier.fillMaxWidth().height(46.dp), icon = Icons.Filled.PersonAdd, enabled = name.isNotBlank() && profile.isNotBlank(), loading = busy)
        done?.let {
            AppCard(Modifier.fillMaxWidth(), border = if (it.startsWith("خطأ")) C.Red else C.Teal) {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = C.Text, textAlign = TextAlign.Start)
            }
        }
    }
}

/** تبويب باقات يوزر منجر (يُستخدم أيضاً في شاشة الباقات الموحدة). */
@Composable
fun UmPackagesTab() = SubscribersTab(packagesMode = true)


/** نص مشاركة الكرت. */
fun cardShareText(card: String, pass: String, profile: String): String = buildString {
    val net = Session.device?.name.orEmpty()
    append("🎫 كرت إنترنت")
    if (net.isNotBlank()) append(" — ").append(net)
    append("\nرقم الكرت: ").append(card)
    if (pass.isNotBlank() && pass != card) append("\nكلمة المرور: ").append(pass)
    if (profile.isNotBlank()) append("\nالفئة: ").append(profile)
}

/** نافذة نجاح إنشاء كرت: مشاركة واتساب، نسخ، حذف. */
@Composable
fun CardCreatedDialog(card: String, pass: String, profile: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val text = cardShareText(card, pass, profile)
    if (confirmDelete) {
        ConfirmDialog("حذف الكرت", "هل تريد حذف الكرت $card؟", "حذف", onConfirm = onDelete, onDismiss = { confirmDelete = false })
        return
    }
    AppDialog("تم إنشاء الكرت", onDismiss = onDismiss, buttons = { AppButton("تم", onDismiss, kind = BtnKind.Ghost) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconBadge(Icons.Filled.CheckCircle, C.Online, 64.dp, 38.dp, circle = true)
        }
        Text("تم انشاء الكرت ${Fmt.ltr(card)} من فئة ${Fmt.ltr(profile)} بنجاح", style = MaterialTheme.typography.titleMedium,
            color = C.Text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (pass.isNotBlank() && pass != card)
            Text("كلمة المرور: ${Fmt.ltr(pass)}", style = MaterialTheme.typography.bodyLarge, color = C.Dim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        AppButton("مشاركة واتساب", {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
            val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
                runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
            }
            val intent = if (pkg != null) send.setPackage(pkg) else android.content.Intent.createChooser(send, "مشاركة الكرت")
            runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, Modifier.fillMaxWidth().height(50.dp), icon = Icons.Filled.Share)
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            AppButton("نسخ", {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("card", text))
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم نسخ بيانات الكرت", Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f).height(48.dp), BtnKind.Secondary, Icons.Filled.ContentCopy)
            AppButton("حذف", { confirmDelete = true }, Modifier.weight(1f).height(48.dp), BtnKind.Danger, Icons.Filled.DeleteOutline)
        }
    }
}
