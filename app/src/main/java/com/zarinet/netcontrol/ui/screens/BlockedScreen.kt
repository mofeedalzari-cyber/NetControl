package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.BlockedDev
import com.zarinet.netcontrol.util.loadBlocked
import com.zarinet.netcontrol.util.unblock
import com.zarinet.netcontrol.util.blockMac
import com.zarinet.netcontrol.util.normalizeMac
import kotlinx.coroutines.launch

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<BlockedDev>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<List<BlockedDev>?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try { list = loadBlocked() } catch (e: Exception) { error = e.message ?: "خطأ" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val filtered = remember(list, query) {
        if (query.isBlank()) list else list.filter { d ->
            listOf(d.mac, d.address, d.reason, d.owner, d.hostName, d.server).any { it.contains(query, true) }
        }
    }
    val reasons = remember(list) { list.groupingBy { it.reason }.eachCount().entries.sortedByDescending { it.value } }

    Column(Modifier.fillMaxSize()) {
        TopBar("الأجهزة المحظورة", "${list.size} جهاز", onBack) {
            BarAction(Icons.Filled.Add, "حظر جهاز", C.Red) { adding = true }
            BarAction(Icons.Filled.Refresh, "تحديث", onClick = ::load)
        }
        Column(Modifier.padding(horizontal = D.screen), verticalArrangement = Arrangement.spacedBy(D.sm)) {
            AppButton("تنظيف الأجهزة المحظورة (${list.size})", { confirm = list }, Modifier.fillMaxWidth().height(52.dp),
                BtnKind.Danger, Icons.Filled.CleaningServices, enabled = list.isNotEmpty())
            if (reasons.size > 1) {
                FlowPills(reasons.map { "${it.key}: ${it.value}" })
            }
            SearchField(query, { query = it }, "بحث: MAC، IP، الكرت...")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, top = D.sm, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item { StateView(loading && list.isEmpty(), error, !loading && error == null && filtered.isEmpty(), "لا توجد أجهزة محظورة", ::load) }
            items(filtered, key = { it.source + it.id }) { d ->
                AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md), border = C.Red.copy(alpha = 0.45f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Block, C.Red, 40.dp, 22.dp)
                        Spacer(Modifier.width(D.sm))
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.ltr(d.mac.ifBlank { d.address }), style = MaterialTheme.typography.titleMedium, color = C.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (d.hostName.isNotBlank()) Text(d.hostName, style = MaterialTheme.typography.bodySmall, color = C.Dim, maxLines = 1)
                        }
                        Pill(d.reason, C.Gold)
                    }
                    Spacer(Modifier.height(D.sm))
                    KvGrid(listOfNotNull(
                        "سبب الحظر" to d.reason,
                        "الكرت المالك" to d.owner.ifBlank { "غير معروف" },
                        "عنوان IP" to d.address.ifBlank { "—" },
                        d.toAddress.takeIf { it.isNotBlank() }?.let { "إلى عنوان" to it },
                        "السيرفر" to d.server.ifBlank { "الكل" },
                        "المصدر" to if (d.source == "binding") "IP Binding" else "Hotspot Host",
                    ))
                    Spacer(Modifier.height(D.sm))
                    SmallAction("إلغاء الحظر", Icons.Filled.LockOpen, C.Green) { confirm = listOf(d) }
                }
            }
        }
    }

    if (adding) BlockMacDialog(onDismiss = { adding = false }, onDone = ::load)
    confirm?.let { l ->
        ConfirmDialog(if (l.size == 1) "إلغاء الحظر" else "تنظيف الأجهزة المحظورة",
            if (l.size == 1) "إلغاء حظر ${l[0].mac.ifBlank { l[0].address }}؟" else "هل تريد إلغاء حظر ${l.size} جهاز؟",
            if (l.size == 1) "إلغاء الحظر" else "تنظيف", danger = l.size > 1,
            onConfirm = {
                scope.launch {
                    try { unblock(l); com.zarinet.netcontrol.ui.components.toast(ctx, "تم إلغاء حظر ${l.size} جهاز", Toast.LENGTH_SHORT).show(); load() }
                    catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
                }
            }, onDismiss = { confirm = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowPills(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Pill(it, C.Gold) }
    }
}

/** نافذة حظر جهاز يدوياً بالماك (مع اختيار من الأجهزة المتصلة). */
@Composable
fun BlockMacDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mac by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("يدوي") }
    var server by remember { mutableStateOf("all") }
    var servers by remember { mutableStateOf(listOf("all")) }
    var online by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }   // (mac, وصف)
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            val api = Session.api()
            servers = listOf("all") + api.print("/ip/hotspot", listOf("name")).mapNotNull { it["name"] }
            online = api.print("/ip/hotspot/host", listOf("mac-address", "address", "authorized", "comment"))
                .mapNotNull { h -> h["mac-address"]?.let { it to "${Fmt.ltr(it)}  •  ${h["address"].orEmpty()}" } }
                .distinctBy { it.first }
        }
        runCatching {
            val users = Session.api().print("/ip/hotspot/active", listOf("user", "mac-address")).associate { it["mac-address"].orEmpty() to it["user"].orEmpty() }
            online = online.map { (m, d) -> m to (users[m]?.let { "$d  •  $it" } ?: d) }
        }
    }
    val norm = normalizeMac(mac)
    FormDialog("حظر جهاز يدوي", confirmText = "حظر", enabled = norm != null && !busy, onDismiss = onDismiss, onConfirm = {
        busy = true
        scope.launch {
            try {
                blockMac(norm!!, reason.trim(), server)
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم حظر $norm", Toast.LENGTH_SHORT).show()
                onDismiss(); onDone()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show(); busy = false }
        }
    }) {
        Field(mac, { mac = it }, "عنوان الجهاز (MAC)", ltr = true, placeholder = "9A:D0:31:C0:29:11")
        if (mac.isNotBlank() && norm == null) Text("صيغة MAC غير صحيحة (12 خانة)", style = MaterialTheme.typography.bodySmall, color = C.Red)
        if (online.isNotEmpty()) Dropdown("أو اختر من الأجهزة المتصلة", online.firstOrNull { it.first.equals(norm, true) }?.second.orEmpty(),
            online.map { it.second }) { v -> mac = online.first { it.second == v }.first }
        Field(reason, { reason = it }, "سبب الحظر", placeholder = "مثال: netshare / مخالف")
        Dropdown("السيرفر", server, servers) { server = it }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Teal, trackColor = C.CardHi)
    }
}
