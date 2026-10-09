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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.account.AccountActions
import com.zarinet.netcontrol.account.AuthService
import com.zarinet.netcontrol.ui.L10n
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.AppTheme
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.launch

/** إعدادات الحساب: تغيير كلمة المرور، الوضع الليلي/النهاري، اللغة، تسجيل الخروج. */
@Composable
fun AccountSettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit, previewEmail: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by AuthService.session.collectAsState()
    val email = previewEmail ?: session?.email.orEmpty()
    val t = MaterialTheme.typography
    var cur by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("إعدادات الحساب", null, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.md)
        ) {
            // ── الحساب ──
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.AccountCircle, C.Teal, 44.dp, 26.dp, circle = true)
                    Spacer(Modifier.width(D.sm))
                    Column(Modifier.weight(1f)) {
                        Text("الحساب", style = t.bodySmall, color = C.Dim)
                        Text(Fmt.ltr(email), style = t.titleMedium, color = C.Text, maxLines = 1)
                    }
                }
            }

            // ── تغيير كلمة المرور ──
            SectionTitle("تغيير كلمة المرور")
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(D.md)) {
                    Field(cur, { cur = it }, "كلمة المرور الحالية", password = true)
                    Field(p1, { p1 = it }, "كلمة المرور الجديدة", password = true)
                    Field(p2, { p2 = it }, "إعادة كتابة كلمة المرور الجديدة", password = true)
                    when {
                        p1.isNotEmpty() && p1.length < 6 -> "كلمة المرور 6 أحرف على الأقل"
                        p2.isNotEmpty() && p1 != p2 -> "كلمتا المرور غير متطابقتين"
                        else -> null
                    }?.let { Text(it, style = t.bodySmall, color = C.Gold) }
                    error?.let { Text(it, style = t.bodyMedium, color = C.Red) }
                    AppButton("حفظ كلمة المرور", {
                        busy = true; error = null
                        scope.launch {
                            try {
                                AccountActions.changePassword(ctx, cur, p1)
                                cur = ""; p1 = ""; p2 = ""
                                toast(ctx, "تم تغيير كلمة المرور ✅", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) { error = e.message ?: "حدث خطأ" }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth().height(46.dp), icon = Icons.Filled.LockReset,
                        enabled = cur.isNotEmpty() && p1.length >= 6 && p1 == p2, loading = busy)
                }
            }

            // ── المظهر ──
            SectionTitle("المظهر")
            SegTabs(listOf("☀️ نهاري", "🌙 ليلي", "حسب الهاتف"), when (AppTheme.mode) { "dark" -> 1; "system" -> 2; else -> 0 }) {
                AppTheme.set(ctx, when (it) { 1 -> "dark"; 2 -> "system"; else -> "light" })
            }

            // ── اللغة ──
            SectionTitle("اللغة")
            SegTabs(listOf("العربية", "English"), if (L10n.isEn) 1 else 0) { L10n.set(ctx, if (it == 1) "en" else "ar") }

            Spacer(Modifier.height(D.sm))
            AppButton("تسجيل الخروج", { confirmLogout = true }, Modifier.fillMaxWidth().height(46.dp), BtnKind.Danger, Icons.Filled.Logout)
        }
    }
    if (confirmLogout) ConfirmDialog("تسجيل الخروج", "هل تريد تسجيل الخروج من $email؟\nأجهزتك تبقى محفوظة في حسابك.", "خروج",
        onConfirm = { scope.launch { AccountActions.logout(ctx); onLoggedOut() } }, onDismiss = { confirmLogout = false })
}
