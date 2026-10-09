package com.zarinet.netcontrol.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.R
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.AppButton
import com.zarinet.netcontrol.ui.components.AppCard
import com.zarinet.netcontrol.ui.components.AppDialog
import com.zarinet.netcontrol.ui.components.BarAction
import com.zarinet.netcontrol.ui.components.BtnKind
import com.zarinet.netcontrol.ui.components.ConfirmDialog
import com.zarinet.netcontrol.ui.components.IconBadge
import com.zarinet.netcontrol.ui.components.KvGrid
import com.zarinet.netcontrol.ui.components.Pill
import com.zarinet.netcontrol.ui.components.SearchField
import com.zarinet.netcontrol.ui.components.SmallAction
import com.zarinet.netcontrol.ui.components.StateView
import com.zarinet.netcontrol.ui.components.TopBar
import com.zarinet.netcontrol.ui.components.toast
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.CloudAccess
import com.zarinet.netcontrol.util.CloudForward
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.ForwardState
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** جهاز بث (مرسل) مكتشف عبر /ip neighbor. */
data class Sender(
    val identity: String,
    val interfaces: String,   // مثل: ether10-BANK-ALSHARQ,bridge-virtual
    val port: String,         // المنفذ الفعلي (أول واجهة)
    val address: String,
    val mac: String,
    val platform: String,
    val board: String,
    val clients: Int,         // عدد الأجهزة المكتشفة خلف هذا المنفذ (جدول MAC للجسر)
    val portDisabled: Boolean,
    val fromCutList: Boolean = false
)

@Composable
fun NeighborsScreen(onBack: () -> Unit, onOpenCloud: () -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val scope = rememberCoroutineScope()
    val devKey = "cut_" + (Session.device?.id ?: "")
    var list by remember { mutableStateOf<List<Sender>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var cutting by remember { mutableStateOf<Sender?>(null) }

    // الدخول لصفحة جهاز البث من خارج الشبكة (تحويل منفذ عبر السحابة)
    var fwd by remember { mutableStateOf<ForwardState?>(null) }
    var remoteFor by remember { mutableStateOf<Sender?>(null) }
    var remoteBusy by remember { mutableStateOf(false) }
    var remoteErr by remember { mutableStateOf<String?>(null) }
    val hasCloud = Session.device?.hasCloud == true

    LaunchedEffect(hasCloud) {
        val d = Session.device
        if (hasCloud && d != null) fwd = runCatching { CloudAccess.forwards(d) }.getOrNull()
    }

    fun remote(block: suspend () -> ForwardState) {
        remoteBusy = true
        remoteErr = null
        scope.launch {
            try {
                fwd = block()
            } catch (e: Exception) {
                remoteErr = e.message ?: "خطأ"
            }
            remoteBusy = false
        }
    }

    // قائمة المنافذ المقطوعة محفوظة محلياً لأن الجهاز يختفي من الجيران بعد قطع منفذه
    fun cutList(): MutableList<JSONObject> {
        val a = JSONArray(store.getString(devKey, "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
    }
    fun saveCut(l: List<JSONObject>) = store.putString(devKey, JSONArray(l).toString())

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val api = Session.api()
                val ifs = api.print("/interface", listOf("name", "disabled")).associate { it["name"].orEmpty() to (it["disabled"] == "true") }
                val hosts = runCatching { api.print("/interface/bridge/host", listOf("on-interface")) }.getOrDefault(emptyList())
                    .groupingBy { it["on-interface"].orEmpty() }.eachCount()
                val nb = api.print("/ip/neighbor", listOf("identity", "interface", "address", "address4", "mac-address", "platform", "board", "system-description"))
                val found = nb.map { n ->
                    val ifaces = n["interface"].orEmpty()
                    val port = ifaces.split(",").firstOrNull().orEmpty().trim()
                    Sender(n["identity"].orEmpty().ifBlank { n["mac-address"].orEmpty() }, ifaces, port,
                        n["address4"]?.ifBlank { null } ?: n["address"].orEmpty(), n["mac-address"].orEmpty(),
                        n["platform"].orEmpty(), n["board"].orEmpty().ifBlank { n["system-description"].orEmpty() }, hosts[port] ?: 0, ifs[port] == true)
                }
                // إضافة المرسلات المقطوعة التي لم تعد تظهر في الجيران
                val cut = cutList().filter { c -> found.none { it.port == c.optString("port") && it.identity == c.optString("identity") } }
                    .map { c ->
                        Sender(c.optString("identity"), c.optString("interfaces"), c.optString("port"), c.optString("address"),
                            c.optString("mac"), c.optString("platform"), c.optString("board"), 0, ifs[c.optString("port")] == true, fromCutList = true)
                    }
                list = (cut + found).sortedWith(compareByDescending<Sender> { it.portDisabled }.thenBy { it.port })
            } catch (e: Exception) { error = e.message ?: "خطأ" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun setPort(s: Sender, disable: Boolean) {
        scope.launch {
            try {
                Session.api().exec("/interface/${if (disable) "disable" else "enable"}", mapOf("numbers" to s.port))
                val l = cutList().filterNot { it.optString("port") == s.port && it.optString("identity") == s.identity }.toMutableList()
                if (disable) l += JSONObject().apply {
                    put("identity", s.identity); put("interfaces", s.interfaces); put("port", s.port); put("address", s.address); put("mac", s.mac)
                    put("platform", s.platform); put("board", s.board)
                }
                saveCut(l)
                toast(ctx, if (disable) "تم قطع البث عن ${s.port}" else "تمت إعادة البث على ${s.port}", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: Exception) { toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }

    val filtered = remember(list, query) {
        if (query.isBlank()) list else list.filter {
            it.identity.contains(query, true) || it.interfaces.contains(query, true) || it.address.contains(query) || it.mac.contains(query, true)
        }
    }
    val cutCount = list.count { it.portDisabled }

    Column(Modifier.fillMaxSize()) {
        TopBar("أجهزة البث", "${list.size} مرسل${if (cutCount > 0) " • $cutCount مقطوع" else ""}", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث", onClick = ::load)
        }
        SearchField(query, { query = it }, "بحث: الاسم، المنفذ، IP...", Modifier.fillMaxWidth().padding(horizontal = D.screen))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, top = D.sm, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item { StateView(loading && list.isEmpty(), error, !loading && error == null && filtered.isEmpty(), "لا توجد أجهزة بث مكتشفة", ::load) }
            items(filtered, key = { it.port + "|" + it.identity + "|" + it.mac }) { s ->
                SenderCard(
                    s,
                    onToggle = { if (s.portDisabled) setPort(s, false) else cutting = s },
                    onOpen = if (s.address.isNotBlank()) ({
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${s.address}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) else null,
                    remote = fwd?.active?.firstOrNull { it.targetIp == s.address },
                    onRemote = if (s.address.isNotBlank()) ({ remoteErr = null; remoteFor = s }) else null
                )
            }
            item {
                Text("قطع البث يعطّل المنفذ الذي يتصل به المرسل، فينقطع كل ما خلفه على نفس المنفذ.",
                    style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.padding(top = D.sm))
            }
        }
    }

    remoteFor?.let { s ->
        val server = fwd?.server.orEmpty().ifBlank { Session.device?.cloudHost.orEmpty() }
        RemoteSenderDialog(
            s = s,
            hasCloud = hasCloud,
            server = server,
            current = fwd?.active?.filter { it.targetIp == s.address }.orEmpty(),
            busy = remoteBusy,
            error = remoteErr,
            onDismiss = { remoteFor = null },
            onEnableCloud = { remoteFor = null; onOpenCloud() },
            onOpen = { port -> remote { CloudAccess.openForward(Session.api(), Session.device!!, s.address, port) } },
            onClose = { f ->
                remote {
                    val ros = runCatching { Session.api() }.getOrNull()
                    CloudAccess.closeForward(ros, Session.device!!, f.publicPort)
                }
            },
            onBrowse = { url ->
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            onCopy = { url ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", url))
                toast(ctx, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
            }
        )
    }

    cutting?.let { s ->
        ConfirmDialog("قطع البث",
            "سيتم تعطيل المنفذ ${s.port}.\nكل الأجهزة على هذا المنفذ (${s.clients} جهاز) ستنقطع حتى تعيد البث.\nتأكد أنك لست متصلاً بالراوتر عبر هذا المنفذ.",
            "قطع البث", onConfirm = { setPort(s, true) }, onDismiss = { cutting = null })
    }
}

@Composable
internal fun SenderCard(
    s: Sender,
    onToggle: () -> Unit,
    onOpen: (() -> Unit)?,
    remote: CloudForward? = null,
    onRemote: (() -> Unit)? = null
) {
    val t = MaterialTheme.typography
    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md), border = if (s.portDisabled) C.Red.copy(alpha = 0.6f) else C.Border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val img = DeviceImages.of(s.board, s.platform, s.identity)
            if (img != null) Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(D.radiusSmall)).background(Color.White)
                    .border(1.5.dp, if (s.portDisabled) C.Red else C.Teal, RoundedCornerShape(D.radiusSmall)).padding(3.dp)
            ) { Image(painterResource(img), s.board, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            else if (DeviceImages.isMikrotik(s.platform, s.board)) IconBadge(Icons.Filled.Router, if (s.portDisabled) C.Red else C.Blue, 40.dp, 22.dp)
            else IconBadge(Icons.Filled.SettingsInputAntenna, if (s.portDisabled) C.Red else C.Teal, 40.dp, 22.dp)
            Spacer(Modifier.width(D.sm))
            Column(Modifier.weight(1f)) {
                Text("${s.identity} ${Fmt.ltr("[${s.clients}]")}", style = t.titleMedium, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.platform.isNotBlank() || s.board.isNotBlank())
                    Text(listOf(s.platform, s.board).filter { it.isNotBlank() }.joinToString(" • "), style = t.bodySmall, color = C.Dim, maxLines = 1)
            }
            Pill(if (s.portDisabled) "مقطوع" else "يبث", if (s.portDisabled) C.Red else C.Online)
        }
        if (remote != null) Row(Modifier.padding(top = D.xs)) {
            Pill("مفتوح من الخارج", C.Blue)
        }
        Spacer(Modifier.height(D.sm))
        KvGrid(listOf("الواجهة" to s.interfaces, "IP" to s.address.ifBlank { "—" }))
        if (s.mac.isNotBlank()) Text("MAC: ${s.mac}", style = t.bodySmall, color = C.Faint, modifier = Modifier.padding(top = D.xs))
        Spacer(Modifier.height(D.md))
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
            if (s.portDisabled) SmallAction("إعادة البث", Icons.Filled.PlayArrow, C.Online, Modifier.weight(1f), onClick = onToggle)
            else SmallAction("قطع البث", Icons.Filled.PowerSettingsNew, C.Red, Modifier.weight(1f), onClick = onToggle)
            if (onOpen != null) SmallAction("فتح الصفحة", Icons.Filled.OpenInBrowser, C.Steel, Modifier.weight(1f), onClick = onOpen)
            if (onRemote != null) SmallAction("من الخارج", Icons.Filled.Public, C.Blue, Modifier.weight(1f), onClick = onRemote)
        }
    }
}

@Composable
internal fun RemoteSenderDialog(
    s: Sender,
    hasCloud: Boolean,
    server: String,
    current: List<CloudForward>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEnableCloud: () -> Unit,
    onOpen: (Int) -> Unit,
    onClose: (CloudForward) -> Unit,
    onBrowse: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    AppDialog(
        "الدخول من خارج الشبكة",
        onDismiss,
        buttons = { AppButton("إغلاق", onDismiss, kind = BtnKind.Ghost) }
    ) {
        RemoteSenderBody(s, hasCloud, server, current, busy, error, onEnableCloud, onOpen, onClose, onBrowse, onCopy)
    }
}

@Composable
internal fun RemoteSenderBody(
    s: Sender,
    hasCloud: Boolean,
    server: String,
    current: List<CloudForward>,
    busy: Boolean,
    error: String?,
    onEnableCloud: () -> Unit,
    onOpen: (Int) -> Unit,
    onClose: (CloudForward) -> Unit,
    onBrowse: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    val t = MaterialTheme.typography
    Text("${s.identity}  ${Fmt.ltr(s.address)}", style = t.titleMedium, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(D.sm))

    if (!hasCloud) {
        Text("هذه الميزة تعمل عبر «الوصول من خارج الشبكة». فعّله للراوتر أولاً ثم ارجع هنا.", style = t.bodyMedium, color = C.Gold)
        Spacer(Modifier.height(D.md))
        AppButton("تفعيل الوصول من الخارج", onEnableCloud, Modifier.fillMaxWidth(), icon = Icons.Filled.Cloud)
        return
    }

    // المنافذ المفتوحة حالياً لهذا الجهاز
    for (f in current) {
        val url = f.url(server)
        AppCard(
            Modifier.fillMaxWidth().padding(bottom = D.sm),
            border = C.Blue.copy(alpha = 0.5f),
            background = C.Blue.copy(alpha = 0.06f),
            padding = PaddingValues(D.md)
        ) {
            Text(portName(f.targetPort), style = t.bodySmall, color = C.Dim)
            Text(Fmt.ltr(url), style = t.titleMedium, color = C.Text)
            Spacer(Modifier.height(D.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                if (!f.isWinbox) SmallAction("فتح", Icons.Filled.OpenInBrowser, C.Teal, Modifier.weight(1f)) { onBrowse(url) }
                SmallAction("نسخ", Icons.Filled.ContentCopy, C.Steel, Modifier.weight(1f)) { onCopy(url) }
                SmallAction("إغلاق", Icons.Filled.LinkOff, C.Red, Modifier.weight(1f)) { if (!busy) onClose(f) }
            }
        }
    }

    // المنافذ المتاحة للفتح (Winbox لأجهزة MikroTik فقط)
    val ports = buildList {
        add(80)
        add(443)
        if (DeviceImages.isMikrotik(s.platform, s.board)) add(8291)
    }.filter { p -> current.none { it.targetPort == p } }

    if (ports.isNotEmpty()) {
        Text(if (current.isEmpty()) "اختر ما تريد فتحه من الخارج:" else "فتح منفذ آخر:", style = t.bodyMedium, color = C.Text)
        Spacer(Modifier.height(D.xs))
        for (p in ports) {
            val first = p == ports.first()
            AppButton(
                "فتح " + portName(p),
                { onOpen(p) },
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                if (first) BtnKind.Primary else BtnKind.Secondary,
                Icons.Filled.Public,
                enabled = !busy,
                loading = busy && first
            )
        }
    }
    error?.let { Text(it, style = t.bodySmall, color = C.Red, modifier = Modifier.padding(top = D.sm)) }
    Text(
        "الرابط مؤقت ويُغلق تلقائياً بعد 3 ساعات. تحتاج اسم المستخدم وكلمة المرور الخاصة بجهاز البث.",
        style = t.bodySmall, color = C.Faint, modifier = Modifier.padding(top = D.sm)
    )
}

private fun portName(p: Int): String = when (p) {
    80 -> "صفحة الجهاز (HTTP)"
    443 -> "صفحة الجهاز (HTTPS)"
    8291 -> "Winbox"
    else -> "المنفذ $p"
}

/** صور الأجهزة حسب الموديل المكتشف (من board / platform / الاسم). يسهل إضافة موديلات جديدة هنا. */
object DeviceImages {
    private val models: List<Pair<Regex, Int>> = listOf(
        Regex("power\\s*beam|\\bPBE[-_ ]", RegexOption.IGNORE_CASE) to R.drawable.dev_powerbeam,
        Regex("lite\\s*beam|\\bLBE[-_ ]", RegexOption.IGNORE_CASE) to R.drawable.dev_litebeam,
        Regex("\\bCPE\\s*-?\\d{3}|TP-?LINK", RegexOption.IGNORE_CASE) to R.drawable.dev_cpe210,
    )
    /** أجهزة MikroTik (بدون صورة موديل): أيقونة راوتر مميزة. */
    fun isMikrotik(vararg texts: String) = texts.any { it.contains("mikrotik", true) || it.contains("routeros", true) }
    fun of(vararg texts: String): Int? {
        val all = texts.joinToString(" ")
        return models.firstOrNull { it.first.containsMatchIn(all) }?.second
    }
}
