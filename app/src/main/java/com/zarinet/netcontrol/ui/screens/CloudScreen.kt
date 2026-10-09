package com.zarinet.netcontrol.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.account.AuthService
import com.zarinet.netcontrol.account.CloudDevices
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.AppButton
import com.zarinet.netcontrol.ui.components.AppCard
import com.zarinet.netcontrol.ui.components.BarAction
import com.zarinet.netcontrol.ui.components.BtnKind
import com.zarinet.netcontrol.ui.components.ConfirmDialog
import com.zarinet.netcontrol.ui.components.IconBadge
import com.zarinet.netcontrol.ui.components.Pill
import com.zarinet.netcontrol.ui.components.SectionTitle
import com.zarinet.netcontrol.ui.components.TopBar
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.CloudAccess
import com.zarinet.netcontrol.util.CloudInfo
import com.zarinet.netcontrol.util.RouterTunnel
import com.zarinet.netcontrol.util.UserManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CloudUi(
    val loggedIn: Boolean = true,
    val loading: Boolean = false,
    val info: CloudInfo? = null,
    val tunnel: RouterTunnel? = null,
    val viaCloud: Boolean = false,
    val busy: String? = null,
    val error: String? = null,
    val umUrl: String? = null
)

/** شاشة الوصول من خارج الشبكة (Shamel Cloud). */
@Composable
fun CloudScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DeviceStore(context) }
    val devices = remember { CloudDevices(store) }
    val loggedIn = AuthService.session.collectAsState().value != null
    var ui by remember { mutableStateOf(CloudUi(loggedIn = loggedIn, loading = true, viaCloud = Session.viaCloud)) }
    var confirmOff by remember { mutableStateOf(false) }

    fun refresh() {
        val d = Session.device ?: return
        ui = ui.copy(loading = true)
        scope.launch {
            val tunnel = runCatching { CloudAccess.routerTunnel(Session.api()) }.getOrNull()
            val info = if (loggedIn) {
                runCatching { CloudAccess.status(d) }
                    .onFailure { ui = ui.copy(error = it.message) }
                    .getOrNull()
            } else null
            ui = ui.copy(loading = false, info = info, tunnel = tunnel, viaCloud = Session.viaCloud)
            // للأجهزة المفعّلة قبل إضافة منفذ الويب: نضيفه الآن ثم نعرض رابط يوزر منجر
            if (info != null && info.enabled) runCatching {
                val fresh = CloudAccess.ensureWeb(Session.api(), d) ?: info
                val v6 = runCatching { UserManager.isV6() }.getOrDefault(true)
                if (fresh.webPort > 0) ui = ui.copy(info = fresh, umUrl = CloudAccess.userManagerUrl(fresh, v6))
            }
        }
    }

    fun run(label: String, block: suspend () -> Unit) {
        ui = ui.copy(busy = label, error = null)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                ui = ui.copy(error = e.message ?: "خطأ")
            }
            ui = ui.copy(busy = null)
            refresh()
        }
    }

    fun enable() = run("جارٍ التفعيل…") {
        val d = Session.device ?: error("لا يوجد جهاز")
        val nd = CloudAccess.enable(Session.api(), d).second
        devices.upsert(nd)
        Session.device = nd
        check(CloudAccess.lastTunnelOk) {
            "تم الإعداد، لكن النفق لا يمرّر بيانات بعد. تأكد أن الراوتر متصل بالإنترنت، وأنه لا توجد قاعدة جدار ناري أو mangle تمنع الاتصال بالسيرفر، ثم اضغط «إعادة الإعداد»."
        }
        delay(2000) // مهلة ليتصل النفق قبل قراءة الحالة
    }

    fun disable() = run("جارٍ الإلغاء…") {
        val d = Session.device ?: error("لا يوجد جهاز")
        val ros = runCatching { Session.api() }.getOrNull()
        val nd = CloudAccess.disable(ros, d)
        devices.upsert(nd)
        Session.device = nd
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        TopBar("الوصول من خارج الشبكة", Session.device?.name, onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث") { refresh() }
        }
        CloudContent(
            ui = ui,
            onEnable = { enable() },
            onRepair = { enable() },
            onDisable = { confirmOff = true },
            onCopy = { label, text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
            },
            onOpen = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        )
    }

    if (confirmOff) {
        ConfirmDialog(
            "إلغاء الوصول من الخارج",
            "سيُحذف النفق من الراوتر ولن تستطيع الدخول لهذا الجهاز من خارج الشبكة حتى تفعّله مجدداً.",
            "إلغاء الوصول",
            onConfirm = { confirmOff = false; disable() },
            onDismiss = { confirmOff = false }
        )
    }
}

@Composable
fun CloudContent(
    ui: CloudUi,
    onEnable: () -> Unit = {},
    onRepair: () -> Unit = {},
    onDisable: () -> Unit = {},
    onCopy: (String, String) -> Unit = { _, _ -> },
    onOpen: (String) -> Unit = {}
) {
    val t = MaterialTheme.typography
    val info = ui.info
    val enabled = info?.enabled == true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = D.screen)
            .padding(bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md)
    ) {
        AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Cloud, C.Blue, size = 52.dp, iconSize = 30.dp, circle = true)
                Spacer(Modifier.width(D.md))
                Column(Modifier.weight(1f)) {
                    Text("Shamel Cloud", color = C.Text, style = t.titleMedium)
                    Text(
                        "ادخل إلى شبكتك من أي مكان عبر الإنترنت — بدون IP عام وبدون فتح منافذ عند مزودك.",
                        color = C.Dim,
                        style = t.bodySmall
                    )
                }
            }
        }

        if (!ui.loggedIn) {
            Notice("سجّل الدخول إلى حسابك في التطبيق لتفعيل هذه الميزة.", C.Gold)
            return@Column
        }

        AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
            SectionTitle("الحالة")
            if (ui.loading && info == null) {
                Box(Modifier.fillMaxWidth().padding(D.lg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = C.Teal, strokeWidth = 2.5.dp)
                }
            } else {
                StatusRow(
                    "الوصول من الخارج",
                    if (enabled) "مفعّل" else "غير مفعّل",
                    if (enabled) C.Green else C.Dim
                )
                if (enabled) {
                    val up = ui.tunnel?.running == true || info?.online == true
                    StatusRow("النفق مع السيرفر", if (up) "متصل" else "غير متصل بعد", if (up) C.Green else C.Gold)
                }
                StatusRow(
                    "اتصالك الآن",
                    if (ui.viaCloud) "عبر السحابة" else "من داخل الشبكة",
                    if (ui.viaCloud) C.Blue else C.Teal
                )
            }
        }

        if (enabled && info != null) {
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
                SectionTitle("عناوين الدخول من الخارج")
                AddressRow("التطبيق (API)", "${info.server}:${info.apiPort}", onCopy)
                AddressRow("Winbox", "${info.server}:${info.winboxPort}", onCopy)
                ui.umUrl?.let { url ->
                    AddressRow(
                        "يوزر منجر من المتصفح",
                        url.removePrefix("http://"),
                        onCopy,
                        copyValue = url,
                        onOpen = { onOpen(url) }
                    )
                }
                Text(
                    "التطبيق يتحول لهذا العنوان تلقائياً عندما تكون خارج الشبكة — لا تحتاج تغيير أي شيء.",
                    Modifier.padding(top = D.sm),
                    color = C.Dim,
                    style = t.bodySmall
                )
            }
        }

        ui.error?.let { Notice(it, C.Red) }

        if (ui.busy != null) {
            AppButton(ui.busy, {}, Modifier.fillMaxWidth(), loading = true)
        } else if (!enabled) {
            AppButton(
                "تفعيل الوصول من الخارج",
                onEnable,
                Modifier.fillMaxWidth().height(50.dp),
                icon = Icons.Filled.CloudDone,
                enabled = !ui.viaCloud
            )
            if (ui.viaCloud) {
                Text("للتفعيل أول مرة يجب أن تكون متصلاً من داخل الشبكة.", color = C.Gold, style = t.bodySmall)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                AppButton(
                    "إعادة الإعداد",
                    onRepair,
                    Modifier.weight(1f),
                    BtnKind.Secondary,
                    Icons.Filled.Build,
                    enabled = !ui.viaCloud
                )
                AppButton("إلغاء الوصول", onDisable, Modifier.weight(1f), BtnKind.Danger, Icons.Filled.CloudOff)
            }
        }

        AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
            SectionTitle("كيف يعمل؟")
            listOf(
                "يُضاف على الراوتر نفق مشفّر (L2TP/IPsec أو SSTP حسب شبكتك) إلى سيرفر ميكروتك الشامل.",
                "إنترنت المشتركين لا يمر عبر السيرفر — النفق للتحكم فقط.",
                "كل شبكة معزولة عن غيرها، ولها منفذ خاص بها.",
                "يعمل مع RouterOS 6 و 7، حتى لو كان مزودك يعطيك IP خاص (CGNAT)."
            ).forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        Modifier.size(16.dp).padding(top = 2.dp),
                        tint = C.Teal
                    )
                    Spacer(Modifier.width(D.sm))
                    Text(line, color = C.Text, style = t.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = C.Dim, style = MaterialTheme.typography.bodyMedium)
        Pill(value, color)
    }
}

@Composable
private fun AddressRow(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit,
    copyValue: String = value,
    onOpen: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C.Dim, style = MaterialTheme.typography.bodySmall)
            // العناوين دائماً من اليسار لليمين
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    value,
                    Modifier.fillMaxWidth(),
                    color = C.Text,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen) {
                Icon(Icons.Filled.OpenInBrowser, "فتح في المتصفح", tint = C.Blue)
            }
        }
        IconButton(onClick = { onCopy(label, copyValue) }) {
            Icon(Icons.Filled.ContentCopy, "نسخ", tint = C.Teal)
        }
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    AppCard(
        Modifier.fillMaxWidth(),
        border = color.copy(alpha = 0.5f),
        background = color.copy(alpha = 0.08f),
        padding = PaddingValues(D.md)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
