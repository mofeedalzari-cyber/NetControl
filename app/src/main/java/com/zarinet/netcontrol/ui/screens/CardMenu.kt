package com.zarinet.netcontrol.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.CardOps
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.RosTime
import com.zarinet.netcontrol.util.UmUserRow
import kotlinx.coroutines.launch

private enum class Stage { MENU, RENEW, DELETE, DISABLE }

private data class MenuItem(val label: String, val icon: ImageVector, val color: Color, val action: () -> Unit)

/**
 * قائمة الضغط المطوّل على الكرت: تجديد، تعديل، تصفير، مشاركة، ربط، فك الربط، تعطيل/تفعيل، حذف.
 * @param onEdit تعديل الكرت (نموذج الشاشة نفسها)
 */
@Composable
fun CardMenuHost(card: UmUserRow, ops: CardOps, onClose: () -> Unit, onChanged: () -> Unit, onEdit: (UmUserRow) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.MENU) }
    var busy by remember { mutableStateOf(false) }

    fun run(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                com.zarinet.netcontrol.ui.components.toast(ctx, done, Toast.LENGTH_SHORT).show()
                onClose(); onChanged()
            } catch (e: Exception) {
                com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show()
                busy = false
            }
        }
    }

    fun share() {
        val net = Session.device?.name.orEmpty()
        val text = buildString {
            append("🎫 كرت إنترنت")
            if (net.isNotBlank()) append(" — ").append(net)
            append("\nرقم الكرت: ").append(card.name)
            if (card.password.isNotBlank() && card.password != card.name) append("\nكلمة المرور: ").append(card.password)
            if (card.profile.isNotBlank()) append("\nالباقة: ").append(card.profile)
        }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
            "مشاركة الكرت").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onClose()
    }

    when (stage) {
        Stage.MENU -> {
            val items = listOf(
                MenuItem("تجديد الاشتراك", Icons.Filled.Autorenew, Color(0xFFB5C22E)) { stage = Stage.RENEW },
                MenuItem("تعديل", Icons.Filled.Edit, Color(0xFFB04DE8)) { onClose(); onEdit(card) },
                MenuItem("تصفير العدادات", Icons.Filled.RestartAlt, Color(0xFFE0457B)) { run("تم تصفير العدادات") { ops.reset(card) } },
                MenuItem("مشاركة", Icons.Filled.Share, Color(0xFFD03FC8)) { share() },
                MenuItem("ربط عند أول استخدام", Icons.Filled.PhonelinkLock, Color(0xFF3B7FE0)) { run("سيرتبط الكرت بأول جهاز يدخل به") { ops.bind(card) } },
                MenuItem("فك الربط", Icons.Filled.LockOpen, Color(0xFF22B8E0)) { run("تم فك الربط") { ops.unbind(card) } },
                if (card.disabled) MenuItem("تفعيل", Icons.Filled.CheckCircle, C.Green) { run("تم التفعيل") { ops.setDisabled(card, false) } }
                else MenuItem("تعطيل", Icons.Filled.Block, Color(0xFFE53935)) { stage = Stage.DISABLE },
                MenuItem("حذف", Icons.Filled.Delete, Color(0xFFD81B8C)) { stage = Stage.DELETE },
            )
            AppDialog(Fmt.ltr(card.name), onDismiss = onClose, buttons = { AppButton("إغلاق", onClose, kind = BtnKind.Ghost) }) {
                Text(listOfNotNull(card.profile.ifBlank { null }, card.view().label.removePrefix("ال"),
                    card.remainingMs?.let { RosTime.left(it) }).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = C.Dim)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Teal, trackColor = C.CardHi)
                Column {
                    items.forEach { it ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusSmall)).clickable(enabled = !busy, onClick = it.action)
                                .padding(vertical = 12.dp, horizontal = D.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(it.icon, null, tint = it.color, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(D.lg))
                            Text(it.label, style = MaterialTheme.typography.titleMedium, color = C.Text)
                        }
                    }
                }
            }
        }
        Stage.RENEW -> {
            var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
            var profile by remember { mutableStateOf(card.profile) }
            LaunchedEffect(Unit) {
                profiles = runCatching { ops.profiles() }.getOrDefault(emptyList())
                if (profile.isBlank()) profile = profiles.firstOrNull().orEmpty()
            }
            FormDialog("تجديد الاشتراك", confirmText = "تجديد", enabled = !busy && profile.isNotBlank(), onDismiss = onClose,
                onConfirm = { run("تم تجديد ${card.name}") { ops.renew(card, profile) } }) {
                Text("رقم الكرت: ${card.name}", style = MaterialTheme.typography.titleMedium, color = C.Text)
                Dropdown("الباقة", profile, profiles) { profile = it }
                Text("يُصفّر الاستخدام ويبدأ الاشتراك من جديد عند أول دخول، ويُفعَّل الكرت إن كان معطلاً.",
                    style = MaterialTheme.typography.bodySmall, color = C.Dim)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Teal, trackColor = C.CardHi)
            }
        }
        Stage.DELETE -> ConfirmDialog("حذف الكرت", "هل أنت متأكد أنك تريد حذف ${card.name}؟ لا يمكن التراجع.", "حذف",
            onConfirm = { run("تم الحذف") { ops.remove(card) } }, onDismiss = onClose)
        Stage.DISABLE -> ConfirmDialog("تعطيل الكرت", "هل أنت متأكد أنك تريد تعطيل ${card.name}؟", "تعطيل",
            onConfirm = { run("تم التعطيل") { ops.setDisabled(card, true) } }, onDismiss = onClose)
    }
}

/** تعديل كرت يوزر منجر: رقم الكرت وكلمة المرور والملاحظة. */
@Composable
fun UmEditDialog(card: UmUserRow, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(card.name) }
    var pass by remember { mutableStateOf(card.password) }
    var comment by remember { mutableStateOf(card.comment) }
    FormDialog("تعديل ${card.name}", enabled = name.isNotBlank(), onDismiss = onDismiss, onConfirm = {
        scope.launch {
            try {
                val v6 = com.zarinet.netcontrol.util.UserManager.isV6()
                val base = if (v6) "/tool/user-manager" else "/user-manager"
                val p = linkedMapOf(".id" to card.id, "password" to pass, "comment" to comment)
                // تغيير رقم الكرت: الحقل username في v6 و name في v7
                if (name.trim() != card.name) p[if (v6) "username" else "name"] = name.trim()
                Session.api().exec("$base/user/set", p)
                com.zarinet.netcontrol.ui.components.toast(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                onDismiss(); onDone()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }) {
        Field(name, { name = it }, "رقم الكرت", ltr = true)
        Field(pass, { pass = it }, "كلمة المرور", ltr = true)
        Field(comment, { comment = it }, "الملاحظة / الدفعة", ltr = true)
    }
}
