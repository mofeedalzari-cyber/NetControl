package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
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
import com.zarinet.netcontrol.util.FairSpeed
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.launch

/**
 * تقسيم السرعة حسب Active (Dynamic Fair Share عبر PCQ) — محتوى بطاقة داخل «الإضافات».
 * [refreshKey]: يتغير عند ضغط «تحديث» أعلى الإضافات فتُعاد قراءة الحالة.
 */
/** قيم ابتدائية للحقول (للمعاينات/لقطات الشرح فقط؛ null في التطبيق). */
internal var fairSpeedPreset: List<String>? = null

@Composable
fun FairSpeedPanel(refreshKey: Int = 0, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<FairSpeed.Status?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }   // (نجاح؟، الرسالة)
    var confirmRemove by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }

    var network by remember { mutableStateOf(fairSpeedPreset?.getOrNull(0).orEmpty()) }
    var down by remember { mutableStateOf(fairSpeedPreset?.getOrNull(1).orEmpty()) }
    var up by remember { mutableStateOf(fairSpeedPreset?.getOrNull(2).orEmpty()) }
    var downRate by remember { mutableStateOf(fairSpeedPreset?.getOrNull(3).orEmpty()) }
    var upRate by remember { mutableStateOf(fairSpeedPreset?.getOrNull(4).orEmpty()) }
    var downCls by remember { mutableStateOf(fairSpeedPreset?.getOrNull(5) ?: "dst-address") }
    var upCls by remember { mutableStateOf(fairSpeedPreset?.getOrNull(6) ?: "src-address") }
    var showErrors by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true; loadError = null
        try {
            val s = FairSpeed.status(Session.api())
            status = s
            if (!prefilled && s.active) {
                network = s.target
                if (s.downBps > 0) down = FairSpeed.bpsToMbpsText(s.downBps)
                if (s.upBps > 0) up = FairSpeed.bpsToMbpsText(s.upBps)
                downRate = if (s.downRate > 0) FairSpeed.bpsToMbpsText(s.downRate) else ""
                upRate = if (s.upRate > 0) FairSpeed.bpsToMbpsText(s.upRate) else ""
                downCls = s.downClassifier; upCls = s.upClassifier
                prefilled = true
            }
        } catch (e: Exception) { loadError = e.message ?: "خطأ" }
        loading = false
    }
    LaunchedEffect(refreshKey) { reload() }

    val v = FairSpeed.validate(network, down, up, downRate, upRate, downCls, upCls)
    val s = status
    val active = s?.active == true

    fun apply() {
        showErrors = true
        val plan = v.plan ?: return
        busy = if (active) "جاري تحديث الإعدادات..." else "جاري التفعيل..."
        result = null
        scope.launch {
            try {
                val r = FairSpeed.apply(Session.api(), plan)
                val parts = buildList {
                    if (r.created.isNotEmpty()) add("أُنشئ: ${r.created.joinToString("، ")}")
                    if (r.updated.isNotEmpty()) add("حُدِّث: ${r.updated.joinToString("، ")}")
                    if (r.removedDuplicates > 0) add("حُذفت نسخ مكررة: ${r.removedDuplicates}")
                }
                result = true to ("تم ${if (active) "التحديث" else "التفعيل"} بنجاح — ${Fmt.ltr(plan.target)} • ${rates(plan.downBps, plan.upBps)}" +
                    if (parts.isNotEmpty()) "\n" + parts.joinToString("\n") else "")
                toast(ctx, "تم ${if (active) "التحديث" else "التفعيل"} بنجاح", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                result = false to (e.message ?: "خطأ غير معروف")
                toast(ctx, "فشلت العملية", Toast.LENGTH_SHORT).show()
            }
            busy = null
            reload(); onChanged()
        }
    }

    fun remove() {
        busy = "جاري إلغاء التقسيم وحذف إعداداته..."; result = null
        scope.launch {
            try {
                val r = FairSpeed.remove(Session.api())
                result = if (r.success) {
                    true to (if (r.removed.isEmpty()) "لا توجد إعدادات للميزة على الراوتر."
                        else "تم الإلغاء وحذف: ${r.removed.joinToString("، ")}\nتم التحقق: لم يبقَ شيء من إعدادات الميزة، ولم تُلمس أي إعدادات أخرى.")
                } else {
                    false to buildString {
                        append("لم يكتمل الحذف.")
                        if (r.removed.isNotEmpty()) append("\nحُذف: ${r.removed.joinToString("، ")}")
                        r.errors.forEach { append("\n✕ ").append(it) }
                        if (r.remaining.isNotEmpty()) append("\nما زال موجوداً: ${r.remaining.joinToString("، ") { FairSpeed.label(it) }}")
                    }
                }
                if (r.success) { network = ""; down = ""; up = ""; showErrors = false; prefilled = false }
                toast(ctx, if (r.success) "تم إلغاء التقسيم" else "فشل حذف بعض العناصر", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                result = false to (e.message ?: "خطأ غير معروف")
            }
            busy = null
            reload(); onChanged()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
        // ── الحالة ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    s == null -> ""
                    s.active -> "${Fmt.ltr(s.target)} • ${rates(s.downBps, s.upBps)}"
                    else -> "يقسم السرعة الكلية بالتساوي على الأجهزة النشطة فقط (PCQ)."
                },
                style = MaterialTheme.typography.bodySmall, color = C.Dim, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(D.sm))
            when {
                loading && s == null -> CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
                s == null -> Pill("غير معروف", C.Dim)
                !s.active -> Pill("غير مفعّل", C.Dim)
                s.disabled -> Pill("معطّل", C.Gold)
                !s.complete -> Pill("ناقص", C.Red)
                else -> Pill("مفعّل", C.Green)
            }
        }
        loadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.Red) }
        s?.let {
            buildList {
                if (it.active && !it.complete) add("بعض إعدادات التقسيم ناقصة — اضغط «تحديث» لإكمالها.")
                if (it.duplicates > 0) add("توجد ${it.duplicates} نسخة مكررة — ستُحذف عند «تحديث».")
                if (it.fasttrack) add("FastTrack مفعّل: الاتصالات المسرّعة تتجاوز الكيوز فلا يُطبَّق عليها التقسيم.")
                if (it.active && it.before > 0) add("يوجد ${it.before} كيو قبل كيو التقسيم؛ الأجهزة التي تطابقها أولاً تأخذ سرعتها منها.")
            }.forEach { w ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = C.Gold)
                    Spacer(Modifier.width(D.xs))
                    Text(w, style = MaterialTheme.typography.bodySmall, color = C.Dim)
                }
            }
        }

        // ── المدخلات ──
        Field(network, { network = it.trim() }, "نطاق العناوين", placeholder = "192.168.1.0/24", ltr = true)
        FieldError(if (showErrors || network.isNotBlank()) v.errors["network"] else null)
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Column(Modifier.weight(1f)) {
                Field(down, { down = it }, "تنزيل كلي (Mbps)", number = true, placeholder = "300")
                FieldError(if (showErrors || down.isNotBlank()) v.errors["down"] else null)
            }
            Column(Modifier.weight(1f)) {
                Field(up, { up = it }, "رفع كلي (Mbps)", number = true, placeholder = "300")
                FieldError(if (showErrors || up.isNotBlank()) v.errors["up"] else null)
            }
        }
        // ── Queue Types (PCQ): Rate لكل جهاز + المصنِّف ──
        Text("Queue Types — PCQ", style = MaterialTheme.typography.labelLarge, color = C.Text)
        PcqTypeRow("التنزيل", downRate, { downRate = it }, downCls, { downCls = it },
            if (showErrors || downRate.isNotBlank()) v.errors["downRate"] else null)
        PcqTypeRow("الرفع", upRate, { upRate = it }, upCls, { upCls = it },
            if (showErrors || upRate.isNotBlank()) v.errors["upRate"] else null)
        Text("Rate فارغ أو 0 = تقسيم الإجمالي بالتساوي على الأجهزة النشطة. وتُربط القائمتان تلقائياً بالـ Simple Queue التي تُضاف في أعلى القائمة.",
            style = MaterialTheme.typography.bodySmall, color = C.Faint)
        v.plan?.let { p ->
            Text(
                "لكل جهاز عند ${Fmt.ltr("3")} أجهزة نشطة: ${rates(FairSpeed.perClient(p.downBps, 3, p.downRate), FairSpeed.perClient(p.upBps, 3, p.upRate))}\n" +
                    "لكل جهاز عند ${Fmt.ltr("6")} أجهزة نشطة: ${rates(FairSpeed.perClient(p.downBps, 6, p.downRate), FairSpeed.perClient(p.upBps, 6, p.upRate))}",
                style = MaterialTheme.typography.bodySmall, color = C.Dim
            )
        }

        // ── التنفيذ / النتيجة ──
        busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
                Spacer(Modifier.width(D.sm)); Text(it, style = MaterialTheme.typography.bodyMedium, color = C.Dim)
            }
        }
        result?.let { (ok, msg) ->
            val col = if (ok) C.Green else C.Red
            AppCard(Modifier.fillMaxWidth(), border = col.copy(alpha = 0.5f), background = col.copy(alpha = 0.08f), padding = PaddingValues(D.md)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(20.dp), tint = col)
                    Spacer(Modifier.width(D.sm))
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = C.Text)
                }
            }
        }

        val idle = busy == null && !loading && s != null
        if (!active) {
            AppButton("تفعيل التقسيم", ::apply, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow, enabled = idle, loading = busy?.startsWith("جاري التفعيل") == true)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                AppButton("تحديث", ::apply, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.Sync, enabled = idle)
                AppButton("إلغاء التقسيم", { confirmRemove = true }, Modifier.weight(1f), BtnKind.Danger, Icons.Filled.DeleteForever, enabled = idle)
            }
        }
        // بقايا ناقصة بدون الكيو الرئيسية (مثل PCQ فقط) ⇒ نسمح بحذفها
        if (!active && (s?.typeDown != null || s?.typeUp != null)) {
            AppButton("حذف بقايا إعدادات التقسيم", { confirmRemove = true }, Modifier.fillMaxWidth(), BtnKind.Danger, Icons.Filled.DeleteForever, enabled = idle)
        }
    }

    if (confirmRemove) ConfirmDialog(
        "إلغاء تقسيم السرعة",
        "سيتم حذف الإعدادات التي أضافها التطبيق فقط: كيو ${Fmt.ltr("Simple Queue")} الخاصة بالتقسيم وقائمتا ${Fmt.ltr("PCQ")} " +
            "(الوسم ${Fmt.ltr(FairSpeed.TAG)}). لن تُحذف أي كيوز أو إعدادات أخرى.",
        confirm = "إلغاء التقسيم", onConfirm = ::remove, onDismiss = { confirmRemove = false }
    )
}

/** «تنزيل 300M • رفع 300M» باتجاه ثابت. */
private fun rates(down: Long, up: Long) =
    "تنزيل ${Fmt.ltr(FairSpeed.bpsToMbpsText(down) + "M")} • رفع ${Fmt.ltr(FairSpeed.bpsToMbpsText(up) + "M")}"

/** صف نوع PCQ: حقل Rate + اختيار Src. Address / Dst. Address. */
@Composable
private fun PcqTypeRow(label: String, rate: String, onRate: (String) -> Unit, cls: String, onCls: (String) -> Unit, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(D.xs)) {
        Field(rate, onRate, "Rate $label لكل جهاز — Mbps", number = true, placeholder = "0")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            Text("المصنِّف ($label):", style = MaterialTheme.typography.bodySmall, color = C.Dim, modifier = Modifier.weight(1f))
            listOf("src-address" to "Src. Address", "dst-address" to "Dst. Address").forEach { (k, t) ->
                val sel = cls == k
                Text(t, style = MaterialTheme.typography.labelMedium, color = if (sel) C.OnTeal else C.Dim, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(D.radiusSmall)).background(if (sel) C.Teal else C.CardHi)
                        .clickable { onCls(k) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        FieldError(error)
    }
}

@Composable
private fun FieldError(msg: String?) {
    if (msg != null) Text(msg, style = MaterialTheme.typography.bodySmall, color = C.Red, modifier = Modifier.padding(start = D.xs))
}
