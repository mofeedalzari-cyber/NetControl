package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.ActiveInfo
import com.zarinet.netcontrol.util.AppForeground
import com.zarinet.netcontrol.util.CardInfo
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.RosTime
import com.zarinet.netcontrol.util.UserManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** مصدر الجلسة: هوتسبوت محلي أو يوزر منجر (RADIUS). لكل مصدر لونه. */
enum class ActiveSrc(val label: String, val color: Color, val c2: Color) {
    HOTSPOT("هوتسبوت", Color(0xFF14A3C7), Color(0xFF2FD3E8)),
    USERMAN("يوزر منجر", Color(0xFF8A4FD8), Color(0xFFC07BF5))
}

data class ActiveRow(
    val id: String,
    val user: String,
    val address: String,
    val mac: String,
    val uptime: String,
    val left: String,
    val server: String,
    val profile: String,
    val bytesTotal: Long,
    val rateBps: Long,
    val source: ActiveSrc = ActiveSrc.HOTSPOT,
    val download: Long = 0,
    val upload: Long = 0,
    val loginBy: String = "",
    val idle: String = "",
    val remainingMs: Long? = null,     // الوقت المتبقي للكرت (من بيانات الكرت أو session-time-left)
    val leftBytes: Long? = null,       // الرصيد المتبقي (null = مفتوح)
    val totalUsed: Long? = null,       // المستهلك الكلي للكرت (السابق + الجلسة الحالية)
    val limit: String = "",            // السرعة المحددة
    val limitKnown: Boolean = false,
    val infoLoaded: Boolean = false
)

/**
 * ذاكرة مؤقتة على مستوى التطبيق للمتصلين: تُعرض القائمة فوراً عند العودة للشاشة،
 * وتُحفظ بيانات الكروت (الباقة/المتبقي/المحددة) حتى لا تُطلب من الراوتر كل مرة.
 */
object ActiveCache {
    val PROPS = listOf(".id", "user", "address", "mac-address", "uptime", "session-time-left", "server",
        "bytes-in", "bytes-out", "login-by", "radius", "idle-time")

    @Volatile var deviceId: String = ""
    @Volatile var raw: List<Map<String, String>> = emptyList()
    val prevBytes = ConcurrentHashMap<String, Pair<Long, Long>>()
    val lastRate = ConcurrentHashMap<String, Long>()
    val infos = ConcurrentHashMap<String, CardInfo>()
    val tried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile var hsAt = 0L
    @Volatile var umAt = 0L
    @Volatile var limAt = 0L
    @Volatile var emptyAt = 0L

    /** يحفظ آخر قراءة؛ تغيير الجهاز يمسح كل ما سبق. */
    fun put(device: String, rows: List<Map<String, String>>) {
        if (device != deviceId) {
            prevBytes.clear(); lastRate.clear(); infos.clear(); tried.clear()
            hsAt = 0; umAt = 0; limAt = 0
        }
        deviceId = device
        raw = rows
    }

    /** يحوّل آخر قراءة إلى صفوف مع حساب السرعة. [track]=false لا يغيّر مرجع حساب السرعة. */
    fun rows(now: Long = System.currentTimeMillis(), track: Boolean = true): List<ActiveRow> = raw.map { a ->
        val id = a[".id"] ?: ""
        val up = a["bytes-in"]?.toLongOrNull() ?: 0      // من العميل = رفع
        val down = a["bytes-out"]?.toLongOrNull() ?: 0   // إلى العميل = تنزيل
        val total = up + down
        val prev = prevBytes[id]
        val computed = if (prev != null && now > prev.second) (total - prev.first).coerceAtLeast(0) * 8000 / (now - prev.second)
        else lastRate[id] ?: 0
        // لا يتغير عداد الراوتر إلا كل بضع ثوانٍ: نُبقي آخر سرعة حتى يتغير العداد أو تمر 8 ثوانٍ
        val rate = if (prev == null || total != prev.first || now - prev.second >= 8000) {
            if (track) { prevBytes[id] = total to now; lastRate[id] = computed }
            computed
        } else lastRate[id] ?: 0
        if (prev == null && track) prevBytes[id] = total to now
        val src = if (a["radius"] == "true") ActiveSrc.USERMAN else ActiveSrc.HOTSPOT
        ActiveRow(id, a["user"] ?: "", a["address"] ?: "", a["mac-address"] ?: "", a["uptime"] ?: "", a["session-time-left"] ?: "",
            a["server"] ?: "", "", total, rate, src, down, up, a["login-by"] ?: "", a["idle-time"] ?: "")
    }
}

@Composable
fun ActiveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devId = Session.device?.id ?: ""
    // عرض فوري لآخر قراءة من الذاكرة المؤقتة (لنفس الجهاز)
    val cached = remember {
        if (ActiveCache.deviceId == devId && ActiveCache.raw.isNotEmpty()) ActiveCache.rows(track = false) else emptyList()
    }
    var list by remember { mutableStateOf(cached) }
    var loading by remember { mutableStateOf(cached.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    // التحديث التلقائي مغلق دائماً عند فتح الشاشة (لا يُحفظ بين الزيارات)
    var auto by remember { mutableStateOf(false) }
    var kicking by remember { mutableStateOf<ActiveRow?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val infos = remember {
        mutableStateMapOf<String, CardInfo>().apply { if (ActiveCache.deviceId == devId) putAll(ActiveCache.infos) }
    }
    var infoBusy by remember { mutableStateOf(false) }

    /** يجلب بيانات الكروت الناقصة (الباقة/المتبقي/الاستهلاك/المحددة) في الخلفية. */
    fun loadInfos(rows: List<ActiveRow>, full: Boolean = true) {
        if (infoBusy || rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val c = ActiveCache
        // أثناء التحديث التلقائي لا نعيد جلب البيانات الثابتة (الباقة/الرصيد/المحددة) — فقط للكروت الجديدة
        val fullHs = full && now - c.hsAt > 120_000
        val fullUm = full && now - c.umAt > 600_000
        val fullLim = full && now - c.limAt > 120_000
        // الكروت التي لم تُعرف بياناتها تُعاد محاولتها كل 20 ثانية
        if (now - c.emptyAt > 20_000) {
            c.tried.removeAll(c.infos.filterValues { it.profile.isEmpty() && it.remainingMs == null && it.totalUsed == null }.keys)
        }
        fun missing(r: ActiveRow): Boolean {
            val i = infos[r.user]
            return (i == null || (i.profile.isEmpty() && i.remainingMs == null)) && r.user !in c.tried
        }
        val hs = rows.filter { it.source == ActiveSrc.HOTSPOT && (fullHs || missing(it)) }
        val um = rows.filter { it.source == ActiveSrc.USERMAN && (fullUm || missing(it)) }
        val lim = rows.filter { fullLim || infos[it.user]?.limitKnown != true }
        if (hs.isEmpty() && um.isEmpty() && lim.isEmpty()) return
        infoBusy = true
        scope.launch {
            runCatching {
                val api = Session.api()
                val sess = rows.associate { it.user to it.bytesTotal }
                // بيانات الكرت تُخزَّن بدون استهلاك الجلسة الحالية (يُضاف عند العرض)
                fun merge(got: Map<String, CardInfo>) {
                    got.forEach { (u, v) ->
                        val s = sess[u] ?: 0L
                        val old = infos[u]
                        infos[u] = v.copy(
                            leftBytes = v.leftBytes?.let { it + s },
                            totalUsed = v.totalUsed?.let { (it - s).coerceAtLeast(0) },
                            limit = old?.limit.orEmpty(),
                            limitKnown = old != null && old.limitKnown
                        )
                    }
                }
                if (hs.isNotEmpty()) runCatching {
                    merge(ActiveInfo.hotspot(api, hs.map { it.user }, hs.associate { it.user to it.bytesTotal }))
                    if (fullHs) c.hsAt = now
                }
                if (um.isNotEmpty()) runCatching {
                    merge(UserManager.activeInfo(um.map { it.user }))
                    if (fullUm) c.umAt = now
                }
                (hs + um).forEach { r ->
                    c.tried.add(r.user)
                    if (!infos.containsKey(r.user)) { infos[r.user] = CardInfo("", null, null); c.emptyAt = now }
                }
                if (lim.isNotEmpty()) {
                    val (limits, known) = ActiveInfo.limits(api, lim.map { it.user })
                    for (u in known) {
                        val old = infos[u] ?: CardInfo("", null, null)
                        infos[u] = old.copy(limit = limits[u].orEmpty(), limitKnown = true)
                    }
                    if (fullLim && known.isNotEmpty()) c.limAt = now
                }
                c.infos.putAll(infos)
            }
            infoBusy = false
        }
    }

    suspend fun fetch(withInfo: Boolean = true, full: Boolean = true) {
        try {
            val raw = Session.api().print("/ip/hotspot/active", ActiveCache.PROPS)
            ActiveCache.put(devId, raw)
            list = ActiveCache.rows()
            error = null
            if (withInfo) loadInfos(list, full)
        } catch (e: Exception) { error = e.message ?: "خطأ" }
        loading = false
    }

    fun leftMs(r: ActiveRow): Long? = r.left.ifBlank { null }?.let { Fmt.seconds(it) * 1000 }?.takeIf { it > 0 }

    LaunchedEffect(auto, refreshKey) {
        fetch()
        while (auto) {
            val started = System.currentTimeMillis()
            AppForeground.await()   // لا تحديث والتطبيق في الخلفية
            // المتغير فقط: الاتصال والتنزيل والرفع والسرعة والمدة — والفاصل 3 ثوانٍ من بداية الدورة
            fetch(full = false)
            delay((3000L - (System.currentTimeMillis() - started)).coerceAtLeast(500L))
        }
    }

    // دمج بيانات الكروت مع الجلسات الحالية (يُعاد الحساب فقط عند تغيّر القائمة أو بيانات الكروت)
    val shown by remember {
        derivedStateOf {
            list.map { r ->
                val inf = infos[r.user]
                if (inf != null) r.copy(
                    profile = inf.profile,
                    remainingMs = inf.remainingMs ?: leftMs(r),
                    leftBytes = inf.leftBytes?.let { (it - r.bytesTotal).coerceAtLeast(0) },
                    totalUsed = inf.totalUsed?.let { it + r.bytesTotal },
                    limit = inf.limit,
                    limitKnown = inf.limitKnown,
                    infoLoaded = true
                ) else r.copy(remainingMs = leftMs(r))
            }
        }
    }

    ActiveContent(shown, loading, error, auto, onAuto = { auto = it }, onBack = onBack, onRefresh = { refreshKey++ }, onKick = { kicking = it })

    kicking?.let { r ->
        ConfirmDialog("قطع الاتصال", "قطع اتصال الكرت ${r.user}؟ سيبقى الكرت صالحاً ويمكنه الدخول مجدداً.", "قطع", danger = false,
            onConfirm = {
                scope.launch {
                    try {
                        Session.api().exec("/ip/hotspot/active/remove", mapOf(".id" to r.id))
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم القطع", Toast.LENGTH_SHORT).show()
                        refreshKey++
                    } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message, Toast.LENGTH_LONG).show() }
                }
            },
            onDismiss = { kicking = null })
    }
}

@Composable
fun ActiveContent(
    list: List<ActiveRow>,
    loading: Boolean,
    error: String?,
    auto: Boolean,
    onAuto: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onKick: (ActiveRow) -> Unit,
    onExpand: (ActiveRow) -> Unit = {},
    initiallyOpen: Set<String> = emptySet()
) {
    val t = MaterialTheme.typography
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(initiallyOpen) }   // البطاقات المفتوحة (تفاصيل)
    var src by remember { mutableStateOf<ActiveSrc?>(null) }
    val filtered = remember(list, query, src) {
        list.filter { r ->
            (src == null || r.source == src) &&
                (query.isBlank() || r.user.contains(query, true) || r.mac.contains(query, true) || r.address.contains(query) ||
                    r.server.contains(query, true) || r.profile.contains(query, true))
        }
    }
    val totalRate = remember(list) { list.sumOf { it.rateBps } }
    val hs = list.count { it.source == ActiveSrc.HOTSPOT }
    val um = list.size - hs

    Column(Modifier.fillMaxSize()) {
        TopBar("المتصلون الآن", "${list.size} متصل • ${Fmt.bps(totalRate)}", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث", onClick = onRefresh)
        }
        Column(Modifier.padding(horizontal = D.screen), verticalArrangement = Arrangement.spacedBy(D.sm)) {
            // شرائح المصدر بألوانها
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                SrcChip("الكل", list.size, C.Steel, C.Cyan, src == null) { src = null }
                SrcChip(ActiveSrc.HOTSPOT.label, hs, ActiveSrc.HOTSPOT.color, ActiveSrc.HOTSPOT.c2, src == ActiveSrc.HOTSPOT) { src = ActiveSrc.HOTSPOT }
                SrcChip(ActiveSrc.USERMAN.label, um, ActiveSrc.USERMAN.color, ActiveSrc.USERMAN.c2, src == ActiveSrc.USERMAN) { src = ActiveSrc.USERMAN }
            }
            SearchField(query, { query = it }, "بحث: الكرت، MAC، IP...")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("المعروض: ${filtered.size}", style = t.labelMedium, color = C.Dim, modifier = Modifier.weight(1f))
                Text("تحديث تلقائي", style = t.bodySmall, color = C.Dim)
                Spacer(Modifier.width(D.sm))
                Switch(auto, onAuto, colors = SwitchDefaults.colors(
                    checkedTrackColor = C.Teal, checkedThumbColor = C.OnTeal,
                    uncheckedTrackColor = C.CardHi, uncheckedThumbColor = C.Dim, uncheckedBorderColor = C.Dim.copy(alpha = 0.6f)))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, top = D.xs, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item { StateView(loading, error, !loading && error == null && filtered.isEmpty(), "لا يوجد متصلون حالياً", onRefresh) }
            itemsIndexed(filtered, key = { _, r -> r.id }) { i, r ->
                Box(Modifier.animateItem()) {
                    ActiveCard(i + 1, r, r.id in open,
                        onToggle = {
                            if (r.id in open) open = open - r.id
                            else { open = open + r.id; onExpand(r) }
                        },
                        onKick = { onKick(r) })
                }
            }
        }
    }
}

@Composable
private fun SrcChip(label: String, n: Int, c1: Color, c2: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(D.radiusSmall))
            .background(if (selected) Brush.horizontalGradient(listOf(c1, c2)) else Brush.horizontalGradient(listOf(C.Card, C.Card)))
            .border(1.dp, if (selected) Color.Transparent else c2.copy(alpha = 0.6f), RoundedCornerShape(D.radiusSmall))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) Color.White else C.Text)
        Spacer(Modifier.width(8.dp))
        Text("$n", style = MaterialTheme.typography.titleMedium, color = if (selected) Color.White else c2)
    }
}

/** بطاقة متصل مختصرة؛ الضغط يفتح التفاصيل (IP/MAC/الاستهلاك...) وزر القطع. */
@Composable
private fun ActiveCard(index: Int, r: ActiveRow, expanded: Boolean, onToggle: () -> Unit, onKick: () -> Unit) {
    val t = MaterialTheme.typography
    val s = r.source
    val shape = RoundedCornerShape(D.radiusCard)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(C.Card)
            .border(1.dp, s.color.copy(alpha = 0.55f), shape).clickable(onClick = onToggle)
    ) {
        // شريط لون المصدر
        Box(Modifier.width(5.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(s.c2, s.color))))
        Column(Modifier.weight(1f).padding(horizontal = D.md, vertical = if (expanded) D.md else D.sm).animateContentSize()) {
            // السطر الأول: الرقم، الكرت، سهم التفاصيل
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(s.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text("$index", style = t.labelLarge, color = s.c2)
                }
                Spacer(Modifier.width(D.sm))
                Text(Fmt.ltr(r.user), style = t.titleLarge, color = Color(0xFF2FA8DE), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) "إخفاء" else "عرض البيانات", tint = C.Dim)
            }
            // الباقة، الوقت المتبقي، الرصيد، السيرفر
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!r.infoLoaded) Text("—", style = t.labelMedium, color = C.Faint)
                else {
                    Chip(r.profile.ifBlank { "بدون باقة" }, s.c2)
                    Chip(r.remainingMs?.let { RosTime.left(it) } ?: "بدون مدة",
                        if ((r.remainingMs ?: Long.MAX_VALUE) < 86_400_000L) C.Red else C.Teal)
                    Chip(r.leftBytes?.let { "باقي " + Fmt.bytes(it) } ?: "رصيد مفتوح",
                        if (r.leftBytes != null && r.leftBytes < 500L * 1024 * 1024) C.Red else C.Blue)
                }
                if (r.server.isNotBlank()) Chip(r.server, C.Steel)
            }
            // التنزيل/الرفع/الإجمالي/السرعة/المحددة
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Info(Icons.Filled.ArrowDownward, "تنزيل", Fmt.bytesRaw(r.download), C.Teal, Modifier.weight(1.45f))
                Info(Icons.Filled.ArrowUpward, "رفع", Fmt.bytesRaw(r.upload), C.Blue, Modifier.weight(1f))
                Info(Icons.Filled.DataUsage, "الإجمالي", Fmt.bytesRaw(r.bytesTotal), C.Purple, Modifier.weight(1.45f))
                Info(Icons.Filled.Speed, "السرعة", bpsRaw(r.rateBps), C.Gold, Modifier.weight(1f))
                Info(Icons.Filled.Tune, "المحددة",
                    if (r.limit.isNotBlank()) r.limit else if (r.limitKnown) "مفتوح" else "—", C.Red, Modifier.weight(0.9f))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(C.Online, 8.dp); Spacer(Modifier.width(5.dp))
                Text("متصل • ${Fmt.durationShort(r.uptime)}", style = t.bodySmall, color = C.Online, maxLines = 1, modifier = Modifier.weight(1f))
                Text(s.label, style = t.labelMedium, color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Brush.horizontalGradient(listOf(s.color, s.c2))).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            if (expanded) {
                Spacer(Modifier.height(D.sm))
                // الشبكة والجهاز
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(D.radiusSmall)).background(C.CardHi).padding(D.md),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Line(Icons.Filled.Language, "IP", r.address)
                    Line(Icons.Filled.Smartphone, "MAC", r.mac)
                    Line(Icons.Filled.DataUsage, "المستهلك الكلي",
                        r.totalUsed?.let { Fmt.bytes(it) } ?: "—", ltr = false)
                    if (r.left.isNotBlank()) Line(Icons.Filled.HourglassBottom, "الوقت المتبقي", Fmt.durationShort(r.left), ltr = false)
                    if (r.loginBy.isNotBlank()) Line(Icons.Filled.Login, "طريقة الدخول", r.loginBy)
                    if (r.idle.isNotBlank() && Fmt.seconds(r.idle) > 60) Line(Icons.Filled.Bedtime, "خامل منذ", Fmt.durationShort(r.idle), ltr = false)
                }
                Spacer(Modifier.height(D.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SmallAction("قطع", Icons.Filled.LinkOff, C.Red, onClick = onKick)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 2.dp))
}

@Composable
private fun Info(icon: ImageVector, label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(D.radiusSmall)).background(C.CardHi).padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = C.Dim, maxLines = 1)
        }
        AutoFitText(value, C.Text)
    }
}

@Composable
private fun Line(icon: ImageVector, label: String, value: String, ltr: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Dim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(D.sm))
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Dim, modifier = Modifier.width(92.dp))
        Text(value.ifBlank { "—" }.let { if (ltr) Fmt.ltr(it) else it }, style = MaterialTheme.typography.titleSmall, color = C.Text, modifier = Modifier.weight(1f))
    }
}

/** سرعة بدون رموز العزل (القيمة تُعرض LTR داخل حقلها). */
private fun bpsRaw(v: Long): String = Fmt.bps(v).trim('\u2066', '\u2069')

/**
 * قيمة تتكيف تلقائياً مع عرض حقلها لتظهر كاملة (الرقم + الوحدة) بدون «...» إطلاقاً.
 * تُرسم مباشرة بالقياس (TextMeasurer) داخل مرحلة الرسم — بدون SubcomposeLayout/BoxWithConstraints
 * (لا يدعمان القياسات الداخلية IntrinsicSize المستخدمة في بطاقة المتصل، وكانا سبب إغلاق التطبيق)،
 * وبدون إعادة رسم متكررة فلا يرتفع ضغط المعالج مع التحديث كل 3 ثوانٍ.
 */
@Composable
private fun AutoFitText(value: String, color: Color) {
    val base = MaterialTheme.typography.labelLarge
    val maxSp = (base.fontSize.value - 2f).coerceAtLeast(10f)   // أصغر قليلاً من السابق
    val style = base.copy(color = color, fontSize = maxSp.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 8)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lineH = remember(style, density) { with(density) { measurer.measure("0 MB", style).size.height.toDp() } }
    Spacer(
        Modifier.fillMaxWidth().height(lineH).drawWithCache {
            val avail = size.width
            var layout = measurer.measure(value, style, maxLines = 1, softWrap = false)
            if (avail > 0f && layout.size.width > avail) {
                // تقدير مباشر بالتناسب ثم تصحيح بسيط إن لزم
                var sz = (maxSp * avail / layout.size.width).coerceAtLeast(6f)
                layout = measurer.measure(value, style.copy(fontSize = sz.sp), maxLines = 1, softWrap = false)
                while (sz > 6f && layout.size.width > avail) {
                    sz -= 0.5f
                    layout = measurer.measure(value, style.copy(fontSize = sz.sp), maxLines = 1, softWrap = false)
                }
            }
            val left = ((avail - layout.size.width) / 2f).coerceAtLeast(0f)
            val top = (size.height - layout.size.height) / 2f
            onDrawBehind { drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(left, top)) }
        }
    )
}
