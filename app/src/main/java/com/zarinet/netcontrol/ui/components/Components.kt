package com.zarinet.netcontrol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zarinet.netcontrol.R
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D

val T @Composable get() = MaterialTheme.typography

// ─────────────────────────── الخلفية ───────────────────────────

/** خلفية داكنة مع زخرفة شبكة خفيفة (أسهم تنزيل/رفع وخط بياني). */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.BgTop, C.Bg, C.BgBottom)))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val a = 0.10f
            val xs = listOf(0.12f, 0.31f, 0.50f, 0.69f, 0.88f)
            xs.forEach { x -> drawLine(C.Blue.copy(alpha = a), Offset(w * x, 0f), Offset(w * x, h * 0.12f), 4f) }
            xs.forEach { x ->
                val top = h * 0.86f
                drawLine(C.Green.copy(alpha = a), Offset(w * x, h), Offset(w * x, top), 4f)
                drawPath(Path().apply { moveTo(w * x - 12f, top + 14f); lineTo(w * x, top); lineTo(w * x + 12f, top + 14f); close() },
                    C.Green.copy(alpha = a))
            }
            val pts = listOf(0.10f to 0.86f, 0.30f to 0.80f, 0.50f to 0.88f, 0.70f to 0.80f, 0.92f to 0.90f)
                .map { Offset(w * it.first, h * it.second) }
            drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } },
                C.Teal.copy(alpha = 0.14f), style = Stroke(2.5f))
            pts.forEach { drawCircle(C.Teal.copy(alpha = 0.22f), 6f, it) }
        }
        content()
    }
}

// ─────────────────────────── الشريط العلوي ───────────────────────────

/** شريط علوي موحد لكل الشاشات. */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    navIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = D.sm, vertical = D.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onBack) { Icon(navIcon, "رجوع", tint = C.Text) }
        } else Spacer(Modifier.width(D.sm))
        Column(Modifier.weight(1f).padding(horizontal = D.xs)) {
            Text(title, style = T.headlineSmall, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank())
                Text(subtitle, style = T.bodySmall, color = C.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun BarAction(icon: ImageVector, desc: String, tint: Color = C.Text, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, desc, tint = tint) }
}

// ─────────────────────────── البطاقات ───────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    border: Color = C.Border,
    background: Color = C.Card,
    padding: PaddingValues = PaddingValues(D.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(D.radiusCard)
    var m = modifier.clip(shape).background(background).border(BorderStroke(1.dp, border), shape)
    if (onClick != null || onLongClick != null) m = m.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    Column(m.padding(padding), content = content)
}

/** أيقونة داخل خلفية ملونة. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 36.dp, iconSize: Dp = 20.dp, circle: Boolean = false) {
    Box(
        Modifier.size(size)
            .clip(if (circle) CircleShape else RoundedCornerShape(D.radiusControl))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = color, modifier = Modifier.size(iconSize)) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = D.sm), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).background(C.Teal, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(D.sm))
        Text(text, style = T.titleLarge, color = C.Text, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** مربع مفتاح/قيمة. */
@Composable
fun KvTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = C.Text) {
    Column(
        modifier.clip(RoundedCornerShape(D.radiusSmall)).background(C.CardHi).padding(horizontal = D.md, vertical = D.sm)
    ) {
        Text(label, style = T.bodySmall, color = C.Dim)
        Text(value.ifBlank { "—" }, style = T.titleSmall, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** شبكة مفاتيح/قيم بعمودين. */
@Composable
fun KvGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(D.sm)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                row.forEach { (l, v) -> KvTile(l, v, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text, style = T.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
fun CountBadge(n: Int, color: Color) {
    Text(
        "$n", style = T.titleMedium, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(D.radiusSmall)).background(color).padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

// ─────────────────────────── الأزرار ───────────────────────────

enum class BtnKind { Primary, Secondary, Danger, Warning, Ghost }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val (bg, fg) = when (kind) {
        BtnKind.Primary -> C.Teal to C.OnTeal
        BtnKind.Secondary -> C.CardHi to C.Text
        BtnKind.Danger -> C.Red.copy(alpha = 0.16f) to C.Red
        BtnKind.Warning -> C.Gold.copy(alpha = 0.16f) to C.Gold
        BtnKind.Ghost -> Color.Transparent to C.Teal
    }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = D.control),
        shape = RoundedCornerShape(D.radiusControl),
        colors = ButtonDefaults.buttonColors(bg, fg, bg.copy(alpha = 0.4f), fg.copy(alpha = 0.5f)),
        border = if (kind == BtnKind.Secondary) BorderStroke(1.dp, C.Border) else null,
        contentPadding = PaddingValues(horizontal = D.lg)
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(D.sm))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(D.sm))
        }
        Text(text, style = T.labelLarge, maxLines = 1)
    }
}

/** زر صغير داخل البطاقات (تعديل/تعطيل/حذف). */
@Composable
fun SmallAction(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(D.radiusSmall)).background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = D.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = T.labelMedium, color = color, maxLines = 1)
    }
}

/** صف أزرار الإجراءات الموحد أسفل البطاقات. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardActions(
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    disabled: Boolean = false,
    onToggle: (() -> Unit)? = null,
    deleteLabel: String = "حذف",
    extra: @Composable RowScope.() -> Unit = {}
) {
    if (onEdit == null && onDelete == null && onToggle == null) return
    Spacer(Modifier.height(D.md))
    // FlowRow: تنتقل الأزرار لسطر جديد بدل أن تُقص عند تكبير الخط أو ضيق الشاشة
    FlowRow(horizontalArrangement = Arrangement.spacedBy(D.sm), verticalArrangement = Arrangement.spacedBy(D.sm)) {
        if (onEdit != null) SmallAction("تعديل", Icons.Filled.Edit, C.Steel, onClick = onEdit)
        if (onToggle != null) SmallAction(if (disabled) "تفعيل" else "تعطيل",
            if (disabled) Icons.Filled.CheckCircle else Icons.Filled.Block, if (disabled) C.Green else C.Gold, onClick = onToggle)
        Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) { extra() }
        if (onDelete != null) SmallAction(deleteLabel, Icons.Filled.DeleteOutline, C.Red, onClick = onDelete)
    }
}

// ─────────────────────────── الحقول ───────────────────────────

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = C.Field,
    unfocusedContainerColor = C.Field,
    disabledContainerColor = C.Field,
    focusedBorderColor = C.Teal,
    unfocusedBorderColor = C.Border,
    focusedTextColor = C.Text,
    unfocusedTextColor = C.Text,
    cursorColor = C.Teal,
    focusedLabelColor = C.Teal,
    unfocusedLabelColor = C.Dim,
    focusedPlaceholderColor = C.Faint,
    unfocusedPlaceholderColor = C.Faint,
)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    ltr: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = if (label.isBlank()) null else ({ Text(label, style = T.bodyMedium) }),
        placeholder = placeholder?.let { { Text(it, style = T.bodyMedium) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        // الحقول التقنية (عناوين، منافذ...) تُكتب من اليسار لليمين حتى داخل واجهة عربية
        textStyle = T.bodyLarge.copy(
            textAlign = if (ltr) TextAlign.Left else TextAlign.Start,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content
        ),
        shape = RoundedCornerShape(D.radiusControl),
        colors = fieldColors(),
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) ({
            IconButton({ visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = C.Dim)
            }
        }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { number -> KeyboardType.Number; password -> KeyboardType.Password; ltr -> KeyboardType.Uri; else -> KeyboardType.Text }
        ),
        modifier = modifier
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String = "ابحث...", modifier: Modifier = Modifier.fillMaxWidth()) {
    CompactSearch(value, onChange, hint, modifier)
}

/** حقل بحث مدمج (ارتفاع 44dp) بدون إطار OutlinedTextField. */
@Composable
fun CompactSearch(value: String, onChange: (String) -> Unit, hint: String = "بحث...", modifier: Modifier = Modifier.fillMaxWidth()) {
    val shape = RoundedCornerShape(D.radiusSmall)
    Row(
        modifier.height(44.dp).clip(shape).background(C.Field).border(1.dp, C.Border, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = C.Dim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty())
                Text(hint, style = T.bodyMedium, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = T.bodyLarge.copy(color = C.Text),
                singleLine = true,
                cursorBrush = SolidColor(C.Teal)
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, null, tint = C.Dim, modifier = Modifier.size(20.dp).clickable { onChange("") })
        }
    }
}

/** قائمة اختيار مدمجة (ارتفاع 44dp): التسمية والقيمة في سطر واحد. */
@Composable
fun CompactDropdown(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val shape = RoundedCornerShape(D.radiusSmall)
        Row(
            Modifier.height(44.dp).clip(shape).background(C.Field).border(1.dp, C.Border, shape)
                .clickable { open = true }.padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = T.labelMedium, color = C.Dim, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(value, style = T.titleSmall, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, null, tint = C.Teal)
        }
        DropdownMenu(open, { open = false }, containerColor = C.CardHi, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach {
                DropdownMenuItem(text = { Text(it, style = T.bodyLarge, color = if (it == value) C.Teal else C.Text) },
                    onClick = { onSelect(it); open = false })
            }
        }
    }
}

/** قائمة اختيار بشكل حقل. */
@Composable
fun Dropdown(label: String, value: String, options: List<String>, modifier: Modifier = Modifier.fillMaxWidth(), onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(D.radiusControl)).background(C.Field)
                .border(1.dp, C.Border, RoundedCornerShape(D.radiusControl)).clickable { open = true }
                .padding(horizontal = D.md, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (label.isNotBlank()) Text(label, style = T.bodySmall, color = C.Dim)
                Text(value.ifBlank { "اختر..." }, style = T.bodyLarge, color = if (value.isBlank()) C.Faint else C.Text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ExpandMore, null, tint = C.Dim)
        }
        DropdownMenu(open, { open = false }, containerColor = C.CardHi) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("لا توجد خيارات", color = C.Dim) }, onClick = { open = false })
            options.forEach {
                DropdownMenuItem(text = { Text(it, style = T.bodyLarge, color = if (it == value) C.Teal else C.Text) },
                    onClick = { onSelect(it); open = false })
            }
        }
    }
}

// ─────────────────────────── الحالات ───────────────────────────

@Composable
fun StateView(loading: Boolean, error: String?, empty: Boolean, emptyText: String = "لا توجد بيانات", onRetry: (() -> Unit)? = null) {
    when {
        loading -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = C.Teal, strokeWidth = 3.dp)
            Spacer(Modifier.height(D.md))
            Text("جاري التحميل...", style = T.bodyMedium, color = C.Dim)
        }
        error != null -> Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Filled.ErrorOutline, C.Red, 56.dp, 30.dp, circle = true)
            Spacer(Modifier.height(D.md))
            Text(error, style = T.bodyMedium, color = C.Text, textAlign = TextAlign.Center)
            if (onRetry != null) { Spacer(Modifier.height(D.md)); AppButton("إعادة المحاولة", onRetry, kind = BtnKind.Secondary, icon = Icons.Filled.Refresh) }
        }
        empty -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Filled.Inbox, C.Dim, 56.dp, 30.dp, circle = true)
            Spacer(Modifier.height(D.md))
            Text(emptyText, style = T.bodyMedium, color = C.Dim)
        }
    }
}

// ─────────────────────────── النوافذ ───────────────────────────

/**
 * ارتفاع شريط التنقل عندما لا يبلّغ النظام عنه في الـ insets (بعض الأجهزة/النوافذ):
 * نقرأ navigation_bar_height من موارد النظام، وإلا 48dp. يُعيد 0 إذا كانت الـ insets متوفرة.
 */
@Composable
fun navBarFallback(): Dp {
    val density = LocalDensity.current
    if (WindowInsets.navigationBars.getBottom(density) > 0) return 0.dp
    val res = LocalContext.current.resources
    val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) with(density) { res.getDimensionPixelSize(id).toDp() } else 48.dp
}

/** إطار نافذة موحد: العنوان ثابت، المحتوى يُمرَّر، والأزرار ثابتة أسفل النافذة ولا تختفي خلف شريط التنقل. */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(bottom = navBarFallback())
                .padding(horizontal = D.lg, vertical = D.xl),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(D.radiusCard)).background(C.Card)
                    .border(1.dp, C.Border, RoundedCornerShape(D.radiusCard))
            ) {
                Text(title, style = T.titleLarge, color = C.Text,
                    modifier = Modifier.fillMaxWidth().padding(start = D.xl, end = D.xl, top = D.xl, bottom = D.md))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = D.xl),
                    verticalArrangement = Arrangement.spacedBy(D.md),
                    content = content
                )
                HorizontalDivider(color = C.Divider, modifier = Modifier.padding(top = D.md))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = D.lg, vertical = D.md),
                    horizontalArrangement = Arrangement.spacedBy(D.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "تأكيد", danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(title, onDismiss, buttons = {
        AppButton("إلغاء", onDismiss, kind = BtnKind.Ghost)
        AppButton(confirm, { onConfirm(); onDismiss() }, kind = if (danger) BtnKind.Danger else BtnKind.Primary)
    }) {
        Text(text, style = T.bodyLarge, color = C.Dim)
    }
}

/** نافذة نموذج موحدة (عنوان + حقول قابلة للتمرير + حفظ/إلغاء). */
@Composable
fun FormDialog(
    title: String,
    confirmText: String = "حفظ",
    enabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AppDialog(title, onDismiss, buttons = {
        AppButton("إلغاء", onDismiss, kind = BtnKind.Ghost)
        AppButton(confirmText, onConfirm, enabled = enabled)
    }, content = content)
}


/**
 * حقل نصي مع قائمة منسدلة من خيارات جاهزة (قيمة + وصف). يبقى قابلاً للكتابة اليدوية.
 */
@Composable
fun ComboField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    ltr: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = if (label.isBlank()) null else ({ Text(label, style = T.bodyMedium) }),
            placeholder = placeholder?.let { { Text(it, style = T.bodyMedium) } },
            singleLine = true,
            textStyle = T.bodyLarge.copy(
                textAlign = if (ltr) TextAlign.Left else TextAlign.Start,
                textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content
            ),
            shape = RoundedCornerShape(D.radiusControl),
            colors = fieldColors(),
            trailingIcon = if (options.isEmpty()) null else ({
                IconButton({ open = true }) { Icon(Icons.Filled.ArrowDropDown, "اختر", tint = C.Teal) }
            }),
            keyboardOptions = KeyboardOptions(keyboardType = if (ltr) KeyboardType.Uri else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(open, { open = false }, containerColor = C.CardHi, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (v, desc) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.ifBlank { "(فارغ)" }, style = T.bodyLarge, color = if (v == value) C.Teal else C.Text)
                            if (desc.isNotBlank()) { Spacer(Modifier.width(8.dp)); Text(desc, style = T.bodySmall, color = C.Dim, maxLines = 1) }
                        }
                    },
                    onClick = { onChange(v); open = false })
            }
        }
    }
}

// ─────────────────────────── الشعار والباقات ───────────────────────────

/** شعار التطبيق: أيقونة المشغّل فوق تدرج أخضر/أزرق. */
@Composable
fun AppLogo(size: Dp, corner: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFF14B89A), Color(0xFF0B2F6E)))),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

/**
 * بطاقة باقة مدمجة: الاسم + شارة + أزرار تعديل/حذف، ثم إحصائيات في مربعات صغيرة
 * (4 في الصف إذا كانت 4 بالضبط، وإلا 3).
 */
@Composable
fun PackageCard(
    name: String,
    badge: String?,
    badgeColor: Color,
    stats: List<Pair<String, String>>,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = D.md, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Layers, C.Teal, 30.dp, 17.dp)
            Spacer(Modifier.width(D.sm))
            Text(name, style = T.titleSmall.copy(fontWeight = FontWeight.Bold), color = C.Text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!badge.isNullOrBlank()) { Pill(badge, badgeColor); Spacer(Modifier.width(4.dp)) }
            if (onEdit != null) IconButton(onEdit, Modifier.size(34.dp)) {
                Icon(Icons.Filled.Edit, "تعديل", Modifier.size(19.dp), tint = C.Steel)
            }
            if (onDelete != null) IconButton(onDelete, Modifier.size(34.dp)) {
                Icon(Icons.Filled.DeleteOutline, "حذف", Modifier.size(19.dp), tint = C.Red)
            }
        }
        Spacer(Modifier.height(8.dp))
        val perRow = if (stats.size == 4) 4 else 3
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { (label, v) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(D.radiusSmall)).background(C.CardHi)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(v, style = T.labelLarge.copy(fontWeight = FontWeight.Bold), color = C.Text,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(label, style = T.labelSmall, color = C.Dim,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
