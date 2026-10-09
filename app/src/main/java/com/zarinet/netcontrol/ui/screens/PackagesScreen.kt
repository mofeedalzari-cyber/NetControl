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
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.OnLoginScript
import kotlinx.coroutines.launch

/** شاشة الباقات الموحدة: تبويب الهوتسبوت وتبويب يوزر منجر (إضافة، تعديل، حذف). */
@Composable
fun PackagesScreen(onBack: () -> Unit, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize()) {
        TopBar("الباقات", "إضافة وتعديل باقات الهوتسبوت ويوزر منجر", onBack)
        SegTabs(listOf("الهوتسبوت", "يوزر منجر"), tab, Modifier.padding(horizontal = D.screen)) { tab = it }
        Spacer(Modifier.height(D.sm))
        Box(Modifier.weight(1f)) {
            if (tab == 0) HotspotProfilesTab() else UmPackagesTab()
        }
    }
}

@Composable
fun HotspotProfilesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var usage by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var pools by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<Map<String, String>?>(null) }
    var deleting by remember { mutableStateOf<Map<String, String>?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val api = Session.api()
                // الباقات والمجمّعات في رحلة واحدة؛ عدد الكروت لكل باقة بعدها دون تأخير عرض القائمة
                val r = api.batch(listOf(
                    com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/hotspot/user/profile/print"),
                    com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/pool/print", proplist = listOf("name"))
                ))
                profiles = r[0].getOrThrow().rows
                pools = (r[1].getOrNull()?.rows ?: emptyList()).mapNotNull { it["name"] }
                loading = false
                usage = runCatching { api.print("/ip/hotspot/user", listOf("profile")).groupingBy { it["profile"].orEmpty() }.eachCount() }
                    .getOrDefault(emptyMap())
            } catch (e: Exception) { error = e.message ?: "خطأ" }
            loading = false
        }
    }
    fun act(msg: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); com.zarinet.netcontrol.ui.components.toast(ctx, msg, Toast.LENGTH_SHORT).show(); load() }
            catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(Unit) { load() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.sm)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("باقات الهوتسبوت", Modifier.weight(1f)) { CountBadge(profiles.size, C.Teal) }
                Spacer(Modifier.width(D.sm))
                AppButton("إضافة باقة", { form = emptyMap() }, Modifier.height(40.dp), icon = Icons.Filled.Add)
            }
        }
        item { StateView(loading && profiles.isEmpty(), error, !loading && error == null && profiles.isEmpty(), "لا توجد باقات", ::load) }
        items(profiles, key = { it[".id"] ?: it["name"].orEmpty() }) { p ->
            val name = p["name"].orEmpty()
            PackageCard(
                name, "${usage[name] ?: 0} كرت", C.Cyan,
                listOf(
                    "السرعة" to p["rate-limit"].orEmpty().ifBlank { "بلا قيود" },
                    "أجهزة/كرت" to p["shared-users"].orEmpty().ifBlank { "1" },
                    "مهلة الجلسة" to p["session-timeout"].orEmpty().let { if (it.isBlank() || Fmt.seconds(it) == 0L) "بلا" else Fmt.durationShort(it) },
                    "مهلة الخمول" to p["idle-timeout"].orEmpty().let { if (it.isBlank() || it == "none" || Fmt.seconds(it) == 0L) "بلا" else Fmt.durationShort(it) },
                    "مجمع العناوين" to p["address-pool"].orEmpty().ifBlank { "none" },
                    "سكربت الدخول" to if (p["on-login"].orEmpty().contains(OnLoginScript.MARKER)) "صلاحية مفعّلة" else if (p["on-login"].isNullOrBlank()) "لا يوجد" else "مخصص",
                ),
                onEdit = { form = p },
                onDelete = if (name == "default") null else ({ deleting = p })
            )
        }
    }

    form?.let { init ->
        HotspotProfileDialog(init, pools, onDismiss = { form = null }) { values ->
            form = null
            act(if (init.isEmpty()) "تمت إضافة الباقة" else "تم حفظ الباقة") {
                if (init.isEmpty()) Session.api().exec("/ip/hotspot/user/profile/add", values.filterValues { it.isNotBlank() })
                else Session.api().exec("/ip/hotspot/user/profile/set", values + (".id" to init[".id"]!!))
            }
        }
    }
    deleting?.let { p ->
        val n = usage[p["name"]] ?: 0
        ConfirmDialog("حذف الباقة", "حذف الباقة \"${p["name"]}\"؟" + if (n > 0) "\nعليها $n كرت — ستنتقل للباقة الافتراضية." else "", "حذف",
            onConfirm = { act("تم حذف الباقة") { Session.api().exec("/ip/hotspot/user/profile/remove", mapOf(".id" to p[".id"]!!)) } },
            onDismiss = { deleting = null })
    }
}

@Composable
private fun HotspotProfileDialog(init: Map<String, String>, pools: List<String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val isNew = init.isEmpty()
    fun clean(v: String?) = v.orEmpty().let { if (it.isBlank() || it == "none" || Fmt.seconds(it) == 0L) "" else it }
    var name by remember { mutableStateOf(init["name"].orEmpty()) }
    var rate by remember { mutableStateOf(init["rate-limit"].orEmpty()) }
    var shared by remember { mutableStateOf(init["shared-users"].orEmpty().ifBlank { "1" }) }
    var session by remember { mutableStateOf(clean(init["session-timeout"])) }
    var idle by remember { mutableStateOf(clean(init["idle-timeout"])) }
    var pool by remember { mutableStateOf(init["address-pool"].orEmpty().ifBlank { "none" }) }
    FormDialog(if (isNew) "إضافة باقة هوتسبوت" else "تعديل باقة ${init["name"]}", enabled = name.isNotBlank(), onDismiss = onDismiss,
        onConfirm = {
            onSave(linkedMapOf(
                "name" to name.trim(),
                "rate-limit" to rate.trim(),
                "shared-users" to shared.ifBlank { "1" },
                "session-timeout" to session.trim().ifBlank { if (isNew) "" else "0s" },
                "idle-timeout" to idle.trim().ifBlank { "none" },
                "address-pool" to pool
            ))
        }) {
        Field(name, { name = it }, "اسم الباقة", ltr = true, placeholder = "مثال: day-200")
        Field(rate, { rate = it }, "السرعة (رفع/تنزيل)", ltr = true, placeholder = "مثال 1M/4M — فارغ يعني بلا قيود")
        Field(shared, { shared = it.filter(Char::isDigit) }, "عدد الأجهزة لكل كرت", number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Field(session, { session = it.trim() }, "مهلة الجلسة", Modifier.weight(1f), ltr = true, placeholder = "مثال 1d أو 5h")
            Field(idle, { idle = it.trim() }, "مهلة الخمول", Modifier.weight(1f), ltr = true, placeholder = "مثال 10m")
        }
        Dropdown("مجمع العناوين (IP Pool)", pool, listOf("none") + pools) { pool = it }
        Text("حد الوقت والبيانات لكل كرت يُحدَّد عند طباعة الكروت. الصلاحية وربط أول هاتف يُفعَّلان تلقائياً على الباقة عند استخدامهما.",
            style = MaterialTheme.typography.bodySmall, color = C.Dim)
    }
}
