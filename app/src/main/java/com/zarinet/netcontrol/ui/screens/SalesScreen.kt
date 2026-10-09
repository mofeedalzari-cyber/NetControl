package com.zarinet.netcontrol.ui.screens

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.DeviceStore
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PERIODS = listOf("اليوم", "7 أيام", "هذا الشهر", "الكل")

private fun sinceOf(period: Int): Long? {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return when (period) {
        0 -> c.timeInMillis
        1 -> c.apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
        2 -> c.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        else -> null
    }
}

@Composable
fun SalesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { DeviceStore(ctx) }
    val scope = rememberCoroutineScope()
    val devId = Session.device?.id ?: ""
    var source by remember { mutableIntStateOf(0) }   // 0 هوتسبوت، 1 يوزر منجر
    var period by remember { mutableIntStateOf(3) }
    var cards by remember { mutableStateOf<List<CardRec>>(emptyList()) }
    var umPrices by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var editPrices by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<java.io.File?>(null) }
    var manualPrices by remember { mutableStateOf(loadPrices(store, devId)) }

    LaunchedEffect(source, refresh) {
        loading = true; error = null; cards = emptyList()
        try {
            if (source == 0) {
                cards = Session.api().print("/ip/hotspot/user", listOf("comment", "profile", "uptime", "bytes-in", "bytes-out")).map {
                    val used = Fmt.seconds(it["uptime"]) > 0 || (it["bytes-in"]?.toLongOrNull() ?: 0) > 0
                    CardRec(it["comment"].orEmpty(), it["profile"].orEmpty(), used)
                }
                umPrices = emptyMap()
            } else {
                cards = UserManager.users().map { CardRec(it.comment, it.profile, it.status != UmStatus.UNUSED) }
                umPrices = UserManager.packages().associate { it.name to (it.price.filter(Char::isDigit).toLongOrNull() ?: 0L) }.filterValues { it > 0 }
            }
        } catch (e: Exception) { error = e.message ?: "خطأ" }
        loading = false
    }

    val target = if (source == 0) "HOTSPOT" else "USERMAN"   // CardTarget.name
    val logs = remember(refresh, source) { SaleLog.listFrom(store.getString("sales_$devId", "[]")).filter { it.target == target } }
    val reps = remember(cards, logs, umPrices, manualPrices, period) {
        Sales.report(cards, logs, umPrices + manualPrices, sinceOf(period))
    }
    val profiles = remember(cards) { cards.map { it.profile }.filter { it.isNotBlank() }.distinct().sorted() }

    SalesContent(
        source = source, period = period, reps = reps, loading = loading, error = error,
        onBack = onBack, onSource = { source = it }, onPeriod = { period = it },
        onRefresh = { refresh++ }, onPrices = { editPrices = true },
        onShare = {
            val title = "تقرير المبيعات — ${if (source == 0) "الهوتسبوت" else "يوزر منجر"} — ${Session.device?.name.orEmpty()}"
            val text = Sales.shareText(title, PERIODS[period], reps)
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                "مشاركة التقرير").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onPdf = {
            scope.launch {
                try {
                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SalesPdf.build(ctx, Session.device?.name.orEmpty(), if (source == 0) "الهوتسبوت" else "يوزر منجر", PERIODS[period], reps)
                    }
                    pdf = f
                } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    )
    pdf?.let { f ->
        AppDialog("تقرير المبيعات PDF", onDismiss = { pdf = null }, buttons = {
            AppButton("مشاركة", { VoucherPdf.share(ctx, f); pdf = null }, kind = BtnKind.Secondary, icon = Icons.Filled.Share)
            AppButton("فتح", { VoucherPdf.open(ctx, f); pdf = null }, icon = Icons.Filled.PictureAsPdf)
        }) {
            Text("تم إنشاء التقرير (${reps.size} نقطة بيع).", style = MaterialTheme.typography.bodyLarge, color = C.Text)
            Text(f.name, style = MaterialTheme.typography.bodySmall, color = C.Dim)
        }
    }

    if (editPrices) {
        val draft = remember { mutableStateMapOf<String, String>().apply { profiles.forEach { put(it, (manualPrices[it] ?: umPrices[it])?.toString().orEmpty()) } } }
        FormDialog("أسعار الباقات", onDismiss = { editPrices = false }, onConfirm = {
            val m = draft.mapNotNull { (k, v) -> v.toLongOrNull()?.takeIf { it > 0 }?.let { k to it } }.toMap()
            manualPrices = m
            store.putString("prices_$devId", JSONObject(m.mapValues { it.value }).toString())
            editPrices = false
        }) {
            Text("يُستخدم سعر الباقة للكروت التي طُبعت بدون سعر.", style = MaterialTheme.typography.bodySmall, color = C.Dim)
            if (profiles.isEmpty()) Text("لا توجد باقات في الكروت الحالية", color = C.Dim)
            profiles.forEach { p -> Field(draft[p].orEmpty(), { draft[p] = it.filter(Char::isDigit) }, p, number = true) }
        }
    }
}

private fun loadPrices(store: DeviceStore, devId: String): Map<String, Long> = try {
    val o = JSONObject(store.getString("prices_$devId", "{}"))
    o.keys().asSequence().associateWith { o.optLong(it) }.filterValues { it > 0 }
} catch (_: Exception) { emptyMap() }

@Composable
fun SalesContent(
    source: Int,
    period: Int,
    reps: List<PosRep>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSource: (Int) -> Unit,
    onPeriod: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPrices: () -> Unit,
    onShare: () -> Unit,
    onPdf: () -> Unit = {}
) {
    val t = MaterialTheme.typography
    val revenue = reps.sumOf { it.revenue }
    val sold = reps.sumOf { it.sold }
    val remaining = reps.sumOf { it.remaining }
    val stock = reps.sumOf { it.stockValue }
    val noPrice = reps.flatMap { it.batches }.any { it.price == 0L && it.sold > 0 }

    Column(Modifier.fillMaxSize()) {
        TopBar("تقارير المبيعات", "حسب نقطة البيع • ${PERIODS[period]}", onBack) {
            BarAction(Icons.Filled.Sell, "أسعار الباقات", C.Dim, onPrices)
            BarAction(Icons.Filled.PictureAsPdf, "تقرير PDF", C.Text, onPdf)
            BarAction(Icons.Filled.Share, "مشاركة نصية", C.Text, onShare)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item { SegTabs(listOf("الهوتسبوت", "يوزر منجر"), source) { onSource(it) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PERIODS.forEachIndexed { i, p ->
                        val sel = i == period
                        Text(p, style = t.labelMedium, color = if (sel) C.OnTeal else C.Dim, maxLines = 1,
                            modifier = Modifier.weight(1f).clip(CircleShape).background(if (sel) C.Teal else C.Card)
                                .clickable { onPeriod(i) }.padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            // ── الملخص ──
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("إجمالي المبيعات", style = t.bodyMedium, color = C.Dim)
                    Text(Fmt.ltr(Sales.money(revenue)), style = t.displaySmall, color = C.Text)
                    Spacer(Modifier.height(D.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                        KvTile("كروت مباعة", "$sold", Modifier.weight(1f))
                        KvTile("متبقي", "$remaining", Modifier.weight(1f))
                        KvTile("قيمة المتبقي", Fmt.ltr(Sales.money(stock)), Modifier.weight(1f))
                    }
                    if (noPrice) {
                        Spacer(Modifier.height(D.sm))
                        Row(Modifier.clickable(onClick = onPrices), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, null, tint = C.Gold, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("بعض الدفعات بدون سعر — اضغط لتحديد أسعار الباقات", style = t.bodySmall, color = C.Gold)
                        }
                    }
                }
            }
            // ── مخطط: المبيعات لكل نقطة بيع ──
            if (reps.isNotEmpty() && revenue > 0) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text("المبيعات لكل نقطة بيع", style = t.titleMedium, color = C.Text)
                    Spacer(Modifier.height(D.md))
                    val max = reps.maxOf { it.revenue }.coerceAtLeast(1)
                    reps.take(8).forEach { r ->
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.pos, style = t.bodySmall, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(88.dp))
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((r.revenue.toFloat() / max).coerceAtLeast(0.01f))
                                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(C.Teal))
                            }
                            Text(Fmt.ltr(Sales.money(r.revenue)), style = t.labelMedium, color = C.Text,
                                modifier = Modifier.width(72.dp).padding(start = 6.dp), maxLines = 1)
                        }
                    }
                    if (reps.size > 8) Text("و${reps.size - 8} نقاط أخرى في القائمة أدناه", style = t.bodySmall, color = C.Faint)
                }
            }
            item { StateView(loading && reps.isEmpty(), error, !loading && error == null && reps.isEmpty(),
                "لا توجد مبيعات في هذه الفترة.\nالكروت المطبوعة من التطبيق تحمل نقطة البيع تلقائياً.", onRefresh) }
            item { if (reps.isNotEmpty()) SectionTitle("نقاط البيع") { CountBadge(reps.size, C.Cyan) } }
            items(reps, key = { it.pos }) { r -> PosCard(r) }
            item {
                Text("• المباع = الكروت المستخدمة + الكروت التي انتهت وحُذفت من الراوتر.\n• الفترة حسب تاريخ طباعة الدفعة.",
                    style = t.bodySmall, color = C.Faint, modifier = Modifier.padding(top = D.sm))
            }
        }
    }
}

@Composable
private fun PosCard(r: PosRep) {
    val t = MaterialTheme.typography
    var open by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    AppCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { open = !open }, padding = PaddingValues(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Storefront, if (r.pos == Sales.NO_POS) C.Faint else C.Gold, 40.dp, 22.dp)
            Spacer(Modifier.width(D.sm))
            Column(Modifier.weight(1f)) {
                Text(r.pos, style = t.titleMedium, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.batches.size} دفعة • ${r.issued} كرت", style = t.bodySmall, color = C.Dim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.ltr(Sales.money(r.revenue)), style = t.titleMedium, color = C.Teal)
                Text("مبيعات", style = t.labelSmall, color = C.Faint)
            }
        }
        Spacer(Modifier.height(D.sm))
        val p = if (r.issued > 0) r.sold.toFloat() / r.issued else 0f
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = C.Teal, trackColor = C.CardHi, drawStopIndicator = {}, gapSize = 0.dp)
        Spacer(Modifier.height(D.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            KvTile("مباع", "${r.sold}", Modifier.weight(1f))
            KvTile("متبقي", "${r.remaining}", Modifier.weight(1f))
            KvTile("نسبة البيع", "${(p * 100).toInt()}%", Modifier.weight(1f))
        }
        if (open) {
            Spacer(Modifier.height(D.md))
            HorizontalDivider(color = C.Divider)
            r.batches.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.ltr(b.tag), style = t.titleSmall, color = C.Text)
                        Text(listOfNotNull(b.date?.let { fmt.format(Date(it)) }, b.profile.ifBlank { null },
                            if (b.price > 0) "${Sales.money(b.price)} للكرت" else "بدون سعر").joinToString(" • "),
                            style = t.bodySmall, color = C.Dim)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${b.sold}/${b.issued}", style = t.titleSmall, color = C.Text)
                        Text(Fmt.ltr(Sales.money(b.revenue)), style = t.bodySmall, color = C.Teal)
                    }
                }
            }
        } else {
            Text("اضغط لعرض الدفعات", style = t.labelSmall, color = C.Faint, modifier = Modifier.padding(top = D.xs))
        }
    }
}
