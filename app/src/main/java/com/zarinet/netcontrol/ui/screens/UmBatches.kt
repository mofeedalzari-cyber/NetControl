package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.RosTime
import com.zarinet.netcontrol.util.ScreenCache
import com.zarinet.netcontrol.util.UmUserRow
import com.zarinet.netcontrol.util.UserManager
import kotlinx.coroutines.launch

/** عرض كروت يوزر منجر حسب الدفعات: اختيار الدفعة ثم عرض كروتها وحالاتها فقط. */
@Composable
fun UmBatchesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var batches by remember { mutableStateOf(ScreenCache.get<List<UserManager.UmBatch>>("um:batches") ?: emptyList()) }
    var selected by remember { mutableStateOf(ScreenCache.get<String>("um:batchSel")) }
    var cards by remember { mutableStateOf(selected?.let { ScreenCache.get<List<UmUserRow>>("um:batch:$it") } ?: emptyList()) }
    var loadingList by remember { mutableStateOf(batches.isEmpty()) }
    var loadingCards by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(UmView.ALL) }
    var query by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<UmUserRow?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, UmUserRow>?>(null) }

    fun loadCards(comment: String) {
        loadingCards = true; error = null
        scope.launch {
            try {
                cards = UserManager.batchCards(comment)
                ScreenCache.put("um:batch:$comment", cards)
            } catch (e: Exception) { error = e.message ?: "خطأ" }
            loadingCards = false
        }
    }

    fun loadList() {
        loadingList = batches.isEmpty(); error = null
        scope.launch {
            try {
                batches = UserManager.batches()
                ScreenCache.put("um:batches", batches)
                val sel = selected?.takeIf { s -> batches.any { it.comment == s } } ?: batches.firstOrNull()?.comment
                if (sel != selected || cards.isEmpty()) {
                    selected = sel; ScreenCache.put("um:batchSel", sel)
                    if (sel != null) loadCards(sel) else cards = emptyList()
                }
            } catch (e: Exception) { if (batches.isEmpty()) error = e.message ?: "خطأ" }
            loadingList = false
        }
    }

    fun act(action: String, u: UmUserRow) {
        val label = when (action) { "remove" -> "حذف"; "disable" -> "تعطيل"; else -> "تفعيل" }
        scope.launch {
            try {
                UserManager.bulk(action, listOf(u.id))
                toast(ctx, "تم $label ${u.name}", Toast.LENGTH_SHORT).show()
                ScreenCache.invalidate("um:users")
                selected?.let { loadCards(it) }
                if (action == "remove") loadList()
            } catch (e: Exception) { toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        if (!ScreenCache.fresh("um:batches", 30_000L) || batches.isEmpty()) loadList()
        else selected?.let { if (cards.isEmpty()) loadCards(it) }
    }

    val current = batches.firstOrNull { it.comment == selected }
    val labels = remember(batches) { batches.map { "${it.label}  (${it.count})" } }
    val counts = remember(cards) { cards.groupingBy { it.view() }.eachCount() }
    val shown = remember(cards, filter, query) {
        cards.filter { u ->
            (filter == UmView.ALL || u.view() == filter) &&
                (query.isBlank() || u.name.contains(query, true) || u.password.contains(query, true) || u.profile.contains(query, true))
        }
    }
    val t = MaterialTheme.typography

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = D.screen), verticalArrangement = Arrangement.spacedBy(D.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                Dropdown("الدفعة", current?.let { "${it.label}  (${it.count})" }.orEmpty(), labels, Modifier.weight(1f)) { l ->
                    val b = batches.getOrNull(labels.indexOf(l)) ?: return@Dropdown
                    if (b.comment != selected) {
                        selected = b.comment; ScreenCache.put("um:batchSel", b.comment)
                        filter = UmView.ALL; query = ""
                        cards = ScreenCache.get<List<UmUserRow>>("um:batch:${b.comment}") ?: emptyList()
                        loadCards(b.comment)
                    }
                }
                AppButton("", { loadList(); selected?.let { loadCards(it) } }, Modifier.height(50.dp), BtnKind.Secondary, Icons.Filled.Refresh)
            }
            // شرائح الحالات مع الأعداد داخل الدفعة
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                UmView.entries.forEach { v ->
                    val n = if (v == UmView.ALL) cards.size else counts[v] ?: 0
                    val sel = v == filter
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(D.radiusSmall))
                            .background(Brush.verticalGradient(if (sel) listOf(v.c2, v.c1) else listOf(C.Card, C.Card)))
                            .border(1.dp, if (sel) Color.Transparent else v.solid.copy(alpha = 0.55f), RoundedCornerShape(D.radiusSmall))
                            .clickable { filter = v }.padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("$n", style = t.titleSmall.copy(fontWeight = FontWeight.Bold), color = if (sel) Color.White else v.solid, maxLines = 1)
                        Text(v.short, style = t.labelSmall, color = if (sel) Color.White else C.Dim, maxLines = 1)
                    }
                }
            }
            SearchField(query, { query = it }, "بحث داخل الدفعة: رقم الكرت، كلمة السر...")
            Text("المعروض: ${shown.size}" + (current?.let { " من ${cards.size} • ${it.label}" } ?: ""),
                style = t.labelMedium, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, top = D.xs, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                StateView((loadingList && batches.isEmpty()) || (loadingCards && cards.isEmpty()), error,
                    !loadingList && !loadingCards && error == null && shown.isEmpty(),
                    if (batches.isEmpty()) "لا توجد دفعات" else "لا توجد كروت", { loadList() })
            }
            itemsIndexed(shown, key = { _, u -> u.id }) { i, u ->
                BatchCardRow(i + 1, u) { details = u }
            }
        }
    }

    details?.let { u ->
        AppDialog(u.name, onDismiss = { details = null }, buttons = {
            AppButton(if (u.disabled) "تفعيل" else "تعطيل", {
                details = null
                if (u.disabled) act("enable", u) else confirm = "disable" to u
            }, Modifier.weight(1f), BtnKind.Secondary)
            AppButton("حذف", { details = null; confirm = "remove" to u }, Modifier.weight(1f), BtnKind.Danger)
            AppButton("إغلاق", { details = null }, Modifier.weight(1f), BtnKind.Ghost)
        }) { KvGrid(umDetails(u)) }
    }
    confirm?.let { (action, u) ->
        val del = action == "remove"
        ConfirmDialog(if (del) "حذف الكرت" else "تعطيل الكرت", "هل تريد ${if (del) "حذف" else "تعطيل"} الكرت ${u.name}؟",
            if (del) "حذف" else "تعطيل", danger = true,
            onConfirm = { act(action, u) }, onDismiss = { confirm = null })
    }
}

@Composable
private fun BatchCardRow(index: Int, u: UmUserRow, onClick: () -> Unit) {
    val t = MaterialTheme.typography
    val v = u.view()
    val shape = RoundedCornerShape(D.radiusSmall)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, v.solid.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick).padding(horizontal = D.sm, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(v.solid.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text("$index", style = t.labelMedium, color = v.solid, maxLines = 1)
        }
        Spacer(Modifier.width(D.sm))
        Column(Modifier.weight(1f)) {
            Text(Fmt.ltr(u.name), style = t.titleMedium, color = Color(0xFF2FA8DE), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                u.password.takeIf { it.isNotBlank() && it != u.name }?.let { "كلمة السر: " + Fmt.ltr(it) },
                u.profile.takeIf { it.isNotBlank() },
                u.lastSeen?.let { "آخر ظهور: " + RosTime.date(it) }
            ).joinToString(" • ")
            if (sub.isNotBlank()) Text(sub, style = t.bodySmall, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(D.sm))
        Text(v.short, style = t.labelMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.clip(CircleShape).background(Brush.horizontalGradient(listOf(v.c1, v.c2))).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}
