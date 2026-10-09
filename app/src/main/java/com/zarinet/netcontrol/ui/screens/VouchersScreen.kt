package com.zarinet.netcontrol.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val CHARSETS = linkedMapOf(
    "أرقام" to "0123456789",
    "حروف صغيرة" to "abcdefghjkmnpqrstuvwxyz",
    "أرقام وحروف" to "abcdefghjkmnpqrstuvwxyz23456789"
)
private val PASS_DIGITS = listOf("بدون", "3", "4", "5", "6", "8")
private val VALID_UNITS = linkedMapOf("بدون" to "", "ساعة" to "h", "يوم" to "d", "أسبوع" to "w")
private val TIME_UNITS = linkedMapOf("بدون" to "", "دقيقة" to "m", "ساعة" to "h", "يوم" to "d")
private val DATA_UNITS = linkedMapOf("ميجا" to 1048576L, "جيجا" to 1073741824L)
private val COLORS = listOf(
    "أسود" to Color.Black, "أبيض" to Color.White, "أحمر" to Color(0xFFD32F2F), "أزرق" to Color(0xFF1565C0),
    "أخضر" to Color(0xFF2E7D32), "ذهبي" to Color(0xFFE6B950), "بنفسجي" to Color(0xFF6A1B9A),
    "خمري" to Color(0xFF8B0A0A), "بنفسجي داكن" to Color(0xFF6B0F85), "كحلي" to Color(0xFF1E2A78),
    "برتقالي" to Color(0xFFEF6C00), "رمادي" to Color(0xFF616161), "فيروزي" to Color(0xFF00897B)
)

/** إعدادات الدفعة (تُحفظ وتُسترجع). */
data class VSettings(
    val profile: String = "",
    val validNum: String = "1", val validUnit: String = "بدون",
    val timeNum: String = "1", val timeUnit: String = "بدون",
    val dataNum: String = "", val dataUnit: String = "جيجا",
    val network: String = "", val timeText: String = "", val price: String = "",
    val digits: String = "8", val charset: String = "أرقام", val prefix: String = "",
    val count: String = "100", val bind: Boolean = false,
    val passDigits: String = "بدون", val pos: String = "", val note1: String = "", val note2: String = "", val serialStart: String = "1",
    val dataLabel: String = "", val validLabel: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("profile", profile); put("validNum", validNum); put("validUnit", validUnit); put("timeNum", timeNum); put("timeUnit", timeUnit)
        put("dataNum", dataNum); put("dataUnit", dataUnit); put("network", network); put("timeText", timeText); put("price", price)
        put("digits", digits); put("charset", charset); put("prefix", prefix); put("count", count); put("bind", bind)
        put("passDigits", passDigits); put("pos", pos); put("note1", note1); put("note2", note2); put("serialStart", serialStart)
        put("dataLabel", dataLabel); put("validLabel", validLabel)
    }.toString()

    val validity: String get() = VALID_UNITS[validUnit].orEmpty().let { u -> if (u.isEmpty() || validNum.isBlank()) "" else "$validNum$u" }
    val limitUptime: String get() = TIME_UNITS[timeUnit].orEmpty().let { u -> if (u.isEmpty() || timeNum.isBlank()) "" else "$timeNum$u" }
    val limitBytes: Long get() = (dataNum.toLongOrNull() ?: 0L) * (DATA_UNITS[dataUnit] ?: 0L)
    /** نصوص تظهر على الكرت. */
    val validText: String get() = if (validUnit == "بدون" || validNum.isBlank()) "" else "$validNum $validUnit"
    val uptimeText: String get() = if (timeUnit == "بدون" || timeNum.isBlank()) "" else "$timeNum $timeUnit"
    val dataText: String get() = if (dataNum.isBlank() || dataNum == "0") "" else "$dataNum $dataUnit"

    /** النص المطبوع للوقت المسموح: النص اليدوي أولاً، وإلا المحسوب من حد المدة (هوتسبوت فقط). */
    fun allowedOn(um: Boolean): String = timeText.ifBlank { if (um) "" else uptimeText }
    /** النص المطبوع لحجم الرصيد. */
    fun balanceOn(um: Boolean): String = dataLabel.ifBlank { if (um) "" else dataText }
    /** النص المطبوع للصلاحية. */
    fun validOn(um: Boolean): String = validLabel.ifBlank { if (um) "" else validText }

    /** النص القابل للتعديل لعنصر في الكرت (null إن لم يكن له نص يدوي). */
    fun textOf(e: El): String? = when (e) {
        El.NETWORK -> network
        El.TIME -> timeText
        El.VALID -> validLabel
        El.DATA -> dataLabel
        El.PRICE -> price
        El.NOTE1 -> note1
        El.NOTE2 -> note2
        else -> null
    }

    fun withText(e: El, v: String): VSettings = when (e) {
        El.NETWORK -> copy(network = v)
        El.TIME -> copy(timeText = v)
        El.VALID -> copy(validLabel = v)
        El.DATA -> copy(dataLabel = v)
        El.PRICE -> copy(price = v)
        El.NOTE1 -> copy(note1 = v)
        El.NOTE2 -> copy(note2 = v)
        else -> this
    }

    companion object {
        fun fromJson(s: String, defNetwork: String): VSettings = try {
            val o = JSONObject(s)
            VSettings(o.optString("profile"), o.optString("validNum", "1"), o.optString("validUnit", "بدون"),
                o.optString("timeNum", "1"), o.optString("timeUnit", "بدون"), o.optString("dataNum"), o.optString("dataUnit", "جيجا").let { if (it.startsWith("ميجا")) "ميجا" else "جيجا" },
                o.optString("network", defNetwork), o.optString("timeText"), o.optString("price"), o.optString("digits", "8"),
                o.optString("charset", "أرقام"), o.optString("prefix"), o.optString("count", "100"), o.optBoolean("bind"),
                o.optString("passDigits", "بدون"), o.optString("pos"), o.optString("note1"), o.optString("note2"), o.optString("serialStart", "1"),
                o.optString("dataLabel"), o.optString("validLabel"))
        } catch (_: Exception) { VSettings(network = defNetwork) }
    }
}

/** نتيجة إنشاء ملف الطباعة (المسار في التنزيلات إن نجح النسخ). */
data class PrintDone(val file: File, val downloadsPath: String?, val count: Int)

@Composable
fun VouchersScreen(target: CardTarget = CardTarget.HOTSPOT, onBack: (() -> Unit)?,
                   preview: Pair<VSettings, CardDesign?>? = null) {
    val um = target == CardTarget.USERMAN
    val key = if (um) "_um" else ""
    val userPath = if (um) "/user-manager/user" else "/ip/hotspot/user"
    val profilePath = if (um) "/user-manager/profile" else "/ip/hotspot/user/profile"
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val scope = rememberCoroutineScope()
    val rnd = remember { SecureRandom() }

    var design by remember { mutableStateOf(preview?.second ?: CardDesign.fromJson(store.getString("card_design$key"))) }
    var st by remember { mutableStateOf(preview?.first ?: VSettings.fromJson(store.getString("v_settings$key", "{}"), Session.device?.name ?: "")) }
    var profiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var batches by remember { mutableStateOf<List<String>>(emptyList()) }
    var connected by remember { mutableStateOf(Session.client?.isConnected == true) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var status by remember { mutableStateOf("") }
    var lastPdf by remember { mutableStateOf<File?>(null) }
    var lastScript by remember { mutableStateOf<File?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    var showReprint by remember { mutableStateOf(false) }
    var confirmAdd by remember { mutableStateOf<Boolean?>(null) }
    var addPkg by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<PrintDone?>(null) }
    var showPrevious by remember { mutableStateOf(false) }

    // إذن الكتابة في التنزيلات (أندرويد 9 وما قبله فقط)
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureStorage() {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return
        runCatching { storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }

    /** ينشئ ملف PDF مع عرض التقدم، ثم ينسخه للتنزيلات ويعرض نافذة النتيجة. */
    suspend fun buildPdf(list: List<Voucher>, info: BatchInfo, tag: String): File {
        phase = "جاري تجهيز الملف"
        progress = 0 to 1
        val f = withContext(Dispatchers.IO) {
            VoucherPdf.build(ctx, list, design, info, tag) { page, pages ->
                phase = "جاري تجهيز الملف $page"
                progress = page to pages
            }
        }
        val where = withContext(Dispatchers.IO) { VoucherPdf.saveToDownloads(ctx, f) }
        done = PrintDone(f, where, list.size)
        return f
    }

    fun saveDesign(d: CardDesign) { design = d; store.putString("card_design$key", d.toJson()) }
    fun saveSt(s: VSettings) { st = s; store.putString("v_settings$key", s.toJson()) }

    // نصوص باقات يوزر منجر (الوقت/الرصيد/الصلاحية) لتعبئة الكرت تلقائياً
    var umTexts by remember { mutableStateOf<Map<String, UserManager.CardTexts>>(emptyMap()) }

    /** يملأ نصوص الكرت من الباقة المختارة؛ force=true يستبدل النصوص الموجودة. */
    fun withPkg(s: VSettings, force: Boolean): VSettings {
        val t = umTexts[s.profile] ?: return s
        return s.copy(
            timeText = if (force || s.timeText.isBlank()) t.time else s.timeText,
            dataLabel = if (force || s.dataLabel.isBlank()) t.data else s.dataLabel,
            validLabel = if (force || s.validLabel.isBlank()) t.valid else s.validLabel
        )
    }

    fun onSt(s: VSettings) {
        var v = s
        if (um && s.profile != st.profile) v = withPkg(v, true)
        saveSt(v)
    }

    LaunchedEffect(Unit) {
        try {
            val api = Session.api()
            if (um) {
                // نصوص الباقات تأتي مع أسمائها في رحلة واحدة
                umTexts = runCatching { UserManager.cardTexts() }.getOrDefault(emptyMap())
                profiles = umTexts.keys.toList().ifEmpty { UserManager.profileNames() }
            } else profiles = api.print(profilePath, listOf("name")).mapNotNull { it["name"] }
            if (st.profile.isBlank() || st.profile !in profiles) saveSt(st.copy(profile = profiles.firstOrNull() ?: "default"))
            if (um) saveSt(withPkg(st, false))
            connected = true
            // قائمة الدفعات (لإعادة الطباعة) بعد جاهزية الشاشة
            batches = runCatching {
                if (um) UserManager.comments() else api.print(userPath, listOf("comment")).mapNotNull { it["comment"]?.takeIf(String::isNotBlank) }.distinct().sorted()
            }.getOrDefault(emptyList())
        } catch (e: Exception) { status = "غير متصل: ${e.message}" }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                    val scaled = if (bmp.width > 1200) android.graphics.Bitmap.createScaledBitmap(bmp, 1200, (bmp.height * 1200f / bmp.width).toInt(), true) else bmp
                    val f = File(ctx.filesDir, "card_bg_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    ctx.filesDir.listFiles { x -> x.name.startsWith("card_bg_") && x != f }?.forEach { it.delete() }
                    f.absolutePath
                } catch (_: Exception) { null }
            }
            if (path != null) {
                val t = Templates.imageTemplate
                saveDesign(design.copy(template = "image", imagePath = path, userColor = t.userColor, textColor = t.textColor))
            } else com.zarinet.netcontrol.ui.components.toast(ctx, "تعذر فتح الصورة", Toast.LENGTH_SHORT).show()
        }
    }

    fun randomCode(chars: String, len: Int) = buildString { repeat(len) { append(chars[rnd.nextInt(chars.length)]) } }

    fun batchInfo(tag: String): BatchInfo {
        val comment = buildString {
            if (!um) append("<")
            append(tag)
            if (st.pos.isNotBlank()) append(";pos=").append(st.pos.replace(";", " "))
            Sales.priceOf(st.price).takeIf { it > 0 }?.let { append(";price=").append(it) }
        }
        return BatchInfo(target, tag, st.profile, st.network, st.allowedOn(um), st.price,
            if (um) "" else st.limitUptime, if (um) 0 else st.limitBytes, comment,
            st.note1, st.note2, st.serialStart.toLongOrNull() ?: 1, st.bind,
            validText = st.validOn(um), uptimeText = "", dataText = st.balanceOn(um),
            // الصلاحية وربط الجهاز تُحفظ في حقل email ويطبقها نظام الصلاحية على الراوتر
            email = if (um || (st.validity.isBlank() && !st.bind)) "" else ZariValidity.emailFor(st.validity, st.bind))
    }

    /** يولد الكروت؛ addToRouter=true يضيفها عبر API، وإلا يصدرها سكربت .rsc. */
    fun generate(addToRouter: Boolean) {
        val n = st.count.toIntOrNull()?.coerceIn(1, 5000) ?: return
        val len = st.digits.toIntOrNull()?.coerceIn(3, 16) ?: 8
        val chars = CHARSETS[st.charset] ?: CHARSETS.values.first()
        val tag = "vc-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
        val info = batchInfo(tag)
        val needScript = !um && (st.validity.isNotBlank() || st.bind)
        lastPdf = null; lastScript = null
        ensureStorage()
        phase = "جاري توليد الكروت"
        progress = 0 to n
        scope.launch {
            try {
                // التوليد محلياً، ثم فحص الأسماء المولّدة فقط على الراوتر (بدل تحميل كل الكروت الموجودة — بطيء جداً عبر السحابة)
                val seen = HashSet<String>()
                val out = ArrayList<Voucher>(n)
                var attempts = 0
                fun fill() {
                    while (out.size < n && attempts < n * 20) {
                        attempts++
                        val user = st.prefix + randomCode(chars, len)
                        if (!seen.add(user)) continue
                        val pd = st.passDigits.toIntOrNull()
                        out += Voucher(user, when { pd != null -> randomCode(CHARSETS.getValue("أرقام"), pd); um -> ""; else -> user })
                    }
                }
                fill()
                if (connected) for (round in 0 until 6) {
                    val taken = if (um) UserManager.existing(out.map { it.user }) else existingHotspot(out.map { it.user })
                    if (taken.isEmpty()) break
                    out.removeAll { it.user in taken }
                    fill()
                }
                if (addToRouter) {
                    val api = Session.api()
                    // تثبيت نظام الصلاحية وربط الجهاز على الراوتر إن لم يكن مثبتاً
                    if (needScript && !ZariValidity.isEnabled()) ZariValidity.install()
                    progress = 0 to out.size
                    phase = "جاري إضافة البطاقات"
                    // إضافة دفعات (Pipelining): رحلة واحدة لكل 25 كرت بدل رحلة لكل كرت
                    if (um) {
                        UserManager.addUsers(out.map { it.user to it.pass }, info.profile, info.comment, st.bind) { d -> progress = d to out.size }
                    } else {
                        val errs = ArrayList<String>()
                        out.chunked(50).forEachIndexed { ci, part ->
                            val res = api.batch(part.map { v ->
                                val p = linkedMapOf("name" to v.user, "password" to v.pass, "profile" to info.profile, "comment" to info.comment)
                                if (info.limitUptime.isNotBlank()) p["limit-uptime"] = info.limitUptime
                                if (info.limitBytes > 0) p["limit-bytes-total"] = info.limitBytes.toString()
                                if (info.email.isNotBlank()) p["email"] = info.email
                                com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/hotspot/user/add", p)
                            }, 50)
                            res.forEachIndexed { i, r -> r.exceptionOrNull()?.let { errs += "${part[i].user}: ${it.message}" } }
                            progress = minOf((ci + 1) * 50, out.size) to out.size
                        }
                        if (errs.isNotEmpty()) throw com.zarinet.netcontrol.api.RouterOsException("فشل ${errs.size} كرت: " + errs.take(3).joinToString(" | "))
                    }
                } else {
                    val v6 = um && runCatching { UserManager.isV6() }.getOrDefault(false)
                    val finalInfo = info.copy(umV6 = v6, customer = if (v6) UserManager.customerName() else "admin")
                    lastScript = withContext(Dispatchers.IO) { VoucherPdf.buildScript(ctx, out, finalInfo, if (needScript) st.profile else null, tag) }
                }
                // سجل الدفعة لتقارير المبيعات (يحفظ العدد حتى لو حُذفت الكروت من الراوتر لاحقاً)
                val logKey = "sales_" + (Session.device?.id ?: "")
                store.putString(logKey, SaleLog.listTo(SaleLog.listFrom(store.getString(logKey, "[]")) +
                    SaleLog(tag, target.name, st.pos.trim(), out.size, Sales.priceOf(st.price), st.profile, System.currentTimeMillis(), exported = !addToRouter)))
                lastPdf = buildPdf(out, info, tag)
                status = if (addToRouter) "تمت إضافة ${out.size} كرت للراوتر (الدفعة $tag)" else "تم تصدير ${out.size} كرت كسكربت + PDF"
                if (addToRouter && info.comment !in batches) batches = (batches + info.comment).sorted()
            } catch (e: Exception) {
                status = "توقف: ${e.message}"
            }
            progress = null
            phase = ""
        }
    }

    /** تأكيد قبل الإضافة والطباعة (والتصدير). */
    fun askThenGenerate(addToRouter: Boolean) {
        confirmAdd = addToRouter
    }

    fun reprint(batch: String) {
        ensureStorage()
        phase = "جاري تجهيز الملف"
        progress = 0 to 1
        scope.launch {
            try {
                val list = if (um) UserManager.byComment(batch)
                    else Session.api().print(userPath, listOf("name", "password"), listOf("?comment=$batch")).map { Voucher(it["name"] ?: "", it["password"] ?: "") }
                val tag = ZariValidity.body(batch).substringBefore(';').replace(Regex("[^\\w\\-]"), "_")
                lastScript = null
                lastPdf = buildPdf(list, batchInfo(tag), tag)
                status = "جاهز: ${list.size} كرت من الدفعة $tag"
            } catch (e: Exception) { status = "خطأ: ${e.message}" }
            progress = null
            phase = ""
        }
    }

    VouchersContent(
        target = target,
        design = design, st = st, profiles = profiles, connected = connected, progress = progress, status = status,
        lastPdf = lastPdf, lastScript = lastScript,
        onBack = onBack,
        onDesign = ::saveDesign, onSettings = ::onSt,
        onGallery = { picker.launch("image/*") },
        onTemplates = { showTemplates = true },
        onReset = { saveDesign(CardDesign.default().copy(font = design.font, withPassword = design.withPassword,
            showSerial = design.showSerial, showQr = design.showQr)) },
        onReprint = { showReprint = true },
        onPrint = { askThenGenerate(true) },
        onExport = { askThenGenerate(false) },
        onOpen = { VoucherPdf.open(ctx, it) },
        onShare = { VoucherPdf.share(ctx, it) },
        onAddPackage = if (um) ({ addPkg = true }) else null,
        onPrevious = { showPrevious = true }
    )

    progress?.let { (d, total) -> PrintProgressDialog(phase, d, total) }
    done?.let { r ->
        PrintDoneDialog(r,
            onOpen = { done = null; VoucherPdf.open(ctx, r.file) },
            onShare = { done = null; VoucherPdf.share(ctx, r.file) },
            onDismiss = { done = null })
    }
    if (showPrevious) {
        PreviousFilesDialog(onDismiss = { showPrevious = false },
            onOpen = { VoucherPdf.open(ctx, it) }, onShare = { VoucherPdf.share(ctx, it) })
    }
    if (showTemplates) {
        TemplatesDialog(design, onDismiss = { showTemplates = false }) { t ->
            showTemplates = false
            saveDesign(design.withTemplate(t))
        }
    }
    if (showReprint) {
        var sel by remember { mutableStateOf(batches.firstOrNull() ?: "") }
        FormDialog("إعادة طباعة دفعة", confirmText = "إنشاء PDF", enabled = sel.isNotBlank(),
            onConfirm = { showReprint = false; reprint(sel) }, onDismiss = { showReprint = false }) {
            Dropdown("الدفعة", sel, batches) { sel = it }
            Text("سيُستخدم تصميم الكرت الحالي.", style = MaterialTheme.typography.bodySmall, color = C.Dim)
        }
    }
    confirmAdd?.let { toRouter ->
        val n = st.count.toIntOrNull() ?: 0
        val needScript = toRouter && !um && (st.validity.isNotBlank() || st.bind)
        AppDialog(
            title = if (toRouter) "إضافة وطباعة" else "تصدير سكربت",
            onDismiss = { confirmAdd = null },
            buttons = {
                AppButton("إلغاء", { confirmAdd = null }, kind = BtnKind.Ghost)
                AppButton(if (toRouter) "إضافة وطباعة" else "تصدير", { confirmAdd = null; generate(toRouter) },
                    icon = if (toRouter) Icons.Filled.Print else Icons.Filled.Code)
            }
        ) {
            Text(if (toRouter) "هل أنت متأكد أنك تريد طباعة $n من البطاقات؟" else "هل تريد تصدير $n بطاقة كسكربت .rsc مع ملف PDF؟",
                style = MaterialTheme.typography.titleMedium, color = C.Text)
            KvGrid(listOfNotNull(
                (if (um) "باقة يوزر منجر" else "البروفايل") to st.profile,
                "نقطة البيع" to st.pos.ifBlank { "لا يوجد" },
                "السعر" to st.price.ifBlank { "—" },
                "الصفحات" to "${if (design.cols * design.rows > 0) (n + design.cols * design.rows - 1) / (design.cols * design.rows) else 0}"
            ))
            if (needScript) Text("لتعمل الصلاحية أو الربط بأول هاتف سيُضاف سكربت on-login إلى بروفايل \"${st.profile}\" مرة واحدة دون حذف الموجود.",
                style = MaterialTheme.typography.bodySmall, color = C.Gold)
        }
    }
    if (addPkg) AddPackageDialog(onDismiss = { addPkg = false }) { n, d, mb, sp, pr ->
        addPkg = false
        scope.launch {
            try {
                UserManager.addPackage(n, d, mb, sp, pr)
                profiles = UserManager.profileNames()
                umTexts = runCatching { UserManager.cardTexts() }.getOrDefault(umTexts)
                saveSt(withPkg(st.copy(profile = n), true))
                com.zarinet.netcontrol.ui.components.toast(ctx, "تمت إضافة الباقة", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }
}

/** أسماء كروت الهوتسبوت الموجودة فعلاً من بين [names] (استعلامات مجمّعة في رحلة واحدة). */
internal suspend fun existingHotspot(names: List<String>): Set<String> {
    if (names.isEmpty()) return emptySet()
    val chunks = names.chunked(40)
    return Session.api().batch(chunks.map {
        com.zarinet.netcontrol.api.RouterOsClient.Cmd("/ip/hotspot/user/print", proplist = listOf("name"),
            queries = com.zarinet.netcontrol.util.ActiveInfo.orQuery("name", it))
    }, 60).flatMap { r -> r.getOrThrow().rows.mapNotNull { it["name"] } }.toHashSet()
}

/** يضيف سكربت on-login للبروفايل إن لم يكن موجوداً. */
internal suspend fun installScript(profile: String) {
    val api = Session.api()
    val row = api.print("/ip/hotspot/user/profile", listOf(".id", "name", "on-login"), listOf("?name=$profile")).firstOrNull() ?: return
    val cur = row["on-login"].orEmpty()
    if (cur.contains(OnLoginScript.MARKER)) return
    val merged = if (cur.isBlank()) OnLoginScript.CODE else cur + "\r\n" + OnLoginScript.CODE
    api.exec("/ip/hotspot/user/profile/set", mapOf(".id" to row[".id"]!!, "on-login" to merged))
}

@Composable
fun VouchersContent(
    target: CardTarget,
    design: CardDesign,
    st: VSettings,
    profiles: List<String>,
    connected: Boolean,
    progress: Pair<Int, Int>?,
    status: String,
    lastPdf: File?,
    lastScript: File?,
    onBack: (() -> Unit)?,
    onDesign: (CardDesign) -> Unit,
    onSettings: (VSettings) -> Unit,
    onGallery: () -> Unit,
    onTemplates: () -> Unit,
    onReset: () -> Unit,
    onReprint: () -> Unit,
    onPrint: () -> Unit,
    onExport: () -> Unit,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
    onAddPackage: (() -> Unit)? = null,
    initialSelected: El? = El.USER,
    onPrevious: () -> Unit = {}
) {
    val t = MaterialTheme.typography
    val um = target == CardTarget.USERMAN
    var selected by remember { mutableStateOf(initialSelected) }
    val count = st.count.toIntOrNull() ?: 0
    val perPage = design.cols * design.rows
    val pages = if (perPage > 0) ceil(count / perPage.toDouble()).toInt() else 0

    Column(Modifier.fillMaxSize()) {
        if (onBack != null) TopBar("طباعة كروت", "كروت الهوتسبوت", onBack) {
            BarAction(Icons.Filled.History, "إعادة طباعة دفعة", C.Dim, onReprint)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = D.screen).padding(bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.md)
        ) {
            if (onBack == null) Spacer(Modifier.height(D.sm))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Dot(if (connected) C.Online else C.Red)
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "متصل بالمايكروتيك" else "غير متصل — يمكنك التصدير كسكربت فقط", style = t.bodySmall,
                    color = if (connected) C.Online else C.Red)
            }

            // ── أدوات التصميم ──
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm), modifier = Modifier.fillMaxWidth()) {
                ToolChip("قالب من الهاتف", Icons.Filled.Image, Modifier.weight(1f), onGallery)
                ToolChip("نماذج جاهزة", Icons.Filled.Dashboard, Modifier.weight(1f), onTemplates)
                ToolChip("إعادة الضبط", Icons.Filled.RestartAlt, Modifier.weight(1f), onReset)
            }

            // ── المعاينة التفاعلية ──
            CardEditor(design, st, um, selected, onSelect = { selected = it }, onDesign = onDesign)
            selected?.let { el ->
                val p = design.posOf(el)
                // تعديل نص العنصر المحدد مباشرة (إن كان له نص يدوي)
                st.textOf(el)?.let { txt ->
                    val hint = when (el) {
                        El.TIME -> st.allowedOn(um)
                        El.VALID -> st.validOn(um)
                        El.DATA -> st.balanceOn(um)
                        else -> ""
                    }
                    Field(txt, { onSettings(st.withText(el, it)) }, el.label, Modifier.fillMaxWidth(), placeholder = hint.ifBlank { null })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الحجم", style = t.bodySmall, color = C.Dim)
                    Spacer(Modifier.width(D.sm))
                    Slider(p.size, { onDesign(design.copy(pos = design.pos + (el to p.copy(size = it)))) },
                        valueRange = 0.08f..0.6f, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = C.Teal, activeTrackColor = C.Teal, inactiveTrackColor = C.CardHi))
                }
            }
            Text("اسحب أي نص لتحريكه • اضغط عليه لتعديل نصه وحجمه", style = t.bodySmall, color = C.Faint,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)

            // ── إعدادات الكرت ──
            SectionTitle("إعدادات الكرت")
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
                Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
                    FormRow("الباقة") {
                        if (um && onAddPackage != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalAlignment = Alignment.CenterVertically) {
                                Dropdown("", st.profile, profiles, Modifier.weight(1f)) { onSettings(st.copy(profile = it)) }
                                AppButton("", onAddPackage, Modifier.height(48.dp), BtnKind.Secondary, Icons.Filled.Add)
                            }
                        } else Dropdown("", st.profile, profiles) { onSettings(st.copy(profile = it)) }
                    }
                    if (!um) {
                        FormRow("الصلاحية") {
                            NumUnit(st.validNum, VALID_UNITS.keys.toList(), st.validUnit,
                                { onSettings(st.copy(validNum = it)) }, { onSettings(st.copy(validUnit = it)) })
                        }
                        FormRow("الوقت المسموح") {
                            NumUnit(st.timeNum, TIME_UNITS.keys.toList(), st.timeUnit,
                                { onSettings(st.copy(timeNum = it)) }, { onSettings(st.copy(timeUnit = it)) })
                        }
                        FormRow("حد البيانات") {
                            NumUnit(st.dataNum, DATA_UNITS.keys.toList(), st.dataUnit,
                                { onSettings(st.copy(dataNum = it)) }, { onSettings(st.copy(dataUnit = it)) }, placeholder = "بدون حد")
                        }
                    }
                    FormRow("أرقام الاسم") {
                        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                            Field(st.digits, { onSettings(st.copy(digits = it.filter(Char::isDigit))) }, "", Modifier.weight(1f), number = true)
                            Dropdown("", st.charset, CHARSETS.keys.toList(), Modifier.weight(1.3f)) { onSettings(st.copy(charset = it)) }
                        }
                    }
                    FormRow("كلمة السر") {
                        Dropdown("", st.passDigits, PASS_DIGITS) { onSettings(st.copy(passDigits = it)) }
                    }
                    FormRow("عدد الكروت") { Field(st.count, { onSettings(st.copy(count = it.filter(Char::isDigit))) }, "", number = true) }
                    FormRow("نقطة البيع") { Field(st.pos, { onSettings(st.copy(pos = it)) }, "", placeholder = "لا يوجد") }
                    FormRow("بادئة الكرت") { Field(st.prefix, { onSettings(st.copy(prefix = it.trim())) }, "", placeholder = "لا يوجد", ltr = true) }
                    FormRow("اسم الشبكة") { Field(st.network, { onSettings(st.copy(network = it)) }, "") }
                    // نصوص الكرت: في يوزر منجر تُملأ من الباقة، وفي الهوتسبوت تُحسب من الحدود إن تُركت فارغة
                    FormRow(if (um) "الصلاحية" else "نص الصلاحية") {
                        Field(st.validLabel, { onSettings(st.copy(validLabel = it)) }, "", placeholder = st.validOn(um).ifBlank { "مثال: 30 يوم" })
                    }
                    FormRow(if (um) "الوقت المسموح" else "نص الوقت") {
                        Field(st.timeText, { onSettings(st.copy(timeText = it)) }, "", placeholder = st.allowedOn(um).ifBlank { "مثال: 7 ساعات" })
                    }
                    FormRow(if (um) "حجم الرصيد" else "نص الرصيد") {
                        Field(st.dataLabel, { onSettings(st.copy(dataLabel = it)) }, "", placeholder = st.balanceOn(um).ifBlank { "مثال: 5 جيجا" })
                    }
                    FormRow("السعر") { Field(st.price, { onSettings(st.copy(price = it)) }, "", placeholder = "مثال: 200 ريال") }
                    FormRow("ملاحظات 1") { Field(st.note1, { onSettings(st.copy(note1 = it)) }, "") }
                    FormRow("ملاحظات 2") { Field(st.note2, { onSettings(st.copy(note2 = it)) }, "") }
                    FormRow("بداية التسلسل") { Field(st.serialStart, { onSettings(st.copy(serialStart = it.filter(Char::isDigit))) }, "", number = true) }
                    FormRow("الخط") {
                        Dropdown("", CARD_FONTS[design.font] ?: "Cairo", CARD_FONTS.values.toList()) { v ->
                            onDesign(design.copy(font = CARD_FONTS.entries.first { it.value == v }.key))
                        }
                    }
                    // ── لون وخط كل نص على حدة ──
                    val styleEl = selected?.takeIf { it != El.QR } ?: El.USER
                    val textEls = El.entries.filter { it != El.QR }
                    Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
                        Text("لون وخط النصوص", style = t.titleSmall, color = C.Dim)
                        Dropdown("النص", styleEl.label, textEls.map { it.label }) { v -> selected = textEls.first { it.label == v } }
                        ColorRow(Color(design.colorOf(styleEl))) { onDesign(design.copy(colors = design.colors + (styleEl to it.toArgb()))) }
                        Dropdown("خط ${styleEl.label}", CARD_FONTS[design.fontOf(styleEl)] ?: "Cairo عريض", CARD_FONTS.values.toList()) { v ->
                            onDesign(design.copy(fonts = design.fonts + (styleEl to CARD_FONTS.entries.first { it.value == v }.key)))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                        Field(design.rows.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { onDesign(design.copy(rows = it.coerceIn(1, 30))) } },
                            "عدد الصفوف", Modifier.weight(1f), number = true)
                        Field(design.cols.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { onDesign(design.copy(cols = it.coerceIn(1, 10))) } },
                            "عدد الأعمدة", Modifier.weight(1f), number = true)
                    }
                }
            }

            // ── ما يظهر على الكرت ──
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = D.lg, vertical = D.sm)) {
                SwitchRow("كلمة السر", design.withPassword) { onDesign(design.copy(withPassword = it)) }
                SwitchRow("استخدام الباركود (QR)", design.showQr) { onDesign(design.copy(showQr = it)) }
                SwitchRow("اسم الشبكة", design.showNetwork) { onDesign(design.copy(showNetwork = it)) }
                SwitchRow("الوقت المسموح", design.showTime) { onDesign(design.copy(showTime = it)) }
                SwitchRow("الصلاحية", design.showValid) { onDesign(design.copy(showValid = it)) }
                SwitchRow("حجم الرصيد", design.showData) { onDesign(design.copy(showData = it)) }
                SwitchRow("السعر", design.showPrice) { onDesign(design.copy(showPrice = it)) }
                SwitchRow("ملاحظات 1", design.showNote1) { onDesign(design.copy(showNote1 = it)) }
                SwitchRow("ملاحظات 2", design.showNote2) { onDesign(design.copy(showNote2 = it)) }
                SwitchRow("رقم تسلسلي", design.showSerial) { onDesign(design.copy(showSerial = it)) }
                HorizontalDivider(color = C.Divider, modifier = Modifier.padding(vertical = 4.dp))
                SwitchRow("ربط عند أول استخدام", st.bind) { onSettings(st.copy(bind = it)) }
            }

            // ── ملخص ──
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusControl)).background(C.Teal.copy(alpha = 0.12f))
                .border(1.dp, C.Teal.copy(alpha = 0.4f), RoundedCornerShape(D.radiusControl)).padding(D.md), contentAlignment = Alignment.Center) {
                Text("يمكن للصفحة أن تتسع لـ $perPage بطاقة (${design.cols} أعمدة × ${design.rows} صف) • $pages صفحة", style = t.bodyMedium, color = C.Teal,
                    textAlign = TextAlign.Center)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                AppButton("حفظ وطباعة", onPrint, Modifier.weight(1.3f).height(52.dp), icon = Icons.Filled.Print,
                    enabled = connected && progress == null && count > 0, loading = progress != null)
                AppButton("الملفات السابقة", onPrevious, Modifier.weight(1f).height(52.dp), BtnKind.Secondary, Icons.Filled.FolderOpen,
                    enabled = progress == null)
            }
            AppButton("تصدير سكربت (.rsc)", onExport, Modifier.fillMaxWidth(), BtnKind.Ghost, Icons.Filled.Code,
                enabled = progress == null && count > 0)

            if (status.isNotBlank() && progress == null) {
                AppCard(Modifier.fillMaxWidth(), border = if (lastPdf != null) C.Teal else C.Border) {
                    Text(status, style = t.bodyLarge, color = C.Text)
                    if (lastPdf != null || lastScript != null) {
                        Spacer(Modifier.height(D.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                            lastPdf?.let { f ->
                                AppButton("فتح PDF", { onOpen(f) }, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.PictureAsPdf)
                                AppButton("مشاركة", { onShare(f) }, Modifier.weight(1f), icon = Icons.Filled.Share)
                            }
                        }
                        lastScript?.let { f ->
                            Spacer(Modifier.height(D.sm))
                            AppButton("مشاركة السكربت (.rsc)", { onShare(f) }, Modifier.fillMaxWidth(), BtnKind.Secondary, Icons.Filled.Code)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────── محرر الكرت ───────────────────

@Composable
private fun CardEditor(design: CardDesign, st: VSettings, um: Boolean, selected: El?, onSelect: (El?) -> Unit, onDesign: (CardDesign) -> Unit) {
    val ctx = LocalContext.current
    val tf = remember(design.font) { VoucherPdf.font(ctx, design.font) }
    val bg = remember(design.template, design.imagePath) { VoucherPdf.loadBg(ctx, design, 1400) }
    val fonts = remember { VoucherPdf.fonts(ctx) }
    val boxes = remember { HashMap<El, android.graphics.RectF>() }
    val aspect = VoucherPdf.cellAspect(design.cols, design.rows).coerceIn(0.6f, 4f)
    val cur by rememberUpdatedState(design)
    val sel by rememberUpdatedState(selected)
    val texts = CardTexts(
        user = st.prefix + "12345678".take(st.digits.toIntOrNull()?.coerceIn(3, 16) ?: 8).padEnd(st.digits.toIntOrNull()?.coerceIn(3, 16) ?: 8, '9'),
        pass = "5821", network = st.network.ifBlank { "اسم الشبكة" }, time = st.allowedOn(um).ifBlank { "الوقت المسموح" },
        price = st.price.ifBlank { "السعر" }, note1 = st.note1.ifBlank { "ملاحظات 1" }, note2 = st.note2.ifBlank { "ملاحظات 2" },
        serial = st.serialStart.ifBlank { "1" },
        valid = st.validOn(um).ifBlank { "الصلاحية" }, uptime = "", data = st.balanceOn(um).ifBlank { "حجم الرصيد" }
    )

    fun hit(o: Offset): El? = boxes.entries.filter { cur.visible(it.key) }
        .firstOrNull { val r = android.graphics.RectF(it.value); r.inset(-24f, -24f); r.contains(o.x, o.y) }?.key

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusControl)).background(Color.White).padding(D.lg)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(aspect)
                .pointerInput(Unit) { detectTapGestures { onSelect(hit(it)) } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> hit(o)?.let(onSelect) },
                        onDrag = { change, amount ->
                            val e = sel ?: return@detectDragGestures
                            change.consume()
                            val p = cur.posOf(e)
                            val nx = (p.x + amount.x / size.width).coerceIn(0.02f, 0.98f)
                            val ny = (p.y + amount.y / size.height).coerceIn(0.02f, 0.98f)
                            onDesign(cur.copy(pos = cur.pos + (e to p.copy(x = nx, y = ny))))
                        }
                    )
                }
        ) {
            boxes.clear()
            drawIntoCanvas { c ->
                CardRenderer.draw(c.nativeCanvas, android.graphics.RectF(0f, 0f, size.width, size.height), design, texts, bg, tf, boxes, fonts)
            }
            val b = selected?.let { boxes[it] }
            if (b != null) {
                val pad = 6.dp.toPx()
                val l = b.left - pad; val tp = b.top - pad; val r = b.right + pad; val bt = b.bottom + pad
                drawRect(C.Teal, Offset(l, tp), Size(r - l, bt - tp), style = Stroke(1.5.dp.toPx()))
                val hr = 5.dp.toPx()
                listOf(l to tp, (l + r) / 2 to tp, r to tp, l to (tp + bt) / 2, r to (tp + bt) / 2, l to bt, (l + r) / 2 to bt, r to bt)
                    .forEach { (x, y) -> drawCircle(C.Teal, hr, Offset(x, y)) }
                drawCircle(Color.Gray.copy(alpha = 0.8f), hr * 1.6f, Offset((l + r) / 2, (tp + bt) / 2))
                drawLine(Color.White, Offset((l + r) / 2 - hr, (tp + bt) / 2), Offset((l + r) / 2 + hr, (tp + bt) / 2), 1.5.dp.toPx())
                drawLine(Color.White, Offset((l + r) / 2, (tp + bt) / 2 - hr), Offset((l + r) / 2, (tp + bt) / 2 + hr), 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun TemplatesDialog(design: CardDesign, onDismiss: () -> Unit, onPick: (CardTemplate) -> Unit) {
    val ctx = LocalContext.current
    val tf = remember { VoucherPdf.font(ctx) }
    FormDialog("نماذج جاهزة", confirmText = "إغلاق", onConfirm = onDismiss, onDismiss = onDismiss) {
        val rows = Templates.all.filter { it.res != null }.map { listOf(it) } + Templates.all.filter { it.res == null }.chunked(2)
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                row.forEach { tpl ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(D.radiusSmall))
                        .border(2.dp, if (tpl.id == design.template) C.Teal else C.Border, RoundedCornerShape(D.radiusSmall))
                        .clickable { onPick(tpl) }.padding(D.sm), horizontalAlignment = Alignment.CenterHorizontally) {
                        val d = design.withTemplate(tpl)
                        val bmp = remember(tpl.id) { VoucherPdf.loadBg(ctx, d, 700) }
                        Canvas(Modifier.fillMaxWidth().aspectRatio(tpl.aspect)) {
                            drawIntoCanvas {
                                CardRenderer.draw(it.nativeCanvas, android.graphics.RectF(0f, 0f, size.width, size.height), d,
                                    CardTexts("12345678", "5821", "الزري نت", "3 ساعات", "200", note1 = "777000000",
                                        valid = "1 يوم", uptime = "3 ساعة", data = "1 جيجا"), bmp, tf)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(tpl.name, style = MaterialTheme.typography.labelMedium, color = C.Text)
                    }
                }
                if (row.size == 1 && row[0].res == null) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ─────────────────── عناصر النموذج ───────────────────

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = C.Dim, modifier = Modifier.width(88.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun NumUnit(num: String, units: List<String>, unit: String, onNum: (String) -> Unit, onUnit: (String) -> Unit, placeholder: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
        Field(num, { onNum(it.filter(Char::isDigit)) }, "", Modifier.weight(1f), number = true, placeholder = placeholder)
        Dropdown("", unit, units, Modifier.weight(1.3f), onUnit)
    }
}

@Composable
private fun ToolChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(D.radiusControl)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(D.radiusControl))
            .clickable(onClick = onClick).padding(vertical = D.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = C.Teal, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = C.Text)
    }
}

@Composable
private fun ColorRow(current: Color, onPick: (Color) -> Unit) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        COLORS.forEach { (_, c) ->
            val sel = c.toArgb() == current.toArgb()
            Box(Modifier.size(if (sel) 32.dp else 28.dp).clip(CircleShape).background(c)
                .border(if (sel) 3.dp else 1.dp, if (sel) C.Teal else C.Border, CircleShape).clickable { onPick(c) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = C.Text, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Teal, checkedThumbColor = C.OnTeal))
    }
}

// ─────────────────── نوافذ الطباعة ───────────────────

/** نافذة تقدم غير قابلة للإغلاق أثناء التوليد/الإضافة/إنشاء الملف. */
@Composable
fun PrintProgressDialog(phase: String, done: Int, total: Int) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusCard)).background(C.Card)
                .border(1.dp, C.Border, RoundedCornerShape(D.radiusCard)).padding(top = D.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(Modifier.size(52.dp), color = C.Teal, strokeWidth = 5.dp)
            Spacer(Modifier.height(D.lg))
            Text(phase.ifBlank { "جاري العمل" }, modifier = Modifier.padding(horizontal = D.lg), color = C.Text,
                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            if (total > 1) Text("‎$done / $total", color = C.Dim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(D.lg))
            LinearProgressIndicator(progress = { done / total.toFloat().coerceAtLeast(1f) }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = C.Blue, trackColor = C.CardHi, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

@Composable
fun PrintDoneDialog(r: PrintDone, onOpen: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    AppDialog("تم إنشاء ملف الطباعة", onDismiss, buttons = { PrintDoneButtons(onOpen, onShare, onDismiss) }) {
        PrintDoneBody(r)
    }
}

@Composable
fun PrintDoneBody(r: PrintDone) {
    Text(if (r.downloadsPath != null) "تم إنشاء ملف الطباعة في مجلد التنزيلات." else "تم إنشاء ملف الطباعة.",
        style = MaterialTheme.typography.titleMedium, color = C.Text)
    Text("${r.count} كرت" + (if (r.downloadsPath != null) "  •  التنزيلات ‹ ميكروتك الشامل" else ""),
        style = MaterialTheme.typography.bodySmall, color = C.Dim)
}

@Composable
fun RowScope.PrintDoneButtons(onOpen: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    AppButton("فتح", onOpen, Modifier.weight(1f), icon = Icons.Filled.PictureAsPdf)
    AppButton("مشاركة", onShare, Modifier.weight(1f), BtnKind.Secondary, Icons.Filled.Share)
    AppButton("تجاهل", onDismiss, Modifier.weight(1f), BtnKind.Ghost)
}

/** قائمة ملفات PDF السابقة مع فتح/مشاركة/حذف. */
@Composable
fun PreviousFilesDialog(onDismiss: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit, files: List<File>? = null) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(files ?: VoucherPdf.previousFiles(ctx)) }
    var deleting by remember { mutableStateOf<File?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    FormDialog("الملفات السابقة", confirmText = "إغلاق", onConfirm = onDismiss, onDismiss = onDismiss) {
        if (list.isEmpty()) Text("لا توجد ملفات طباعة سابقة.", style = MaterialTheme.typography.bodyMedium, color = C.Dim)
        list.forEach { f ->
            AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.PictureAsPdf, C.Red, 36.dp, 20.dp)
                    Spacer(Modifier.width(D.sm))
                    Column(Modifier.weight(1f).clickable { onOpen(f) }) {
                        Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleSmall, color = C.Text, maxLines = 1)
                        Text("‎" + fmt.format(Date(f.lastModified())) + "  •  " + Fmt.bytes(f.length()),
                            style = MaterialTheme.typography.labelSmall, color = C.Dim)
                    }
                    IconButton({ onShare(f) }) { Icon(Icons.Filled.Share, "مشاركة", tint = C.Teal) }
                    IconButton({ deleting = f }) { Icon(Icons.Filled.DeleteOutline, "حذف", tint = C.Red) }
                }
            }
        }
    }
    deleting?.let { f ->
        ConfirmDialog("حذف الملف", "حذف ${f.name} من التطبيق؟\n(النسخة في مجلد التنزيلات لا تُحذف)", "حذف",
            onConfirm = { f.delete(); list = list - f; deleting = null }, onDismiss = { deleting = null })
    }
}
