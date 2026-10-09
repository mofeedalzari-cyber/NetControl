package com.zarinet.netcontrol.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.UserManager
import com.zarinet.netcontrol.util.BlockedDev
import com.zarinet.netcontrol.util.ShareBlock
import com.zarinet.netcontrol.util.loadBlocked
import com.zarinet.netcontrol.util.unblock
import kotlinx.coroutines.launch

/** الإضافات: مسح جلسات يوزر منجر، كلمة سر يوزر منجر، فتح/إغلاق الهوتسبوت. */
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val scope = rememberCoroutineScope()
    val devKey = "hs_open_" + (Session.device?.id ?: "")

    var servers by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var sessionsCount by remember { mutableStateOf<Int?>(null) }
    var umVer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }   // clearSessions | password | openHotspot | closeHotspot
    var refresh by remember { mutableIntStateOf(0) }
    // الكيوز: (البسيطة الثابتة، البسيطة الديناميكية، المعطلة منها، شجرة الكيوز)
    var qSimple by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var qTree by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var withTree by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<List<BlockedDev>>(emptyList()) }
    // حظر مشاركة الإنترنت: القواعد الحالية + الواجهات المتاحة
    var shareRules by remember { mutableStateOf<List<ShareBlock.Rule>>(emptyList()) }
    var ifaces by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(refresh) {
        val api = runCatching { Session.api() }.getOrNull() ?: return@LaunchedEffect
        servers = runCatching { api.print("/ip/hotspot", listOf(".id", "name", "interface", "disabled")) }.getOrDefault(emptyList())
        blocked = runCatching { loadBlocked() }.getOrDefault(emptyList())
        shareRules = runCatching { ShareBlock.rules(api) }.getOrDefault(emptyList())
        ifaces = runCatching { ShareBlock.interfaces(api) }.getOrDefault(emptyList())
        qSimple = runCatching { api.print("/queue/simple", listOf(".id", "name", "dynamic", "disabled")) }.getOrDefault(emptyList())
        qTree = runCatching { api.print("/queue/tree", listOf(".id", "name", "dynamic", "disabled")) }.getOrDefault(emptyList())
        runCatching {
            val v6 = UserManager.isV6()
            umVer = if (v6) "v6" else "v7"
            sessionsCount = api.count(if (v6) "/tool/user-manager/session" else "/user-manager/session")
        }.onFailure { umVer = ""; sessionsCount = null }
    }

    fun run(label: String, done: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            try { block(); com.zarinet.netcontrol.ui.components.toast(ctx, done, Toast.LENGTH_LONG).show(); refresh++ }
            catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }

    val enabledServers = servers.filter { it["disabled"] != "true" }
    val openedByApp = store.getString(devKey, "").split(",").filter { it.isNotBlank() }
    val hotspotOpen = servers.isNotEmpty() && enabledServers.isEmpty()

    Column(Modifier.fillMaxSize()) {
        TopBar("الإضافات", "أدوات سريعة للشبكة", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث") { refresh++ }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.md)
        ) {
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
                    Spacer(Modifier.width(D.sm)); Text(it, style = MaterialTheme.typography.bodyMedium, color = C.Dim)
                }
            }

            // ── فتح / إغلاق الهوتسبوت ──
            ToolCard(
                icon = if (hotspotOpen) Icons.Filled.LockOpen else Icons.Filled.Wifi,
                color = if (hotspotOpen) C.Red else C.Green,
                title = if (hotspotOpen) "الهوتسبوت معطّل — الشبكة مفتوحة" else "فتح الهوتسبوت (تعطيل)",
                desc = when {
                    servers.isEmpty() -> "لا توجد سيرفرات هوتسبوت على هذا الراوتر."
                    hotspotOpen -> "كل سيرفرات الهوتسبوت معطلة والزبائن يتصفحون بدون كروت. أعد تفعيلها لإرجاع صفحة الدخول."
                    else -> "يعطّل كل سيرفرات الهوتسبوت (${enabledServers.size}) ويفتح الشبكة لجميع الزبائن بدون كروت."
                },
                extra = if (servers.isNotEmpty()) servers.joinToString(" • ") { "${it["name"]} (${it["interface"]})${if (it["disabled"] == "true") " ✕" else " ✓"}" } else null
            ) {
                if (hotspotOpen) AppButton("إعادة تفعيل الهوتسبوت", { dialog = "closeHotspot" }, Modifier.fillMaxWidth(), icon = Icons.Filled.Lock, enabled = busy == null)
                else AppButton("فتح الهوتسبوت", { dialog = "openHotspot" }, Modifier.fillMaxWidth(), BtnKind.Danger, Icons.Filled.LockOpen,
                    enabled = busy == null && enabledServers.isNotEmpty())
            }

            // ── الأجهزة المحظورة ──
            ToolCard(icon = Icons.Filled.Block, color = C.Red, title = "الأجهزة المحظورة (${blocked.size})") {
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    AppButton("عرض", { onOpen("blocked") }, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.List)
                    AppButton("تنظيف (${blocked.size})", { dialog = "clearBlocked" }, Modifier.weight(1f), BtnKind.Danger,
                        Icons.Filled.CleaningServices, enabled = busy == null && blocked.isNotEmpty())
                }
            }

            // ── حظر جهاز يدوي ──
            ToolCard(icon = Icons.Filled.PhonelinkErase, color = C.Red, title = "حظر جهاز يدوي بالماك") {
                AppButton("حظر جهاز", { dialog = "blockMac" }, Modifier.fillMaxWidth(), BtnKind.Danger, Icons.Filled.Block, enabled = busy == null)
            }

            // ── حظر مشاركة الإنترنت (Tethering) ──
            ToolCard(icon = Icons.Filled.WifiTethering, color = C.Purple, title = "حظر مشاركة الإنترنت") {
                ShareBlockBody(shareRules, busy == null, { dialog = "shareBlock" }) { r ->
                    run("جاري إلغاء الحظر...", "تم إلغاء حظر المشاركة على ${r.iface}") { ShareBlock.unblock(Session.api(), r.id) }
                }
            }

            // ── تقسيم السرعة حسب Active ──
            ToolCard(icon = Icons.Filled.Speed, color = C.Teal, title = "تقسيم السرعة حسب Active") {
                FairSpeedPanel(refresh, onChanged = { refresh++ })
            }

            // ── فتح السرعات (الكيوز) ──
            val qStatic = qSimple.filter { it["dynamic"] != "true" }
            val qDyn = qSimple.size - qStatic.size
            val qTreeStatic = qTree.filter { it["dynamic"] != "true" }
            val qDisabled = qStatic.count { it["disabled"] == "true" } + qTreeStatic.count { it["disabled"] == "true" }
            ToolCard(
                icon = Icons.Filled.Speed, color = C.Cyan, title = "فتح السرعات (مسح الكيوز)",
                desc = "يزيل تحديد السرعة بحذف الكيوز الثابتة أو تعطيلها مؤقتاً. الكيوز الديناميكية (من الهوتسبوت وPPP) يعيد الراوتر إنشاءها حسب سرعة الباقة.",
                extra = "كيوز ثابتة: ${qStatic.size} • ديناميكية: $qDyn • شجرة الكيوز: ${qTreeStatic.size}" +
                    if (qDisabled > 0) " • معطلة الآن: $qDisabled" else ""
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    AppButton("تعطيل مؤقت", { dialog = "disableQueues" }, Modifier.weight(1f), BtnKind.Warning, Icons.Filled.PauseCircle,
                        enabled = busy == null && (qStatic.any { it["disabled"] != "true" } || qTreeStatic.any { it["disabled"] != "true" }))
                    AppButton("مسح الكيوز", { dialog = "clearQueues" }, Modifier.weight(1f), BtnKind.Danger, Icons.Filled.DeleteSweep,
                        enabled = busy == null && (qStatic.isNotEmpty() || qTreeStatic.isNotEmpty()))
                }
                if (qDisabled > 0) {
                    Spacer(Modifier.height(D.sm))
                    AppButton("إعادة تفعيل الكيوز المعطلة ($qDisabled)", {
                        run("جاري التفعيل...", "تم إعادة تفعيل الكيوز") {
                            val api = Session.api()
                            qStatic.filter { it["disabled"] == "true" }.mapNotNull { it[".id"] }.chunked(100)
                                .forEach { api.exec("/queue/simple/enable", mapOf(".id" to it.joinToString(","))) }
                            qTreeStatic.filter { it["disabled"] == "true" }.mapNotNull { it[".id"] }.chunked(100)
                                .forEach { api.exec("/queue/tree/enable", mapOf(".id" to it.joinToString(","))) }
                        }
                    }, Modifier.fillMaxWidth(), BtnKind.Secondary, Icons.Filled.PlayArrow, enabled = busy == null)
                }
            }

            // ── مسح جلسات يوزر منجر ──
            ToolCard(
                icon = Icons.Filled.DeleteSweep, color = C.Gold, title = "مسح جلسات اليوزر منجر",
                desc = if (umVer.isBlank()) "يوزر منجر غير متاح على هذا الراوتر."
                else "يحذف سجل الجلسات القديمة من يوزر منجر ($umVer) لتخفيف قاعدة البيانات وتسريع الراوتر. لا يحذف المشتركين ولا الباقات.",
                extra = sessionsCount?.let { "عدد الجلسات الحالي: $it" }
            ) {
                AppButton("مسح الجلسات", { dialog = "clearSessions" }, Modifier.fillMaxWidth(), BtnKind.Warning, Icons.Filled.DeleteSweep,
                    enabled = busy == null && umVer.isNotBlank() && (sessionsCount ?: 0) > 0)
            }

            // ── كلمة سر يوزر منجر ──
            ToolCard(
                icon = Icons.Filled.Password, color = C.Purple, title = "كلمة سر يوزر منجر",
                desc = when (umVer) {
                    "v6" -> "تغيير كلمة سر حساب الدخول للوحة يوزر منجر (Customer)."
                    "v7" -> "في v7 تُستخدم حسابات الراوتر للدخول إلى لوحة يوزر منجر — غيّر كلمة سر حساب الراوتر."
                    else -> "يوزر منجر غير متاح على هذا الراوتر."
                }
            ) {
                AppButton("تغيير كلمة السر", { dialog = "password" }, Modifier.fillMaxWidth(), BtnKind.Secondary, Icons.Filled.Key,
                    enabled = busy == null && umVer.isNotBlank())
            }
        }
    }

    when (dialog) {
        "openHotspot" -> AppDialog("فتح الهوتسبوت", onDismiss = { dialog = null }, buttons = {
            AppButton("لا", { dialog = null }, kind = BtnKind.Ghost)
            AppButton("نعم", {
                dialog = null
                val names = enabledServers.mapNotNull { it["name"] }
                run("جاري تعطيل ${names.size} سيرفر...", "تم تعطيل الهوتسبوت وفتح الشبكة لجميع الزبائن") {
                    val api = Session.api()
                    enabledServers.forEach { api.exec("/ip/hotspot/disable", mapOf(".id" to it[".id"]!!)) }
                    // نحفظ السيرفرات التي عطّلناها لنعيد تفعيلها هي فقط
                    store.putString(devKey, names.joinToString(","))
                }
            }, kind = BtnKind.Danger)
        }) {
            Text("هل تريد تعطيل الهوتسبوت وفتح الشبكة لجميع الزبائن؟", style = MaterialTheme.typography.titleMedium, color = C.Text)
            Text("سيتم تعطيل ${enabledServers.size} سيرفر: ${enabledServers.joinToString("، ") { it["name"].orEmpty() }}",
                style = MaterialTheme.typography.bodySmall, color = C.Dim)
        }
        "closeHotspot" -> ConfirmDialog("إعادة تفعيل الهوتسبوت",
            "سيعود طلب الكرت لجميع الزبائن. سيُعاد تفعيل: " + (openedByApp.ifEmpty { servers.mapNotNull { it["name"] } }).joinToString("، "),
            "تفعيل", danger = false,
            onConfirm = {
                run("جاري التفعيل...", "تم تفعيل الهوتسبوت") {
                    val api = Session.api()
                    val targets = servers.filter { s -> openedByApp.isEmpty() || s["name"] in openedByApp }
                    targets.forEach { api.exec("/ip/hotspot/enable", mapOf(".id" to it[".id"]!!)) }
                    store.putString(devKey, "")
                }
            }, onDismiss = { dialog = null })
        "clearQueues", "disableQueues" -> {
            val del = dialog == "clearQueues"
            val simple = qSimple.filter { it["dynamic"] != "true" && (del || it["disabled"] != "true") }
            val tree = qTree.filter { it["dynamic"] != "true" && (del || it["disabled"] != "true") }
            AppDialog(if (del) "مسح الكيوز" else "تعطيل الكيوز مؤقتاً", onDismiss = { dialog = null }, buttons = {
                AppButton("لا", { dialog = null }, kind = BtnKind.Ghost)
                AppButton("نعم", {
                    dialog = null
                    val action = if (del) "remove" else "disable"
                    run(if (del) "جاري مسح الكيوز..." else "جاري التعطيل...", if (del) "تم مسح الكيوز وفتح السرعات" else "تم تعطيل الكيوز وفتح السرعات") {
                        val api = Session.api()
                        simple.mapNotNull { it[".id"] }.chunked(100).forEach { api.exec("/queue/simple/$action", mapOf(".id" to it.joinToString(","))) }
                        if (withTree) tree.mapNotNull { it[".id"] }.chunked(100).forEach { api.exec("/queue/tree/$action", mapOf(".id" to it.joinToString(","))) }
                    }
                }, kind = if (del) BtnKind.Danger else BtnKind.Warning)
            }) {
                Text(if (del) "هل تريد حذف الكيوز وفتح السرعات لجميع الزبائن؟" else "هل تريد تعطيل الكيوز مؤقتاً وفتح السرعات؟",
                    style = MaterialTheme.typography.titleMedium, color = C.Text)
                Text("Simple Queues الثابتة: ${simple.size}", style = MaterialTheme.typography.bodyMedium, color = C.Dim)
                if (tree.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تضمين Queue Tree (${tree.size})", style = MaterialTheme.typography.bodyMedium, color = C.Text, modifier = Modifier.weight(1f))
                    Switch(withTree, { withTree = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal, checkedThumbColor = C.OnTeal))
                }
                Text(if (del) "لا يمكن التراجع عن الحذف — يُنصح بعمل نسخة احتياطية أولاً من قسم نسخ/استعادة."
                    else "يمكنك إعادة تفعيلها لاحقاً من نفس المكان.",
                    style = MaterialTheme.typography.bodySmall, color = if (del) C.Gold else C.Dim)
            }
        }
        "shareBlock" -> ShareBlockDialog(ifaces.filter { i -> shareRules.none { it.iface == i } }, onDismiss = { dialog = null }) { iface ->
            dialog = null
            run("جاري إضافة القاعدة...", "تم حظر مشاركة الإنترنت على $iface") { ShareBlock.block(Session.api(), iface) }
        }
        "blockMac" -> BlockMacDialog(onDismiss = { dialog = null }, onDone = { refresh++ })
        "clearBlocked" -> ConfirmDialog("تنظيف الأجهزة المحظورة",
            "هل تريد إلغاء حظر ${blocked.size} جهاز؟\n" + blocked.groupingBy { it.reason }.eachCount().entries.joinToString("، ") { "${it.key}: ${it.value}" },
            "تنظيف", onConfirm = {
                val l = blocked
                run("جاري التنظيف...", "تم إلغاء حظر ${l.size} جهاز") { unblock(l) }
            }, onDismiss = { dialog = null })
        "clearSessions" -> ConfirmDialog("مسح جلسات اليوزر منجر",
            "سيتم حذف ${sessionsCount ?: 0} جلسة من سجل يوزر منجر. المشتركون والباقات لا تتأثر. متابعة؟", "مسح",
            onConfirm = {
                run("جاري مسح الجلسات...", "تم مسح جلسات اليوزر منجر") {
                    val api = Session.api()
                    val path = if (UserManager.isV6()) "/tool/user-manager/session" else "/user-manager/session"
                    val ids = api.print(path, listOf(".id")).mapNotNull { it[".id"] }
                    var done = 0
                    ids.chunked(200).forEach { ch ->
                        api.exec("$path/remove", mapOf(".id" to ch.joinToString(",")))
                        done += ch.size; busy = "جاري مسح الجلسات: $done / ${ids.size}"
                    }
                }
            }, onDismiss = { dialog = null })
        "password" -> UmPasswordDialog(onDismiss = { dialog = null }) { account, pass ->
            dialog = null
            run("جاري تغيير كلمة السر...", "تم تغيير كلمة السر لـ $account") {
                val api = Session.api()
                if (UserManager.isV6()) {
                    val id = api.print("/tool/user-manager/customer", listOf(".id", "login"), listOf("?login=$account")).firstOrNull()?.get(".id")
                        ?: throw IllegalStateException("الحساب غير موجود")
                    api.exec("/tool/user-manager/customer/set", mapOf(".id" to id, "password" to pass))
                } else {
                    val id = api.print("/user", listOf(".id", "name"), listOf("?name=$account")).firstOrNull()?.get(".id")
                        ?: throw IllegalStateException("الحساب غير موجود")
                    api.exec("/user/set", mapOf(".id" to id, "password" to pass))
                    // إن كان هو حساب اتصال التطبيق نحدّث الجهاز المحفوظ حتى لا ينقطع الاتصال لاحقاً
                    Session.device?.takeIf { it.user == account }?.let { d ->
                        val nd = d.copy(password = pass); store.upsert(nd); Session.device = nd
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(icon: ImageVector, color: Color, title: String, desc: String = "", extra: String? = null, action: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 48.dp, 26.dp)
            Spacer(Modifier.width(D.md))
            Text(title, style = MaterialTheme.typography.titleMedium, color = C.Text, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(D.md))
        action()
    }
}

@Composable
private fun UmPasswordDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var accounts by remember { mutableStateOf<List<String>>(emptyList()) }
    var account by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        accounts = runCatching {
            if (UserManager.isV6()) Session.api().print("/tool/user-manager/customer", listOf("login")).mapNotNull { it["login"] }
            else Session.api().print("/user", listOf("name")).mapNotNull { it["name"] }
        }.getOrDefault(emptyList())
        account = accounts.firstOrNull { it == "admin" } ?: accounts.firstOrNull().orEmpty()
    }
    val mismatch = pass2.isNotEmpty() && pass != pass2
    FormDialog("كلمة سر يوزر منجر", confirmText = "حفظ", enabled = account.isNotBlank() && pass.isNotBlank() && pass == pass2,
        onDismiss = onDismiss, onConfirm = { onSave(account, pass) }) {
        Dropdown("الحساب", account, accounts) { account = it }
        Field(pass, { pass = it }, "كلمة السر الجديدة", password = true)
        Field(pass2, { pass2 = it }, "تأكيد كلمة السر", password = true)
        if (mismatch) Text("كلمتا السر غير متطابقتين", style = MaterialTheme.typography.bodySmall, color = C.Red)
    }
}

/** محتوى بطاقة حظر مشاركة الإنترنت: الشرح + القواعد الحالية (مع إلغاء) + زر إضافة. */
@Composable
fun ShareBlockBody(rules: List<ShareBlock.Rule>, enabled: Boolean, onAdd: () -> Unit, onRemove: (ShareBlock.Rule) -> Unit) {
    Text("يمنع المشترك من مشاركة الإنترنت لأجهزة أخرى عبر نقطة اتصال الجوال أو راوتر خلفه. الجهاز المتصل مباشرة يعمل طبيعياً.",
        style = MaterialTheme.typography.bodySmall, color = C.Dim)
    Spacer(Modifier.height(D.sm))
    rules.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Block, null, Modifier.size(18.dp), tint = C.Red)
            Spacer(Modifier.width(D.sm))
            Text("محظور على: ${r.iface}", style = MaterialTheme.typography.bodyMedium, color = C.Text, modifier = Modifier.weight(1f))
            TextButton({ onRemove(r) }, enabled = enabled) { Text("إلغاء", color = C.Red) }
        }
    }
    AppButton("حظر المشاركة", onAdd, Modifier.fillMaxWidth(), BtnKind.Danger, Icons.Filled.Block, enabled = enabled)
}

/** اختيار الواجهة لإضافة قاعدة حظر المشاركة. */
@Composable
fun ShareBlockDialog(options: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var iface by remember { mutableStateOf(options.firstOrNull().orEmpty()) }
    FormDialog("حظر مشاركة الإنترنت", confirmText = "حظر", enabled = iface.isNotBlank(),
        onDismiss = onDismiss, onConfirm = { onConfirm(iface) }) {
        Text("اختر الواجهة التي يتصل منها المشتركون (مثل بريدج الهوتسبوت):", style = MaterialTheme.typography.bodyMedium, color = C.Text)
        if (options.isEmpty()) Text("لا توجد واجهات متاحة (أو كلها محظورة مسبقاً).", style = MaterialTheme.typography.bodySmall, color = C.Gold)
        else Dropdown("الواجهة", iface, options) { iface = it }
        Text("ستُضاف القاعدة إلى Mangle في جدار الحماية تلقائياً.", style = MaterialTheme.typography.bodySmall, color = C.Dim)
    }
}
