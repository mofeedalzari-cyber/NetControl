package com.zarinet.netcontrol.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.api.RouterOsClient
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.AppForeground
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.ZariValidity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DashState(
    val name: String = "",
    val host: String = "",
    val identity: String = "",
    val board: String = "",
    val version: String = "",
    val cpu: Int = 0,
    val memPct: Int = 0,
    val memText: String = "",
    val uptime: String = "",
    val rx: Long = 0,
    val tx: Long = 0,
    val users: Int = 0,
    val active: Int = 0,
    val profiles: Int = 0,
    val ppp: Int = 0,
    val pppActive: Int = 0,
    val temp: String = "",
    val volt: String = "",
    val hddPct: Int = 0,
    val hddText: String = "",
    val clock: String = "",
    val online: Boolean = true,
    val wan: String = ""
)

/**
 * ذاكرة اللوحة لكل جهاز: آخر حالة معروضة + المنافذ + أوقات آخر تحديث لكل مجموعة أوامر،
 * حتى تظهر القيم فوراً عند الرجوع للوحة بدل انتظار دورة كاملة.
 */
class DashMemory {
    var dash: DashState? = null
    var interfaces: List<String> = emptyList()
    val last = HashMap<String, Long>()

    companion object {
        private var dev: String? = null
        private var cur = DashMemory()
        fun forDevice(id: String?): DashMemory {
            if (id != dev) { dev = id; cur = DashMemory() }
            return cur
        }
    }
}

class BigTile(val title: String, val value: String?, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

@Composable
fun DashboardScreen(onOpen: (String) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val device = Session.device
    val mem = DashMemory.forDevice(device?.id)
    var dash by remember {
        mutableStateOf(
            mem.dash?.copy(online = Session.client?.isConnected == true)
                ?: DashState(name = device?.name ?: "", host = device?.host ?: "", wan = device?.wanInterface ?: "")
        )
    }
    var interfaces by remember { mutableStateOf(mem.interfaces) }
    var confirmReboot by remember { mutableStateOf(false) }
    var pickWan by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // نظام الصلاحية (سكربت الدخول + جدولة إيقاف الكروت المنتهية)
    var askValidity by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var validityOk by remember { mutableStateOf(false) }
    val askKey = "zari_validity_asked_" + (device?.id ?: "")
    val prefs = remember { ctx.getSharedPreferences("mikroadmin_ui", Context.MODE_PRIVATE) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    fun goOn() { pendingRoute?.let { pendingRoute = null; onOpen(it) } }

    LaunchedEffect(Unit) {
        try {
            if (interfaces.isNotEmpty() && dash.wan.isNotBlank()) return@LaunchedEffect
            val ifs = Session.api().print("/interface", listOf("name", "type"))
            interfaces = ifs.mapNotNull { it["name"] }
            mem.interfaces = interfaces
            if (dash.wan.isBlank()) {
                dash = dash.copy(wan = interfaces.firstOrNull { it.contains("wan", true) }
                    ?: ifs.firstOrNull { it["type"] == "ether" }?.get("name") ?: interfaces.firstOrNull() ?: "")
            }
        } catch (_: Exception) {}
    }

    // تحديث كل ثانيتين بأوامر خفيفة، والأوامر الثقيلة لها فترات أطول (due):
    // المعالج/الذاكرة/التخزين كل دورة ← الحساسات 20ث ← الساعة و PPP النشط 10ث ← المتصلون 6ث/30ث
    // ← سرعة المنفذ من عدّاداته ← الهوية والعدادات البطيئة كل دقيقة.
    val wan = dash.wan
    LaunchedEffect(wan) {
        fun due(key: String, everyMs: Long): Boolean {
            val now = System.currentTimeMillis()
            if (now - (mem.last[key] ?: 0L) < everyMs) return false
            mem.last[key] = now
            return true
        }
        // عند تغيّر منفذ WAN نعيد كل شيء فوراً ما عدا الأوامر الثقيلة
        mem.last.keys.retainAll(setOf("health", "activeList"))
        var lastRx = -1L; var lastTx = -1L; var lastAt = 0L
        while (true) {
            AppForeground.await()   // لا نستعلم والتطبيق في الخلفية
            val started = System.currentTimeMillis()
            try {
                val api = Session.api()
                // كل أوامر الدورة تُرسل دفعة واحدة (Pipelining): رحلة واحدة للراوتر بدل رحلة لكل أمر — فرق كبير عبر السحابة
                val cmds = ArrayList<RouterOsClient.Cmd>()
                val idx = HashMap<String, Int>()
                fun add(k: String, c: RouterOsClient.Cmd) { idx[k] = cmds.size; cmds += c }
                fun count(path: String) = RouterOsClient.Cmd("$path/print", mapOf("count-only" to ""))
                add("res", RouterOsClient.Cmd("/system/resource/print", proplist = listOf("cpu-load", "total-memory", "free-memory", "uptime",
                    "board-name", "version", "free-hdd-space", "total-hdd-space")))
                if (due("health", 20_000)) add("health", RouterOsClient.Cmd("/system/health/print"))
                if (due("clock", 10_000)) {
                    add("clock", RouterOsClient.Cmd("/system/clock/print", proplist = listOf("time", "date")))
                    add("pppActive", count("/ppp/active"))
                }
                // القائمة الكاملة تُحفظ في ActiveCache لتفتح شاشة «الأكتف» فوراً
                if (due("activeList", 30_000)) add("activeList", RouterOsClient.Cmd("/ip/hotspot/active/print", proplist = ActiveCache.PROPS))
                else if (due("activeCount", 6_000)) add("activeCount", count("/ip/hotspot/active"))
                if (wan.isNotBlank()) add("wan", RouterOsClient.Cmd("/interface/print", proplist = listOf("rx-byte", "tx-byte"), queries = listOf("?name=$wan")))
                if (due("slow", 60_000)) {
                    add("identity", RouterOsClient.Cmd("/system/identity/print"))
                    add("profiles", count("/ip/hotspot/user/profile"))
                    add("users", count("/ip/hotspot/user"))
                    add("ppp", count("/ppp/secret"))
                }
                val out = api.batch(cmds)
                fun rows(k: String): List<Map<String, String>>? = idx[k]?.let { out[it].getOrNull()?.rows }
                fun ret(k: String): Int? = idx[k]?.let { out[it].getOrNull()?.done?.get("ret")?.toIntOrNull() }

                val res = out[idx.getValue("res")].getOrThrow().rows.firstOrNull()?.takeIf { it["cpu-load"] != null }
                    ?: api.print("/system/resource").firstOrNull() ?: emptyMap()
                val total = res["total-memory"]?.toLongOrNull() ?: 0
                val free = res["free-memory"]?.toLongOrNull() ?: 0
                var nd = dash.copy(
                    board = res["board-name"] ?: dash.board.ifBlank { "MikroTik" },
                    version = res["version"] ?: dash.version,
                    cpu = res["cpu-load"]?.toIntOrNull() ?: 0,
                    memPct = if (total > 0) ((total - free) * 100 / total).toInt() else 0,
                    memText = if (total > 0) "${Fmt.bytes(total - free)} / ${Fmt.bytes(total)}" else "",
                    uptime = res["uptime"] ?: dash.uptime,
                    online = true
                )
                val hddTotal = res["total-hdd-space"]?.toLongOrNull() ?: 0
                val hddFree = res["free-hdd-space"]?.toLongOrNull() ?: 0
                if (hddTotal > 0) nd = nd.copy(hddPct = ((hddTotal - hddFree) * 100 / hddTotal).toInt(), hddText = Fmt.bytes(hddTotal - hddFree))
                rows("health")?.let { h ->
                    // v6: أعمدة مباشرة، v7: صفوف name/value
                    fun pick(vararg keys: String): String? =
                        h.firstNotNullOfOrNull { r -> keys.firstNotNullOfOrNull { r[it] } }
                            ?: h.firstOrNull { it["name"] in keys }?.get("value")
                    nd = nd.copy(
                        temp = pick("temperature", "cpu-temperature", "board-temperature1", "sfp-temperature") ?: "",
                        volt = pick("voltage", "psu1-voltage") ?: ""
                    )
                }
                rows("clock")?.firstOrNull()?.let { c ->
                    nd = nd.copy(clock = listOfNotNull(c["time"], c["date"]).joinToString("  "))
                }
                ret("pppActive")?.let { nd = nd.copy(pppActive = it) }
                rows("activeList")?.let { r ->
                    ActiveCache.put(device?.id ?: "", r)
                    nd = nd.copy(active = r.size)
                }
                ret("activeCount")?.let { nd = nd.copy(active = it) }
                if (idx.containsKey("identity")) nd = nd.copy(
                    identity = rows("identity")?.firstOrNull()?.get("name") ?: nd.identity,
                    profiles = ret("profiles") ?: nd.profiles,
                    users = ret("users") ?: nd.users,
                    ppp = ret("ppp") ?: nd.ppp
                )
                dash = nd
                if (wan.isNotBlank()) {
                    val row = rows("wan")?.firstOrNull()
                    val rx = row?.get("rx-byte")?.toLongOrNull(); val tx = row?.get("tx-byte")?.toLongOrNull()
                    val now = System.currentTimeMillis()
                    if (rx != null && tx != null) {
                        if (lastRx >= 0 && now > lastAt && rx >= lastRx && tx >= lastTx) {
                            val sec = (now - lastAt) / 1000.0
                            dash = dash.copy(rx = ((rx - lastRx) * 8 / sec).toLong(), tx = ((tx - lastTx) * 8 / sec).toLong())
                        } else if (lastRx < 0) {
                            // أول قراءة: قيمة لحظية مباشرة حتى تتوفر قراءتان
                            val t = runCatching { api.exec("/interface/monitor-traffic", mapOf("interface" to wan, "once" to "")).rows.firstOrNull() }.getOrNull()
                            if (t != null) dash = dash.copy(rx = t["rx-bits-per-second"]?.toLongOrNull() ?: 0, tx = t["tx-bits-per-second"]?.toLongOrNull() ?: 0)
                        }
                        lastRx = rx; lastTx = tx; lastAt = now
                    } else {
                        val t = api.exec("/interface/monitor-traffic", mapOf("interface" to wan, "once" to "")).rows.firstOrNull()
                        dash = dash.copy(rx = t?.get("rx-bits-per-second")?.toLongOrNull() ?: 0, tx = t?.get("tx-bits-per-second")?.toLongOrNull() ?: 0)
                    }
                }
            } catch (e: Exception) {
                // مهلة طلب منتهية (TimeoutCancellation) ليست إلغاءً للشاشة؛ نرمي فقط إن أُلغيت الحلقة فعلاً
                if (e is CancellationException && currentCoroutineContext()[Job]?.isActive != true) throw e
                if (e !is CancellationException) dash = dash.copy(online = false)
            }
            mem.dash = dash
            delay((2000L - (System.currentTimeMillis() - started)).coerceAtLeast(300))
        }
    }

    if (askValidity) {
        AppDialog("نظام الصلاحية", onDismiss = {}, buttons = {
            AppButton("لا", {
                askValidity = false
                prefs.edit().putBoolean(askKey, true).apply()
                goOn()
            }, kind = BtnKind.Ghost)
            AppButton("نعم", {
                installing = true
                scope.launch {
                    try {
                        ZariValidity.install()
                        prefs.edit().putBoolean(askKey, true).apply()
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم تفعيل نظام الصلاحية ✓", Toast.LENGTH_LONG).show()
                        askValidity = false
                        goOn()
                    } catch (e: Exception) {
                        com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show()
                    }
                    installing = false
                }
            }, icon = Icons.Filled.CheckCircle, loading = installing)
        }) { ValidityPromptBody() }
    }
    if (validityOk) {
        AppDialog("نظام الصلاحية", onDismiss = { validityOk = false }, buttons = {
            AppButton("إغلاق", { validityOk = false }, kind = BtnKind.Ghost)
        }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Filled.VerifiedUser, C.Online, 56.dp, 32.dp, circle = true)
                Spacer(Modifier.height(D.sm))
                Text("نظام الصلاحية مفعّل على كل باقات الهوتسبوت ✓", style = MaterialTheme.typography.bodyLarge, color = C.Text,
                    textAlign = TextAlign.Center)
            }
        }
    }

    DashboardContent(
        dash = dash,
        onOpen = { route ->
            if (route == "validity") scope.launch {
                // مفعّل؟ نعرض تأكيداً، وإلا نعرض عرض التفعيل
                val on = runCatching { ZariValidity.isEnabled() }.getOrDefault(false)
                if (on) validityOk = true else askValidity = true
            } else onOpen(route)
        },
        onExit = onExit,
        onPickWan = { pickWan = true },
        onReboot = { confirmReboot = true }
    )

    if (pickWan) {
        FormDialog("منفذ WAN لقياس السرعة", confirmText = "إغلاق", onConfirm = { pickWan = false }, onDismiss = { pickWan = false }) {
            interfaces.forEach { n ->
                val sel = n == dash.wan
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusSmall)).background(if (sel) C.Teal.copy(alpha = 0.15f) else C.CardHi)
                        .clickable {
                            dash = dash.copy(wan = n); pickWan = false
                            device?.let { store.upsert(it.copy(wanInterface = n)); Session.device = it.copy(wanInterface = n) }
                        }.padding(D.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.SettingsEthernet, null, tint = if (sel) C.Teal else C.Dim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(D.sm))
                    Text(n, style = MaterialTheme.typography.bodyLarge, color = if (sel) C.Teal else C.Text)
                }
            }
        }
    }
    if (confirmReboot) {
        ConfirmDialog("إعادة تشغيل الراوتر", "سيتم قطع جميع المستخدمين مؤقتاً حتى يعود الراوتر. هل أنت متأكد؟", "إعادة التشغيل",
            onConfirm = {
                scope.launch {
                    try { Session.api().exec("/system/reboot") } catch (_: Exception) {}
                    com.zarinet.netcontrol.ui.components.toast(ctx, "تم إرسال أمر إعادة التشغيل", Toast.LENGTH_SHORT).show()
                    onExit()
                }
            },
            onDismiss = { confirmReboot = false })
    }
}

@Composable
fun DashboardContent(
    dash: DashState,
    onOpen: (String) -> Unit,
    onExit: () -> Unit,
    onPickWan: () -> Unit,
    onReboot: () -> Unit
) {
    val t = MaterialTheme.typography
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("ميكروتك الشامل", dash.name.ifBlank { null }, onBack = onExit, navIcon = Icons.Filled.SwapHoriz) {
            Box {
                BarAction(Icons.Filled.MoreVert, "المزيد") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = C.CardHi) {
                    DropdownMenuItem(text = { Text("منفذ WAN") }, leadingIcon = { Icon(Icons.Filled.SettingsEthernet, null) },
                        onClick = { menu = false; onPickWan() })
                    DropdownMenuItem(text = { Text("إعادة تشغيل الراوتر") }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                        onClick = { menu = false; onReboot() })
                    val isAdmin by com.zarinet.netcontrol.account.AuthService.isAdmin.collectAsState()
                    DropdownMenuItem(text = { Text(if (isAdmin) "إدارة الاشتراكات" else "الاشتراك ومعرفي") }, leadingIcon = { Icon(Icons.Filled.WorkspacePremium, null) },
                        onClick = { menu = false; onOpen("subscription") })
                    DropdownMenuItem(text = { Text("إعدادات الحساب") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { menu = false; onOpen("settings") })
                    DropdownMenuItem(text = { Text("قطع الاتصال") }, leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                        onClick = { menu = false; onExit() })
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.md)
        ) {
            SubscriptionBanner(onOpen = { onOpen("subscription") }, compact = true)
            // ── اسم الجهاز والحالة ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dash.identity.ifBlank { dash.name.ifBlank { "MikroTik" } }, Modifier.weight(1f), style = t.titleMedium, color = C.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Pill(if (!dash.online) "منقطع" else if (Session.viaCloud) "متصل عبر السحابة" else "متصل", if (dash.online) C.Online else C.Red)
            }
            // ── المعالج • الفولط • الحرارة ──
            Row(Modifier.height(96.dp), horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                GradTile(listOf(Color(0xFF0B2F6E), Color(0xFF1B5FD1)), Modifier.weight(2f)) {
                    Label("المعالج"); Big("${dash.cpu}%", 38)
                }
                GradTile(listOf(Color(0xFF0E8F7A), Color(0xFF19C2A0)), Modifier.weight(1f)) {
                    Label("الفولط"); Big(dash.volt.ifBlank { "—" }.substringBefore('V'), 26)
                }
                GradTile(listOf(Color(0xFFC0392B), Color(0xFFE8663D)), Modifier.weight(1f)) {
                    Label("الحرارة"); Big(dash.temp.ifBlank { "—" }.substringBefore('C'), 26)
                    if (dash.temp.isNotBlank()) Small("مئوية")
                }
            }
            // ── سرعة النت • مدة التشغيل • الموديل ──
            Row(Modifier.height(126.dp), horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                GradTile(listOf(Color(0xFF4A1F7A), Color(0xFF8E44C9)), Modifier.weight(1f).clickable(onClick = onPickWan)) {
                    Label("سرعة النت • " + dash.wan.ifBlank { "WAN" })
                    Row(verticalAlignment = Alignment.Bottom) {
                        Big(Fmt.bps(dash.rx).substringBefore(' '), 32)
                        Spacer(Modifier.width(4.dp))
                        Small("‎" + Fmt.bps(dash.rx).substringAfter(' ', "") + " ↓", 13)
                    }
                    Small("‎↑ " + Fmt.bps(dash.tx), 13)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(D.sm)) {
                    GradTile(listOf(Color(0xFFB7791F), Color(0xFFE0A21B)), Modifier.weight(1f).fillMaxWidth()) {
                        Label("مدة التشغيل"); Small(shortUptime(dash.uptime), 15)
                    }
                    GradTile(listOf(Color(0xFF334155), Color(0xFF52627A)), Modifier.weight(1f).fillMaxWidth()) {
                        Small(dash.board.ifBlank { "MikroTik" }, 14, ltr = true)
                        Small(dash.version.ifBlank { null }?.let { "v$it" } ?: "", 12, ltr = true)
                    }
                }
            }
            // ── التخزين • الذاكرة ──
            Row(Modifier.height(70.dp), horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                BarTile("التخزين (الفلاش)", dash.hddPct, dash.hddText, Modifier.weight(1f))
                BarTile("الذاكرة (RAM)", dash.memPct, dash.memText.substringBefore(" /"), Modifier.weight(1f))
            }
            if (dash.clock.isNotBlank()) GradTile(listOf(Color(0xFF334155), Color(0xFF52627A)), Modifier.fillMaxWidth().height(52.dp)) {
                Small("وقت الشبكة:  ‎${dash.clock}", 16)
            }

            // ── الأقسام الرئيسية ──
            var menuFor by remember { mutableStateOf<String?>(null) }
            val tiles = listOf(
                BigTile("البرودباند", if (dash.ppp >= dash.pppActive) "${dash.pppActive} من ${dash.ppp}" else "${dash.pppActive} متصل",
                    Icons.Filled.SettingsInputComponent, C.Purple) { onOpen("broadband") },
                BigTile("الأكتف", "${dash.active}", Icons.Filled.Devices, C.Blue) { onOpen("active") },
                BigTile("هوتسبوت", "${dash.users}", Icons.Filled.Wifi, C.Teal) { menuFor = "hotspot" },
                BigTile("يوزر منجر", null, Icons.Filled.CardMembership, C.Cyan) { menuFor = "userman" },
                BigTile("أجهزة البث", null, Icons.Filled.SettingsInputAntenna, C.Steel) { onOpen("neighbors") },
                BigTile("المبيعات", null, Icons.Filled.BarChart, C.Green) { onOpen("sales") },
                BigTile("الإضافات", null, Icons.Filled.Extension, C.Teal) { onOpen("tools") },
                BigTile("نسخ / استعادة", null, Icons.Filled.Backup, C.Gold) { onOpen("backup") },
                BigTile("جدار الحماية", null, Icons.Filled.Security, C.Red) { onOpen("firewall/0") },
                BigTile("السجلات", null, Icons.Filled.Description, C.Cyan) { onOpen("resource/log") },
                BigTile("الوصول من الخارج", if (Session.device?.hasCloud == true) "مفعّل" else null, Icons.Filled.Cloud, C.Blue) { onOpen("cloud") },
                BigTile("الواجهات", null, Icons.Filled.SettingsEthernet, C.Blue) { onOpen("resource/interfaces") },
            )
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    row.forEach { BigTileView(it, Modifier.weight(1f)) }
                }
            }
            BigTileView(BigTile("المزيد", null, Icons.Filled.Apps, C.Steel) { onOpen("more") }, Modifier.fillMaxWidth())

            menuFor?.let { m ->
                val items = if (m == "hotspot") HOTSPOT_MENU else listOf(
                    Triple("المشتركون", Icons.Filled.People, "userman/1"), Triple("الباقات", Icons.Filled.Layers, "userman/0"),
                    Triple("طباعة كروت", Icons.Filled.ConfirmationNumber, "userman/2"), Triple("بطاقة واحدة", Icons.Filled.PersonAdd, "userman/3")
                )
                QuickMenu(items, onDismiss = { menuFor = null }) { r -> menuFor = null; onOpen(r) }
            }
        }
    }
}

/** مدة التشغيل مختصرة: «3 يوم 4 س» / «5 س 12 د» / «40 دقيقة». */
private fun shortUptime(u: String): String {
    val s = Fmt.seconds(u)
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return when {
        d > 0 -> "$d يوم $h س"
        h > 0 -> "$h س $m د"
        else -> "$m دقيقة"
    }
}

@Composable
private fun BigTileView(b: BigTile, modifier: Modifier) {
    val shape = RoundedCornerShape(D.radiusControl)
    Column(
        modifier.height(116.dp).clip(shape)
            .background(Brush.linearGradient(listOf(C.Card, b.color.copy(alpha = 0.1f))))
            .border(1.dp, C.Border, shape)
            .clickable(onClick = b.onClick).padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(b.icon, b.color, 46.dp, 28.dp, circle = true)
        Spacer(Modifier.height(4.dp))
        Text(b.title, style = MaterialTheme.typography.titleMedium, color = C.Text, maxLines = 1)
        if (b.value != null) Text(b.value, style = MaterialTheme.typography.titleLarge, color = C.Text, maxLines = 1)
    }
}

@Composable
private fun QuickMenu(items: List<Triple<String, ImageVector, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.navigationBarsPadding()) { QuickMenuItems(items, onPick) }
    }
}

val HOTSPOT_MENU: List<Triple<String, ImageVector, String>> = listOf(
    Triple("المستخدمون", Icons.Filled.People, "hotspot/1"),
    Triple("الباقات", Icons.Filled.Layers, "hotspot/0"),
    Triple("طباعة كروت", Icons.Filled.ConfirmationNumber, "hotspot/2"),
    Triple("بطاقة واحدة", Icons.Filled.PersonAdd, "hotspot/3"),
    Triple("تفعيل صلاحية الهوتسبوت", Icons.Filled.VerifiedUser, "validity")
)

@Composable
fun QuickMenuItems(items: List<Triple<String, ImageVector, String>>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (title, icon, route) ->
            val gold = route == "validity"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.Card)
                    .border(if (gold) 1.5.dp else 0.dp, if (gold) C.Gold else Color.Transparent, RoundedCornerShape(28.dp))
                    .clickable { onPick(route) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(icon, if (gold) C.Gold else C.Teal, 38.dp, 22.dp)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = C.Text)
            }
        }
    }
}

@Composable
private fun GradTile(colors: List<Color>, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(D.radiusControl)).background(Brush.linearGradient(colors))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun Label(s: String) {
    Text(s, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge.copy(lineHeight = 16.sp))
}

@Composable
private fun Big(s: String, sp: Int) {
    Text(s, color = Color.White, fontSize = sp.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        style = MaterialTheme.typography.headlineLarge)
}

/** نص صغير أبيض؛ ltr = نص إنجليزي (موديل/إصدار) يُعرض من اليسار لليمين ومحاذى لليمين. */
@Composable
fun Small(s: String, sp: Int = 12, ltr: Boolean = false) {
    Text(
        s, if (ltr) Modifier.fillMaxWidth() else Modifier, color = Color.White, fontSize = sp.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium.copy(
            textAlign = if (ltr) TextAlign.Right else TextAlign.Start,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
            lineHeight = (sp * 1.25f).sp
        )
    )
}

@Composable
private fun BarTile(label: String, pct: Int, text: String, modifier: Modifier) {
    val color = Color(if (pct >= 85) 0xFFFF6B6B else if (pct >= 60) 0xFFF5B82E else 0xFF3EE0C0)
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(D.radiusControl))
            .background(Brush.linearGradient(listOf(Color(0xFF0F3B4C), Color(0xFF1C6273))))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row { Label(label); Spacer(Modifier.weight(1f)); Text("$pct%", style = MaterialTheme.typography.titleSmall, color = Color.White) }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            color = color, trackColor = Color.White.copy(alpha = 0.35f), gapSize = 0.dp, drawStopIndicator = {}
        )
        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun MeterTile(label: String, value: String, progress: Float, icon: ImageVector, modifier: Modifier) {
    val color = when { progress >= 0.85f -> C.Red; progress >= 0.6f -> C.Gold; else -> C.Online }
    Column(modifier.clip(RoundedCornerShape(D.radiusControl)).background(C.CardHi).padding(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = C.Dim, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(D.sm))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = C.Field, drawStopIndicator = {}, gapSize = 0.dp
        )
    }
}

@Composable
private fun SpeedTile(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(D.radiusControl)).background(C.CardHi).padding(D.md), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 32.dp, 18.dp, circle = true)
        Spacer(Modifier.width(D.sm))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = C.Dim)
            Text(value, style = MaterialTheme.typography.titleSmall, color = C.Text, maxLines = 1)
        }
    }
}

@Composable
private fun StatTile(n: Int, label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick, padding = PaddingValues(vertical = D.md, horizontal = D.sm)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Text("$n", style = MaterialTheme.typography.headlineMedium, color = C.Text)
            Text(label, style = MaterialTheme.typography.bodySmall, color = C.Dim, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(D.radiusControl)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 82.dp).clip(shape).background(C.Card).border(1.dp, C.Border, shape)
                .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = D.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            IconBadge(t.icon, t.color, 34.dp, 20.dp)
            Spacer(Modifier.height(6.dp))
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = C.Text, textAlign = TextAlign.Center, softWrap = true)
        }
        if (t.pro) {
            Text("PRO", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2A1A00),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(Color(0xFFF2C14E), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** «المزيد»: بقية أقسام الشبكة والنظام التي ليست في اللوحة الرئيسية. */
@Composable
fun MoreScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    // موجودة كبلاطات في اللوحة الرئيسية
    val onDash = setOf("cloud", "neighbors", "firewall/0", "resource/log", "broadband", "resource/interfaces", "backup", "tools", "sales")
    Column(Modifier.fillMaxSize()) {
        TopBar("المزيد", "الشبكة والنظام", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            Sections.groups.filter { it.first in setOf("الشبكة", "النظام") }.forEach { (group, all) ->
                val tiles = all.filterNot { it.route in onDash }
                if (tiles.isEmpty()) return@forEach
                SectionTitle(group)
                tiles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(D.sm), modifier = Modifier.height(IntrinsicSize.Max)) {
                        row.forEach { tile -> TileView(tile, Modifier.weight(1f).fillMaxHeight()) { onOpen(tile.route) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ValidityPromptBody() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(Icons.Filled.VerifiedUser, C.Gold, 56.dp, 32.dp, circle = true)
        Spacer(Modifier.height(D.sm))
        Text("إن نظام الصلاحية غير مفعل على شبكتك وهو ضروري للتحكم ببطاقات الهوتسبوت، هل تريد أن نفعله من أجلك؟",
            style = MaterialTheme.typography.bodyLarge, color = C.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(D.sm))
        Text("سيُضاف سكربت الدخول لكل باقات الهوتسبوت (تسجيل أول دخول وربط الجهاز) وجدولة «Zari Net - hotspot card expiry» لإيقاف الكروت المنتهية — بدون أي تنبيهات خارجية.",
            style = MaterialTheme.typography.bodySmall, color = C.Dim, textAlign = TextAlign.Center)
    }
}

/** يسأل مرة واحدة لكل جهاز: إن لم يكن نظام الصلاحية مفعّلاً على الراوتر يعرض تفعيله. */
@Composable
fun ValidityGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = "zari_validity_asked_" + (Session.device?.id ?: "")
    val prefs = remember { ctx.getSharedPreferences("mikroadmin_ui", Context.MODE_PRIVATE) }
    var ask by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (prefs.getBoolean(key, false)) return@LaunchedEffect
        // عند تعذّر الفحص نفترض أنه مفعّل حتى لا نزعج المستخدم
        val on = runCatching { ZariValidity.isEnabled() }.getOrDefault(true)
        if (!on) ask = true else prefs.edit().putBoolean(key, true).apply()
    }
    if (ask) {
        AppDialog("نظام الصلاحية", onDismiss = {}, buttons = {
            AppButton("لا", { ask = false; prefs.edit().putBoolean(key, true).apply() }, kind = BtnKind.Ghost)
            AppButton("نعم", {
                installing = true
                scope.launch {
                    try {
                        ZariValidity.install()
                        prefs.edit().putBoolean(key, true).apply()
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم تفعيل نظام الصلاحية ✓", Toast.LENGTH_LONG).show()
                        ask = false
                    } catch (e: Exception) {
                        com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show()
                    }
                    installing = false
                }
            }, icon = Icons.Filled.CheckCircle, loading = installing)
        }) { ValidityPromptBody() }
    }
}
