package com.zarinet.netcontrol.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import com.zarinet.netcontrol.util.LogArabic
import com.zarinet.netcontrol.util.ScreenCache
import kotlinx.coroutines.launch

/**
 * شاشة عامة لأي قسم RouterOS (عناوين IP، جدار الحماية، NAT، DHCP...).
 * embedded = true: تُعرض داخل شاشة أخرى (تبويبات) بدون شريط علوي.
 */
@Composable
fun ResourceScreen(section: Section, onBack: () -> Unit, onConsole: (String) -> Unit, embedded: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheKey = "res:${section.key}"
    // نبدأ من آخر نسخة محفوظة (إن وُجدت) لعرض فوري
    var rows by remember { mutableStateOf<List<Map<String, String>>>(ScreenCache.get<List<Map<String, String>>>(cacheKey) ?: emptyList()) }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<Map<String, String>?>(null) }
    var deleting by remember { mutableStateOf<Map<String, String>?>(null) }

    /**
     * force: تجاهل الكاش وإعادة الجلب من الراوتر.
     * quick: عند فتح الشاشة يكفي أن يكون الكاش حديثاً (3 ثوانٍ بدلاً من 20).
     */
    fun load(force: Boolean = true, quick: Boolean = false) {
        if (!force && ScreenCache.fresh(cacheKey, if (quick) 3000L else 20000L)) { loading = false; return }
        if (force) Session.client?.invalidateCache()
        loading = rows.isEmpty(); error = null
        scope.launch {
            try {
                var r = Session.api().print(section.path)
                if (section.newestFirst) r = r.reversed()
                if (section.limit > 0) r = r.take(section.limit)
                if (section.key == "log") r = r.map { LogArabic.row(it) }
                rows = r
                ScreenCache.put(cacheKey, r)
            } catch (e: Exception) { error = e.message ?: "خطأ غير معروف" }
            loading = false
        }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block(); load() } catch (e: Exception) { toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(section.key) { load(force = false, quick = true) }

    ResourceContent(
        section = section, rows = rows, loading = loading, error = error,
        onBack = onBack, onRefresh = { load() }, onConsole = { onConsole(section.path) },
        onAdd = { form = emptyMap() },
        onEdit = { form = it },
        onDelete = { deleting = it },
        onToggle = { row ->
            val cmd = if (row["disabled"] == "true") "enable" else "disable"
            act { Session.api().exec("${section.path}/$cmd", mapOf(".id" to row[".id"]!!)) }
        },
        embedded = embedded,
        onRowAction = { row ->
            section.rowAction?.let { a ->
                act { Session.api().exec("${section.path}/${a.command}", mapOf(".id" to row[".id"]!!)) }
            }
        }
    )

    form?.let { initial ->
        ItemForm(section, initial, rows, onDismiss = { form = null }) { values ->
            form = null
            act {
                if (initial.isEmpty()) Session.api().exec("${section.path}/add", values)
                else Session.api().exec("${section.path}/set", initial[".id"]?.let { values + (".id" to it) } ?: values)
            }
        }
    }
    deleting?.let { row ->
        val verb = if (section.key == "pppactive") "فصل" else "حذف"
        ConfirmDialog(verb, "$verb \"${primaryOf(section, row)}\"؟", verb,
            onConfirm = { act { Session.api().exec("${section.path}/remove", mapOf(".id" to row[".id"]!!)) } },
            onDismiss = { deleting = null })
    }
}

@Composable
fun ResourceContent(
    section: Section,
    rows: List<Map<String, String>>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConsole: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Map<String, String>) -> Unit,
    onDelete: (Map<String, String>) -> Unit,
    onToggle: (Map<String, String>) -> Unit,
    embedded: Boolean = false,
    onRowAction: (Map<String, String>) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, rows) {
        if (query.isBlank()) rows else rows.filter { r -> r.values.any { it.contains(query, true) } }
    }
    Column(Modifier.fillMaxSize()) {
        if (!embedded) {
            TopBar(section.title, section.subtitle, onBack) {
                BarAction(Icons.Filled.Terminal, "API Console", C.Dim, onConsole)
                BarAction(Icons.Filled.Refresh, "تحديث", C.Text, onRefresh)
            }
        }
        Row(Modifier.padding(horizontal = D.screen), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.sm)) {
            SearchField(query, { query = it }, "بحث في ${section.title}...", Modifier.weight(1f))
            if (section.canAdd) AppButton("إضافة", onAdd, Modifier.height(44.dp), icon = Icons.Filled.Add)
        }
        Row(Modifier.padding(horizontal = D.screen, vertical = D.sm), verticalAlignment = Alignment.CenterVertically) {
            // في الوضع المضمّن لا يوجد شريط علوي، فنعرض زر التحديث هنا
            if (embedded) {
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, "تحديث", tint = C.Dim)
                }
            }
            Text(section.path, style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.weight(1f))
            Text(if (query.isBlank()) "${rows.size} عنصر" else "${filtered.size} من ${rows.size}",
                style = MaterialTheme.typography.labelMedium, color = C.Dim)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item {
                StateView(loading = loading && rows.isEmpty(), error = error, empty = !loading && error == null && filtered.isEmpty(),
                    emptyText = if (query.isBlank()) "لا توجد عناصر" else "لا نتائج لـ \"$query\"", onRetry = onRefresh)
            }
            items(filtered, key = { it[".id"] ?: it.hashCode().toString() }) { row ->
                ResourceCard(section, row,
                    onEdit = if (section.canEdit) ({ onEdit(row) }) else null,
                    onDelete = if (section.canDelete) ({ onDelete(row) }) else null,
                    onToggle = if (section.canToggle && row.containsKey("disabled")) ({ onToggle(row) }) else null,
                    onRowAction = if (section.rowAction?.visible?.invoke(row) == true) ({ onRowAction(row) }) else null
                )
            }
        }
    }
}

fun primaryOf(s: Section, row: Map<String, String>): String =
    s.primary.firstNotNullOfOrNull { row[it]?.takeIf(String::isNotBlank) } ?: row[".id"] ?: "—"

@Composable
private fun ResourceCard(
    section: Section,
    row: Map<String, String>,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onToggle: (() -> Unit)?,
    onRowAction: (() -> Unit)? = null
) {
    val t = MaterialTheme.typography
    var expanded by remember { mutableStateOf(false) }
    val disabled = row["disabled"] == "true"
    val primaryKey = section.primary.firstOrNull { !row[it].isNullOrBlank() }
    AppCard(Modifier.fillMaxWidth().animateContentSize(), onClick = { expanded = !expanded },
        background = if (disabled) C.Card.copy(alpha = 0.6f) else C.Card, padding = PaddingValues(D.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(section.icon, if (disabled) C.Faint else section.color, 36.dp, 20.dp)
            Spacer(Modifier.width(D.sm))
            Text(primaryOf(section, row), style = t.titleMedium, color = if (disabled) C.Dim else C.Text,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row["dynamic"] == "true") { Pill("ديناميكي", C.Gold); Spacer(Modifier.width(4.dp)) }
            if (row["invalid"] == "true") { Pill("غير صالح", C.Red); Spacer(Modifier.width(4.dp)) }
            if (row.containsKey("disabled")) Pill(if (disabled) "معطل" else "مفعل", if (disabled) C.Red else C.Online)
        }
        // الحقول المعروضة في البطاقة: كل الحقول أو المحددة في section.card فقط
        val shown = section.fields
            .filter { it.first != primaryKey && (section.card.isEmpty() || it.first in section.card) }
            .mapNotNull { (k, label) ->
                row[k]?.takeIf { it.isNotBlank() }?.let {
                    label to (if (k == "size") Fmt.bytes(it) else if (k == "running") (if (it == "true") "نعم" else "لا") else it)
                }
            }
        if (shown.isNotEmpty()) { Spacer(Modifier.height(D.md)); KvGrid(shown) }
        if (expanded) {
            Spacer(Modifier.height(D.md))
            HorizontalDivider(color = C.Divider)
            Spacer(Modifier.height(D.sm))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column {
                    row.forEach { (k, v) ->
                        Text("$k = $v", style = t.bodySmall.copy(fontFamily = FontFamily.Monospace), color = C.Dim)
                    }
                }
            }
        }
        CardActions(onEdit, onDelete, disabled, onToggle, deleteLabel = if (section.key == "pppactive") "فصل" else "حذف") {
            if (onRowAction != null) {
                SmallAction(section.rowAction!!.label, Icons.Filled.PushPin, C.Teal, onClick = onRowAction)
            }
        }
    }
}

/** نموذج إضافة/تعديل عام: حقول القسم + حقول إضافية بصيغة key=value. */
@Composable
private fun ItemForm(section: Section, initial: Map<String, String>, rows: List<Map<String, String>>, onDismiss: () -> Unit,
                     onSave: (Map<String, String>) -> Unit) {
    val editable = section.fields.filter {
        it.first !in section.readOnly &&
            it.first !in setOf("network", "status", "running", "actual-mtu", "uptime", "last-logged-in", "type")
    }
    val values = remember { mutableStateMapOf<String, String>().apply { editable.forEach { (k, _) -> put(k, initial[k] ?: "") } } }
    var extra by remember { mutableStateOf("") }
    // خيارات القوائم المنسدلة: ثابتة + من الراوتر (الواجهات، الشبكات، قوائم العناوين...)
    var dyn by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    LaunchedEffect(section.key) {
        dyn = runCatching { FieldOptions.dynamic(section.key, editable.map { it.first }.toSet(), rows) }.getOrDefault(emptyMap())
    }
    FormDialog(
        title = if (initial.isEmpty()) "إضافة — ${section.title}" else "تعديل ${primaryOf(section, initial)}",
        onConfirm = {
            val out = LinkedHashMap<String, String>()
            values.forEach { (k, v) ->
                val changed = if (initial.isEmpty()) v.isNotBlank() else v != (initial[k] ?: "")
                if (changed) {
                    out[k] = v.trim()
                }
            }
            extra.lines().map { it.trim() }.filter { it.contains('=') }.forEach {
                out[it.substringBefore('=').trim()] = it.substringAfter('=').trim()
            }
            onSave(out)
        },
        onDismiss = onDismiss
    ) {
        ItemFormFields(section, editable, values, dyn)
        Field(extra, { extra = it }, "حقول إضافية (key=value لكل سطر)", singleLine = false, ltr = true)
    }
}

/** حقول النموذج: كل حقل له خيارات جاهزة يظهر كقائمة منسدلة قابلة للكتابة. */
@Composable
internal fun ItemFormFields(section: Section, editable: List<Pair<String, String>>, values: MutableMap<String, String>,
                            dyn: Map<String, List<Pair<String, String>>>) {
    editable.forEach { (k, label) ->
        val opts = (FieldOptions.static(section.key, k) + dyn[k].orEmpty()).distinctBy { it.first }
        if (opts.isEmpty()) Field(values[k] ?: "", { values[k] = it }, label, placeholder = k)
        else ComboField(values[k] ?: "", { values[k] = it }, label, opts, placeholder = k, ltr = k != "comment")
    }
}
