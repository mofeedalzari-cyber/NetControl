package com.zarinet.netcontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.account.AuthService
import com.zarinet.netcontrol.account.SignUpResult
import com.zarinet.netcontrol.subscription.SubscriptionConfig
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.launch

/** أول شاشة: إنشاء حساب أو تسجيل الدخول. الأجهزة والاشتراك يُحفظان على الحساب. */
@Composable
fun AuthScreen(onLoggedIn: () -> Unit, initialSignUp: Boolean = false, previewForgot: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var signUp by remember { mutableStateOf(initialSignUp) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // سبب الخروج الإجباري (مثلاً: تم تسجيل الدخول من جهاز آخر) يظهر كرسالة خطأ عند فتح الشاشة
    val kicked by AuthService.kicked.collectAsState()
    var error by remember { mutableStateOf<String?>(kicked) }
    var info by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableIntStateOf(previewForgot) }   // 0 = لا، 1 = البريد، 2 = الرمز، 3 = كلمة مرور جديدة
    val t = MaterialTheme.typography
    if (forgot > 0) {
        ForgotPassword(forgot, email, onStep = { forgot = it }, onEmail = { email = it }, onDone = onLoggedIn)
        return
    }

    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    val valid = emailOk && pass.length >= 6 && (!signUp || pass == pass2)

    fun submit() {
        if (!valid || busy) return
        busy = true; error = null; info = null
        scope.launch {
            try {
                if (signUp) {
                    when (AuthService.signUp(email, pass)) {
                        SignUpResult.LoggedIn -> onLoggedIn()
                        SignUpResult.ConfirmEmail -> { info = "تم إنشاء الحساب ✅ — افتح بريدك واضغط رابط التأكيد ثم سجّل الدخول."; signUp = false }
                    }
                } else {
                    AuthService.signIn(email, pass); onLoggedIn()
                }
            } catch (e: Exception) { error = e.message ?: "حدث خطأ" }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(top = 40.dp, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppLogo(84.dp, 24.dp)
        Text("ميكروتك الشامل", style = t.headlineMedium, color = C.Text)
        Text("إدارة MikroTik من هاتفك — أجهزتك محفوظة على حسابك", style = t.bodyMedium, color = C.Dim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(D.sm))
        SegTabs(listOf("تسجيل الدخول", "إنشاء حساب"), if (signUp) 1 else 0) { signUp = it == 1; error = null }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(D.md)) {
                Field(email, { email = it.trim() }, "البريد الإلكتروني", ltr = true, placeholder = "name@gmail.com")
                Field(pass, { pass = it }, "كلمة المرور", password = true)
                if (signUp) {
                    Field(pass2, { pass2 = it }, "تأكيد كلمة المرور", password = true)
                    val hint = when {
                        pass.isNotEmpty() && pass.length < 6 -> "كلمة المرور 6 أحرف على الأقل"
                        pass2.isNotEmpty() && pass != pass2 -> "كلمتا المرور غير متطابقتين"
                        else -> null
                    }
                    hint?.let { Text(it, style = t.bodySmall, color = C.Gold) }
                }
                error?.let { Text(it, style = t.bodyMedium, color = C.Red) }
                info?.let { Text(it, style = t.bodyMedium, color = C.Online) }
                AppButton(if (signUp) "إنشاء الحساب" else "تسجيل الدخول", ::submit, Modifier.fillMaxWidth().height(48.dp),
                    icon = if (signUp) Icons.Filled.PersonAdd else Icons.Filled.Login, enabled = valid, loading = busy)
            }
        }
        if (signUp) Text("كل حساب جديد يحصل على تجربة مجانية ${SubscriptionConfig.TRIAL_DAYS} أيام.", style = t.bodySmall, color = C.Teal,
            textAlign = TextAlign.Center)
        if (!signUp) TextButton({ forgot = 1; error = null }) {
            Text("نسيت كلمة المرور؟", style = t.titleSmall, color = C.Teal)
        }
        Text("🔒 كلمات مرور أجهزة MikroTik تُشفَّر على هاتفك قبل حفظها في حسابك.", style = t.labelSmall, color = C.Faint, textAlign = TextAlign.Center)
    }
}

/** نسيت كلمة المرور: المستخدم يرسل بريده للمدير عبر واتساب، والمدير يعيّن له كلمة مرور جديدة من «إدارة الاشتراكات». */
@Composable
private fun ForgotPassword(step: Int, email0: String, onStep: (Int) -> Unit, onEmail: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t = MaterialTheme.typography
    var email by remember { mutableStateOf(email0) }
    var sent by remember { mutableStateOf(step >= 2) }
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(top = 24.dp, bottom = D.xl),
        verticalArrangement = Arrangement.spacedBy(D.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onStep(0) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = C.Text) }
            Text("استعادة كلمة المرور", style = t.headlineSmall, color = C.Text)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(D.md)) {
                IconBadge(Icons.Filled.LockReset, C.Teal, 52.dp, 28.dp, circle = true)
                Text("أدخل البريد الإلكتروني لحسابك، وسيُرسَل طلبك إلى مدير التطبيق عبر واتساب ليعيّن لك كلمة مرور جديدة.",
                    style = t.bodyLarge, color = C.Dim)
                Field(email, { email = it.trim() }, "البريد الإلكتروني", ltr = true, placeholder = "name@gmail.com")
                AppButton("📱 إرسال الطلب عبر واتساب", {
                    onEmail(email.trim())
                    openWhatsApp(ctx, "السلام عليكم، نسيت كلمة مرور حسابي في تطبيق ميكروتك الشامل.\n📧 البريد: ${email.trim()}\nأرجو تعيين كلمة مرور جديدة لحسابي.")
                    sent = true
                }, Modifier.fillMaxWidth().height(48.dp), enabled = emailOk)
            }
        }
        if (sent) AppCard(Modifier.fillMaxWidth(), border = C.Online.copy(alpha = 0.6f)) {
            Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
                Text("✅ تم فتح واتساب", style = t.titleMedium, color = C.Text)
                Text("بعد أن يرسل لك المدير كلمة المرور الجديدة، ارجع وسجّل الدخول بها.", style = t.bodyMedium, color = C.Dim)
                AppButton("العودة لتسجيل الدخول", { onStep(0) }, Modifier.fillMaxWidth(), BtnKind.Secondary, Icons.Filled.Login)
            }
        }
        Text("رقم المدير: ${Fmt.ltr(SubscriptionConfig.WHATSAPP_DISPLAY)}", style = t.labelMedium, color = C.Faint,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
