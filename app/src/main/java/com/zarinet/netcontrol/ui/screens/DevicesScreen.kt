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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Device
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(onConnected: () -> Unit, onSubscription: () -> Unit = {}, onLogout: () -> Unit = {}, onSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val cloud = remember { com.zarinet.netcontrol.account.CloudDevices(store) }
    var devices by remember { mutableStateOf(cloud.cached()) }
    var syncing by remember { mutableStateOf(true) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val session by com.zarinet.netcontrol.account.AuthService.session.collectAsState()
    var editing by remember { mutableStateOf<Device?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Device?>(null) }
    var deleting by remember { mutableStateOf<Device?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun sync() {
        syncing = true
        scope.launch {
            try { devices = cloud.sync(); syncError = null } catch (e: Exception) { syncError = e.message }
            syncing = false
        }
    }
    fun save(d: Device) {
        scope.launch {
            try { cloud.upsert(d); devices = store.load() }
            catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, "لم يُحفظ في الحساب: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun remove(id: String) {
        scope.launch {
            try { cloud.delete(id); devices = store.load() }
            catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, "تعذر الحذف: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(session?.userId) { if (session != null) sync() else syncing = false }

    fun connect(d: Device) {
        if (connecting != null) return
        connecting = d.id
        scope.launch {
            try {
                Session.connect(d)
                onConnected()
            } catch (e: com.zarinet.netcontrol.subscription.SubscriptionRequiredException) {
                com.zarinet.netcontrol.ui.components.toast(ctx, e.message, Toast.LENGTH_LONG).show()
                onSubscription()
            } catch (e: Exception) {
                com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "فشل الاتصال", Toast.LENGTH_LONG).show()
            } finally {
                connecting = null
            }
        }
    }

    DevicesContent(
        devices = devices,
        connectingId = connecting,
        onConnect = ::connect,
        onMenu = { menuFor = it },
        onAdd = { editing = null; showForm = true },
        topExtra = {
            Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
                session?.let { AccountBar(it.email, syncing, syncError, onSync = ::sync, onLogout = { confirmLogout = true }, onSettings = onSettings) }
                SubscriptionBanner(onSubscription)
                if (cloud.needPassword.isNotEmpty()) Text("⚠️ أعد إدخال كلمة مرور ${cloud.needPassword.size} جهاز (تغيّرت كلمة مرور الحساب)",
                    style = MaterialTheme.typography.bodySmall, color = C.Gold)
            }
        }
    )
    if (confirmLogout) ConfirmDialog("تسجيل الخروج", "هل تريد تسجيل الخروج من ${session?.email.orEmpty()}؟\nأجهزتك تبقى محفوظة في حسابك.", "خروج",
        onConfirm = {
            scope.launch { com.zarinet.netcontrol.account.AccountActions.logout(ctx); onLogout() }
        }, onDismiss = { confirmLogout = false })

    menuFor?.let { d ->
        FormDialog(title = d.name, confirmText = "تعديل", onConfirm = { editing = d; showForm = true; menuFor = null },
            onDismiss = { menuFor = null }) {
            Text("المستخدم: ${d.user}", style = MaterialTheme.typography.bodyLarge, color = C.Dim)
            AppButton("حذف الجهاز", { deleting = d; menuFor = null }, Modifier.fillMaxWidth(), BtnKind.Danger, Icons.Filled.DeleteOutline)
        }
    }
    deleting?.let { d ->
        ConfirmDialog("حذف الجهاز", "هل تريد حذف \"${d.name}\" من القائمة؟", "حذف",
            onConfirm = { remove(d.id) },
            onDismiss = { deleting = null })
    }
    if (showForm) {
        DeviceForm(editing, onDismiss = { showForm = false }) { d ->
            showForm = false; save(d)
        }
    }
}

@Composable
fun DevicesContent(
    devices: List<Device>,
    connectingId: String?,
    onConnect: (Device) -> Unit,
    onMenu: (Device) -> Unit,
    onAdd: () -> Unit,
    topExtra: @Composable () -> Unit = {}
) {
    val t = MaterialTheme.typography
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(D.screen),
            verticalArrangement = Arrangement.spacedBy(D.md)
        ) {
            // الهوية
            item {
                Row(Modifier.fillMaxWidth().padding(top = D.lg, bottom = D.sm), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(64.dp, 18.dp)
                    Spacer(Modifier.width(D.lg))
                    Column {
                        Text("ميكروتك الشامل", style = t.headlineMedium, color = C.Text)
                        Text("إدارة MikroTik مباشرة عبر RouterOS API", style = t.bodyMedium, color = C.Dim)
                    }
                }
            }
            item { topExtra() }
            item {
                SectionTitle("أجهزتي") { if (devices.isNotEmpty()) CountBadge(devices.size, C.Cyan) }
                Text("اضغط للاتصال • ضغط مطوّل للتعديل أو الحذف", style = t.bodySmall, color = C.Faint,
                    modifier = Modifier.padding(start = D.md, top = 2.dp))
            }
            if (devices.isEmpty()) item {
                AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.xl)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconBadge(Icons.Filled.Router, C.Teal, 64.dp, 34.dp, circle = true)
                        Spacer(Modifier.height(D.md))
                        Text("لا توجد أجهزة بعد", style = t.titleMedium, color = C.Text)
                        Text("أضف راوتر MikroTik للبدء", style = t.bodyMedium, color = C.Dim, textAlign = TextAlign.Center)
                    }
                }
            }
            items(devices, key = { it.id }) { d ->
                AppCard(Modifier.fillMaxWidth(), onClick = { onConnect(d) }, onLongClick = { onMenu(d) },
                    border = if (connectingId == d.id) C.Teal else C.Border, padding = PaddingValues(D.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.Router, C.Cyan, 42.dp, 24.dp)
                        Spacer(Modifier.width(D.md))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = t.titleMedium, color = C.Text, maxLines = 1)
                            // هل الجهاز متاح عن بُعد (عنوان سحابي مضبوط)
                            Text(if (d.hasCloud) "متاح من داخل وخارج الشبكة" else "اضغط للاتصال", style = t.bodySmall,
                                color = if (d.hasCloud) C.Teal else C.Dim, maxLines = 1)
                        }
                        if (connectingId == d.id) CircularProgressIndicator(Modifier.size(24.dp), color = C.Teal, strokeWidth = 2.5.dp)
                        else IconButton({ onMenu(d) }) { Icon(Icons.Filled.MoreVert, "خيارات", tint = C.Dim) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = D.screen, vertical = D.md)) {
            AppButton("إضافة جهاز", onAdd, Modifier.fillMaxWidth().height(46.dp), icon = Icons.Filled.Add)
            Text("Native • RouterOS API • بدون WebView", style = t.labelSmall, color = C.Faint,
                modifier = Modifier.fillMaxWidth().padding(top = D.sm), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DeviceForm(initial: Device?, onDismiss: () -> Unit, onSave: (Device) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 8728).toString()) }
    var user by remember { mutableStateOf(initial?.user ?: "admin") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    FormDialog(
        title = if (initial == null) "إضافة جهاز" else "تعديل جهاز",
        confirmText = "حفظ الجهاز",
        enabled = host.isNotBlank() && user.isNotBlank(),
        onDismiss = onDismiss,
        onConfirm = {
            val base = initial ?: Device(name = "", host = "", user = "", password = "")
            onSave(base.copy(name = name.ifBlank { host }, host = host, port = port.toIntOrNull() ?: 8728, user = user, password = pass))
        }
    ) {
        Field(name, { name = it }, "اسم الشبكة / الجهاز")
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Field(host, { host = it.trim() }, "IP / DNS", Modifier.weight(2f), ltr = true)
            Field(port, { port = it.filter(Char::isDigit) }, "المنفذ", Modifier.weight(1f), number = true)
        }
        Field(user, { user = it }, "اسم المستخدم", ltr = true)
        Field(pass, { pass = it }, "كلمة المرور", password = true)
    }
}

@Composable
internal fun AccountBar(email: String, syncing: Boolean, error: String?, onSync: () -> Unit, onLogout: () -> Unit, onSettings: () -> Unit = {}) {
    val t = MaterialTheme.typography
    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = D.md, vertical = D.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.AccountCircle, C.Teal, 34.dp, 20.dp, circle = true)
            Spacer(Modifier.width(D.sm))
            Column(Modifier.weight(1f)) {
                Text(com.zarinet.netcontrol.util.Fmt.ltr(email), style = t.titleSmall, color = C.Text, maxLines = 1)
                Text(when { syncing -> "جاري المزامنة مع حسابك..."; error != null -> "بدون اتصال — عرض النسخة المحفوظة"; else -> "☁️ أجهزتك محفوظة على حسابك" },
                    style = t.labelSmall, color = if (error != null) C.Gold else C.Dim, maxLines = 1)
            }
            if (syncing) CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
            else IconButton(onSync) { Icon(Icons.Filled.Sync, "مزامنة", tint = C.Dim) }
            IconButton(onSettings) { Icon(Icons.Filled.Settings, "إعدادات الحساب", tint = C.Teal) }
            IconButton(onLogout) { Icon(Icons.Filled.Logout, "خروج", tint = C.Red) }
        }
    }
}
