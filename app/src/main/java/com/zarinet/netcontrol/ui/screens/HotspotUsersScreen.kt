package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.RosTime
import com.zarinet.netcontrol.util.ScreenCache
import com.zarinet.netcontrol.util.ZariValidity
import com.zarinet.netcontrol.util.UmStatus
import com.zarinet.netcontrol.util.UmUserRow
import kotlinx.coroutines.launch

private val USER_PROPS = listOf(".id", "name", "password", "profile", "limit-uptime", "limit-bytes-total",
    "uptime", "bytes-in", "bytes-out", "disabled", "comment", "email")

const val ALL = "الكل"

/** يحوّل مستخدمي الهوتسبوت لنفس نموذج قائمة يوزر منجر (نفس التصميم والألوان). */
suspend fun loadHotspotRows(): Pair<List<UmUserRow>, List<Map<String, String>>> {
    val api = Session.api()
    val now = System.currentTimeMillis()
    // القراءات الثلاث في رحلة واحدة (Pipelining) — أسرع كثيراً من خارج الشبكة
    val res = api.batch(listOf(
        com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/hotspot/user/print", proplist = USER_PROPS),
        com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/hotspot/active/print", proplist = listOf("user")),
        com.zarinet.netcontrol.api.RouterOsClient.Cmd("/system/scheduler/print", proplist = listOf("name", "next-run"))
    ))
    val raw = res[0].getOrThrow().rows.filter { !it["name"].orEmpty().startsWith("default-trial") }
    val online = (res[1].getOrNull()?.rows ?: emptyList()).mapNotNull { it["user"] }.toSet()
    // مؤقتات الصلاحية القديمة: exp-<اسم الكرت>
    val expiry = (res[2].getOrNull()?.rows ?: emptyList())
        .filter { it["name"].orEmpty().startsWith("exp-") }
        .mapNotNull { s -> RosTime.parse(s["next-run"], now)?.let { s["name"]!!.removePrefix("exp-") to it } }.toMap()
    val rows = raw.map { u ->
        val name = u["name"].orEmpty()
        val up = Fmt.seconds(u["uptime"])
        val limit = Fmt.seconds(u["limit-uptime"])
        val bytes = (u["bytes-in"]?.toLongOrNull() ?: 0) + (u["bytes-out"]?.toLongOrNull() ?: 0)
        val limitBytes = u["limit-bytes-total"]?.toLongOrNull() ?: 0
        val expired = (limit > 0 && up >= limit) || (limitBytes > 0 && bytes >= limitBytes)
        val st = when { expired -> UmStatus.EXPIRED; up > 0 || bytes > 0 -> UmStatus.USED; else -> UmStatus.UNUSED }
        val isOnline = name in online
        val comment = u["comment"].orEmpty()
        // صلاحية الكرت المكتوبة في الملاحظة/البريد (ZariValidity) لها الأولوية
        val validity = ZariValidity.remainingMs(comment, u["email"].orEmpty(), now)
        val remaining = when {
            st != UmStatus.USED -> null
            validity != null -> validity
            expiry[name] != null -> (expiry[name]!! - now).coerceAtLeast(0)
            limit > 0 -> (limit - up) * 1000
            else -> null
        }
        val info = if (st != UmStatus.UNUSED && !isOnline && up > 0) "استخدم ${Fmt.durationShort(u["uptime"])}" else ""
        val first = ZariValidity.firstLogin(comment, now)
        val expiresAt = first?.let { f ->
            ZariValidity.validityHours(u["email"].orEmpty()).takeIf { it > 0 }?.let { f + it * 3_600_000 }
        }
        UmUserRow(u[".id"].orEmpty(), name, u["password"].orEmpty(), u["profile"].orEmpty(), st, u["disabled"] == "true",
            ZariValidity.body(comment), info, lastSeen = null, online = isOnline, usedBytes = bytes, remainingMs = remaining,
            firstLogin = first, expiresAt = expiresAt)
    }
    return rows to raw
}

@Composable
fun HotspotUsersScreen(onBack: () -> Unit, embedded: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // عرض فوري لآخر قائمة محفوظة ثم تحديثها في الخلفية
    val cache = ScreenCache
    var rows by remember { mutableStateOf(cache.get<List<UmUserRow>>("hs:rows") ?: emptyList()) }
    var raw by remember { mutableStateOf(cache.get<Map<String, Map<String, String>>>("hs:raw") ?: emptyMap()) }
    var profiles by remember { mutableStateOf(cache.get<List<String>>("hs:profiles") ?: emptyList()) }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<Map<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, List<UmUserRow>>?>(null) }
    var details by remember { mutableStateOf<UmUserRow?>(null) }
    var menu by remember { mutableStateOf<UmUserRow?>(null) }
    var created by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    /** [force]: تجاهل الذاكرة المؤقتة. [quick]: عند فتح الشاشة تُعتبر القائمة حديثة لـ 3 ثوانٍ فقط. */
    fun load(force: Boolean = false, quick: Boolean = false) {
        if (!force && cache.fresh("hs:rows", if (quick) 3000L else 30_000L)) { loading = false; return }
        if (force) Session.client?.invalidateCache()
        loading = rows.isEmpty(); error = null
        scope.launch {
            try {
                val (r, m) = loadHotspotRows()
                rows = r; raw = m.associateBy { it[".id"].orEmpty() }
                if (profiles.isEmpty() || force)
                    profiles = Session.api().print("/ip/hotspot/user/profile", listOf("name")).mapNotNull { it["name"] }
                cache.put("hs:rows", rows); cache.put("hs:raw", raw); cache.put("hs:profiles", profiles)
            } catch (e: Exception) { if (rows.isEmpty()) error = e.message ?: "خطأ" }
            loading = false
        }
    }
    fun bulk(action: String, list: List<UmUserRow>) {
        val label = when (action) { "remove" -> "حذف"; "disable" -> "تعطيل"; "enable" -> "تفعيل"; else -> "تصفير" }
        busy = "جاري $label ${list.size} كرت..."
        scope.launch {
            try {
                var done = 0
                list.map { it.id }.chunked(100).forEach { ch ->
                    Session.api().exec("/ip/hotspot/user/$action", mapOf(".id" to ch.joinToString(",")))
                    done += ch.size; busy = "جاري $label: $done / ${list.size}"
                }
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم $label ${list.size} كرت", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }
    LaunchedEffect(Unit) { load(force = false, quick = true) }

    Column(Modifier.fillMaxSize()) {
        if (!embedded) TopBar("مستخدمو الهوتسبوت", "${rows.size} كرت • ${profiles.size} باقة", onBack) {
            BarAction(Icons.Filled.PersonAdd, "إضافة", C.Teal) { form = emptyMap() }
            BarAction(Icons.Filled.Refresh, "تحديث", onClick = ::load)
        }
        Box(Modifier.weight(1f)) {
            UmSubscribersContent(
                users = rows, loading = loading, error = error, busy = busy, onRefresh = ::load,
                onAction = { action, list -> if (action == "enable") bulk(action, list) else confirm = action to list },
                onOpen = { details = it },
                onMenu = { menu = it }
            )
        }
    }

    created?.let { (card, pw, prof) ->
        CardCreatedDialog(card, pw, prof, onDismiss = { created = null }, onDelete = {
            scope.launch {
                try {
                    Session.api().print("/ip/hotspot/user", listOf(".id"), listOf("?name=$card")).mapNotNull { it[".id"] }
                        .forEach { Session.api().exec("/ip/hotspot/user/remove", mapOf(".id" to it)) }
                    com.zarinet.netcontrol.ui.components.toast(ctx, "تم حذف الكرت $card", Toast.LENGTH_SHORT).show()
                    load()
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                created = null
            }
        })
    }
    menu?.let { u ->
        CardMenuHost(u, com.zarinet.netcontrol.util.HotspotOps, onClose = { menu = null }, onChanged = ::load,
            onEdit = { c -> form = raw[c.id].orEmpty() })
    }
    form?.let { init ->
        UserForm(init, profiles, onDismiss = { form = null }) { values ->
            form = null
            scope.launch {
                try {
                    if (init.isEmpty()) {
                        Session.api().exec("/ip/hotspot/user/add", values)
                        created = Triple(values["name"].orEmpty(), values["password"].orEmpty(), values["profile"].orEmpty())
                    } else {
                        Session.api().exec("/ip/hotspot/user/set", values + (".id" to init[".id"]!!))
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                    }
                    load()
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            }
        }
    }
    confirm?.let { (action, list) ->
        val del = action == "remove"
        ConfirmDialog(if (del) "حذف الكروت" else "تعطيل الكروت",
            "هل أنت متأكد أنك تريد ${if (del) "حذف" else "تعطيل"} ${list.size} من البطاقات؟" + if (del) "\nلا يمكن التراجع." else "",
            if (del) "حذف" else "تعطيل", onConfirm = { bulk(action, list) }, onDismiss = { confirm = null })
    }
    details?.let { u ->
        val r = raw[u.id].orEmpty()
        AppDialog(u.name, onDismiss = { details = null }, buttons = {
            AppButton("حذف", { details = null; confirm = "remove" to listOf(u) }, kind = BtnKind.Danger)
            AppButton("تعديل", { details = null; form = r }, kind = BtnKind.Secondary)
            AppButton("إغلاق", { details = null }, kind = BtnKind.Ghost)
        }) {
            KvGrid(listOfNotNull(
                "الحالة" to (if (u.online) "متصل الآن" else u.view().label.removePrefix("ال")),
                "الباقة" to u.profile.ifBlank { "—" },
                "كلمة المرور" to u.password.ifBlank { "بدون" }.let { if (it == u.name) "نفس الرقم" else it },
                "الاستخدام" to Fmt.durationShort(r["uptime"]) + (r["limit-uptime"]?.let { " / ${Fmt.durationShort(it)}" } ?: ""),
                "الاستهلاك" to Fmt.bytes(u.usedBytes) + (r["limit-bytes-total"]?.let { " / ${Fmt.bytes(it)}" } ?: ""),
                u.remainingMs?.let { "المتبقي" to RosTime.left(it).removePrefix("باقي ") },
                u.comment.substringBefore(';').takeIf { it.isNotBlank() }?.let { "الدفعة" to it },
            ))
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                SmallAction(if (u.disabled) "تفعيل" else "تعطيل", if (u.disabled) Icons.Filled.CheckCircle else Icons.Filled.Block,
                    if (u.disabled) C.Green else C.Gold) {
                    details = null
                    if (u.disabled) bulk("enable", listOf(u)) else confirm = "disable" to listOf(u)
                }
                SmallAction("تصفير العداد", Icons.Filled.RestartAlt, C.Teal) { details = null; bulk("reset-counters", listOf(u)) }
            }
        }
    }
}

@Composable
private fun UserForm(init: Map<String, String>, profiles: List<String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var name by remember { mutableStateOf(init["name"] ?: "") }
    var pass by remember { mutableStateOf(init["password"] ?: "") }
    var profile by remember { mutableStateOf(init["profile"] ?: profiles.firstOrNull() ?: "default") }
    var limitUptime by remember { mutableStateOf(init["limit-uptime"] ?: "") }
    var limitBytesMb by remember { mutableStateOf(init["limit-bytes-total"]?.toLongOrNull()?.let { (it / 1048576).toString() } ?: "") }
    var comment by remember { mutableStateOf(init["comment"] ?: "") }
    FormDialog(
        title = if (init.isEmpty()) "إضافة مستخدم" else "تعديل ${init["name"]}",
        enabled = name.isNotBlank(),
        onDismiss = onDismiss,
        onConfirm = {
            val m = linkedMapOf("name" to name, "password" to pass, "profile" to profile, "comment" to comment)
            if (limitUptime.isNotBlank() || init.containsKey("limit-uptime")) m["limit-uptime"] = limitUptime.ifBlank { "0s" }
            if (limitBytesMb.isNotBlank()) m["limit-bytes-total"] = ((limitBytesMb.toLongOrNull() ?: 0) * 1048576).toString()
            else if (init.containsKey("limit-bytes-total")) m["limit-bytes-total"] = "0"
            onSave(m)
        }
    ) {
        Field(name, { name = it.trim() }, "اسم المستخدم / رقم الكرت", ltr = true)
        Field(pass, { pass = it }, "كلمة المرور", ltr = true)
        Dropdown("الباقة", profile, profiles) { profile = it }
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Field(limitUptime, { limitUptime = it.trim() }, "حد الوقت", Modifier.weight(1f), placeholder = "1d / 5h", ltr = true)
            Field(limitBytesMb, { limitBytesMb = it.filter(Char::isDigit) }, "حد البيانات MB", Modifier.weight(1f), number = true)
        }
        Field(comment, { comment = it }, "ملاحظة / اسم الدفعة")
    }
}


/** شاشة الهوتسبوت: الباقات، المستخدمين، طباعة كروت، بطاقة واحدة. */
@Composable
fun HotspotScreen(onBack: () -> Unit, initialTab: Int = 0, printPreview: Pair<VSettings, com.zarinet.netcontrol.util.CardDesign?>? = null) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        TopBar("الهوتسبوت", "Hotspot", onBack)
        SegTabs(listOf("الباقات", "المستخدمين", "طباعة كروت", "بطاقة واحدة"), tab, Modifier.padding(horizontal = D.screen)) { tab = it }
        Spacer(Modifier.height(D.sm))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HotspotProfilesTab()
                1 -> HotspotUsersScreen(onBack, embedded = true)
                2 -> VouchersScreen(com.zarinet.netcontrol.util.CardTarget.HOTSPOT, onBack = null, preview = printPreview)
                else -> HotspotSingleCardTab()
            }
        }
    }
}

/** إضافة كرت هوتسبوت واحد. */
@Composable
fun HotspotSingleCardTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rnd = remember { java.security.SecureRandom() }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var limitUptime by remember { mutableStateOf("") }
    var limitMb by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    fun rand(n: Int) = buildString { repeat(n) { append(rnd.nextInt(10)) } }

    LaunchedEffect(Unit) {
        try {
            profiles = Session.api().print("/ip/hotspot/user/profile", listOf("name")).mapNotNull { it["name"] }
            profile = profiles.firstOrNull { it != "default" } ?: profiles.firstOrNull().orEmpty()
        } catch (e: Exception) { error = "خطأ: ${e.message}" }
    }
    created?.let { (card, pw, prof) ->
        CardCreatedDialog(card, pw, prof, onDismiss = { created = null }, onDelete = {
            scope.launch {
                try {
                    Session.api().print("/ip/hotspot/user", listOf(".id"), listOf("?name=$card")).mapNotNull { it[".id"] }
                        .forEach { Session.api().exec("/ip/hotspot/user/remove", mapOf(".id" to it)) }
                    com.zarinet.netcontrol.ui.components.toast(ctx, "تم حذف الكرت $card", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                created = null
            }
        })
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md)
    ) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(D.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Field(name, { name = it.trim() }, "اسم المستخدم / رقم الكرت", Modifier.weight(1f), ltr = true)
                    AppButton("توليد", { name = rand(9) }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Casino)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                    Field(pass, { pass = it }, "كلمة السر (اختياري)", Modifier.weight(1f), ltr = true)
                    AppButton("توليد", { pass = rand(4) }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Casino)
                }
                Dropdown("باقة الهوتسبوت", profile, profiles) { profile = it }
                Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                    Field(limitUptime, { limitUptime = it.trim() }, "حد الوقت", Modifier.weight(1f), placeholder = "1d / 5h", ltr = true)
                    Field(limitMb, { limitMb = it.filter(Char::isDigit) }, "حد البيانات MB", Modifier.weight(1f), number = true)
                }
                Field(comment, { comment = it }, "ملاحظة (اختياري)")
            }
        }
        AppButton("إضافة البطاقة", {
            busy = true
            scope.launch {
                try {
                    val m = linkedMapOf("name" to name, "password" to pass, "profile" to profile)
                    if (comment.isNotBlank()) m["comment"] = comment
                    if (limitUptime.isNotBlank()) m["limit-uptime"] = limitUptime
                    if (limitMb.isNotBlank()) m["limit-bytes-total"] = ((limitMb.toLongOrNull() ?: 0) * 1048576).toString()
                    Session.api().exec("/ip/hotspot/user/add", m)
                    created = Triple(name, pass, profile)
                    error = null; name = ""; pass = ""
                } catch (e: Exception) { error = "خطأ: ${e.message}" }
                busy = false
            }
        }, Modifier.fillMaxWidth().height(46.dp), icon = Icons.Filled.PersonAdd, enabled = name.isNotBlank() && profile.isNotBlank(), loading = busy)
        error?.let {
            AppCard(Modifier.fillMaxWidth(), border = C.Red) { Text(it, style = MaterialTheme.typography.bodyLarge, color = C.Text) }
        }
    }
}
