package com.zarinet.netcontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zarinet.netcontrol.R

/** الوضع الليلي/النهاري الحالي (حالة Compose — تغييره يعيد رسم كل الشاشات). */
object AppTheme {
    var dark by androidx.compose.runtime.mutableStateOf(false)
    /** "light" (الافتراضي) | "dark" | "system" */
    var mode by androidx.compose.runtime.mutableStateOf("light")

    fun init(ctx: android.content.Context) {
        mode = runCatching { ctx.getSharedPreferences("mikroadmin_ui", 0).getString("theme", "light") }.getOrNull() ?: "light"
    }
    fun set(ctx: android.content.Context, m: String) {
        mode = m
        runCatching { ctx.getSharedPreferences("mikroadmin_ui", 0).edit().putString("theme", m).apply() }
    }
}

/** الألوان — مصدر واحد لكل التطبيق. الألوان المحايدة تتبدل حسب الوضع، وألوان التمييز ثابتة. */
object C {
    private val d get() = AppTheme.dark
    val Bg get() = if (d) Color(0xFF0A1628) else Color(0xFFF2F5FA)
    val BgTop get() = if (d) Color(0xFF0D2138) else Color(0xFFE6EDF6)
    val BgBottom get() = if (d) Color(0xFF071F1C) else Color(0xFFE3F2EE)
    val Card get() = if (d) Color(0xFF12223B) else Color(0xFFFFFFFF)
    val CardHi get() = if (d) Color(0xFF182B48) else Color(0xFFEFF3F9)      // عناصر داخل البطاقات
    val Field get() = if (d) Color(0xFF0E1C31) else Color(0xFFF7F9FC)
    val Border get() = if (d) Color(0xFF243E62) else Color(0xFFD3DCE8)
    val Divider get() = if (d) Color(0xFF1E3454) else Color(0xFFE3E9F1)
    val Text get() = if (d) Color(0xFFEAF1FB) else Color(0xFF0F1B2D)
    val Dim get() = if (d) Color(0xFF8FA5C2) else Color(0xFF4A5D78)
    val Faint get() = if (d) Color(0xFF5E7596) else Color(0xFF8395AE)
    val Teal get() = if (d) Color(0xFF1DC9A1) else Color(0xFF12A886)
    val OnTeal = Color(0xFF03211A)
    val Online get() = if (d) Color(0xFF34D27A) else Color(0xFF1E9E57)
    val Blue = Color(0xFF3B82D6)
    val Cyan get() = if (d) Color(0xFF1B9AC9) else Color(0xFF15829F)
    val Gold get() = if (d) Color(0xFFD29B32) else Color(0xFFB27A12)
    val Red = Color(0xFFE0566B)
    val Purple = Color(0xFF8A5CD6)
    val Green get() = if (d) Color(0xFF35B866) else Color(0xFF26944F)
    val Steel = Color(0xFF4A7FBF)
}

/** المقاسات الموحدة: مسافات، زوايا، ارتفاعات. */
object D {
    val xs = 4.dp
    val sm = 8.dp
    val md = 10.dp
    val lg = 14.dp
    val xl = 20.dp
    val screen = 12.dp          // هامش الشاشة
    val radiusCard = 16.dp
    val radiusControl = 12.dp
    val radiusSmall = 9.dp
    val control = 42.dp         // ارتفاع الأزرار والحقول
    val iconBtn = 36.dp
    val icon = 20.dp
}

val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_semibold, FontWeight.Medium),
    Font(R.font.cairo_bold, FontWeight.Bold),
)

/**
 * خط Cairo ارتفاعه الطبيعي ≈ 1.87 من حجمه، لذلك نترك ارتفاع السطر غير محدد
 * (يُحسب من مقاييس الخط) حتى لا تُقص أعلى الحروف أو أسفلها على أي هاتف أو حجم خط.
 */
private fun style(size: Float, @Suppress("UNUSED_PARAMETER") line: Int, weight: FontWeight) = TextStyle(
    fontFamily = Cairo,
    fontSize = size.sp,
    fontWeight = weight,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/**
 * سلم الخطوط:
 * displaySmall 28 — الأرقام الكبيرة
 * headlineSmall 22 — عنوان الشاشة
 * titleLarge 18 — عنوان بطاقة
 * titleMedium 16 — عنصر قائمة
 * bodyLarge 15 / bodyMedium 14 / bodySmall 12 — نصوص
 * labelLarge 15 — أزرار، labelMedium 12 — شارات
 */
val AppTypography = Typography(
    displaySmall = style(24f, 0, FontWeight.Bold),
    headlineMedium = style(20f, 0, FontWeight.Bold),
    headlineSmall = style(17f, 0, FontWeight.Bold),
    titleLarge = style(15.5f, 0, FontWeight.Bold),
    titleMedium = style(14f, 0, FontWeight.SemiBold),
    titleSmall = style(12.5f, 0, FontWeight.SemiBold),
    bodyLarge = style(13f, 0, FontWeight.Normal),
    bodyMedium = style(12.5f, 0, FontWeight.Normal),
    bodySmall = style(11f, 0, FontWeight.Normal),
    labelLarge = style(13f, 0, FontWeight.SemiBold),
    labelMedium = style(11f, 0, FontWeight.SemiBold),
    labelSmall = style(10f, 0, FontWeight.SemiBold),
)

@Composable
fun NetControlTheme(content: @Composable () -> Unit) {
    val scheme = if (AppTheme.dark) darkColorScheme(
            primary = C.Teal,
            onPrimary = C.OnTeal,
            secondary = C.Cyan,
            background = C.Bg,
            surface = C.Card,
            surfaceContainerHigh = C.Card,
            onSurface = C.Text,
            onSurfaceVariant = C.Dim,
            onBackground = C.Text,
            surfaceVariant = C.CardHi,
            outline = C.Border,
            outlineVariant = C.Divider,
            error = C.Red
        ) else androidx.compose.material3.lightColorScheme(
            primary = C.Teal, onPrimary = Color.White, secondary = C.Cyan, background = C.Bg, surface = C.Card,
            surfaceContainerHigh = C.Card, onSurface = C.Text, onSurfaceVariant = C.Dim, onBackground = C.Text,
            surfaceVariant = C.CardHi, outline = C.Border, outlineVariant = C.Divider, error = C.Red
        )
    // حجم خط النظام الكبير يكسر التصميم — نسمح بتكبير بسيط فقط (حتى 1.05)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.05f))) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
