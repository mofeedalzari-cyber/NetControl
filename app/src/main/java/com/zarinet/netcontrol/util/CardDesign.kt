package com.zarinet.netcontrol.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.json.JSONObject
import com.zarinet.netcontrol.R

/** عناصر الكرت القابلة للسحب. */
enum class El(val label: String) {
    USER("اسم المستخدم"), PASS("كلمة السر"), NETWORK("اسم الشبكة"), TIME("الوقت المسموح"),
    VALID("الصلاحية"), UPTIME("مدة المستخدم"), DATA("حجم الرصيد"), PRICE("السعر"),
    NOTE1("ملاحظات 1"), NOTE2("ملاحظات 2"), SERIAL("الرقم التسلسلي"), QR("الباركود")
}

/** موضع عنصر: مركزه كنسبة من عرض/ارتفاع الكرت، والحجم كنسبة من ارتفاع الكرت. */
data class ElemPos(val x: Float, val y: Float, val size: Float)

data class CardTemplate(
    val id: String,
    val name: String,
    val userColor: Int,
    val textColor: Int,
    val positions: Map<El, ElemPos>,
    val res: Int? = null,                       // صورة خلفية جاهزة من التطبيق
    val colors: Map<El, Int> = emptyMap(),      // ألوان مخصصة لكل عنصر
    val cols: Int? = null, val rows: Int? = null,
    val shown: Set<El>? = null                  // العناصر الظاهرة افتراضياً مع هذا النموذج
) { val aspect: Float get() = if (res != null) 4f else 2f }

/** الخطوط المتاحة للكرت. */
val CARD_FONTS = linkedMapOf("cairo" to "Cairo عريض", "cairo_semi" to "Cairo متوسط", "cairo_reg" to "Cairo خفيف",
    "sans" to "عادي", "mono" to "أرقام ثابتة", "serif" to "كلاسيكي")

/** تصميم الكرت الكامل (يُحفظ في الإعدادات). */
data class CardDesign(
    val template: String = "table",
    val imagePath: String? = null,
    val userColor: Int = Color.BLACK,
    val textColor: Int = Color.BLACK,
    val font: String = "cairo",
    val showNetwork: Boolean = true,
    val showTime: Boolean = true,
    val showValid: Boolean = false,
    val showUptime: Boolean = false,
    val showData: Boolean = false,
    val showPrice: Boolean = true,
    val withPassword: Boolean = false,
    val showNote1: Boolean = false,
    val showNote2: Boolean = false,
    val showSerial: Boolean = false,
    val showQr: Boolean = false,
    val cols: Int = 5,
    val rows: Int = 20,
    val pos: Map<El, ElemPos> = Templates.byId("table").positions,
    val colors: Map<El, Int> = emptyMap(),
    val fonts: Map<El, String> = emptyMap()
) {
    fun colorOf(e: El): Int = colors[e] ?: if (e == El.USER || e == El.PASS) userColor else textColor
    fun fontOf(e: El): String = fonts[e] ?: font

    /** تطبيق نموذج جاهز: الخلفية والمواضع والألوان والعناصر الظاهرة. */
    fun withTemplate(t: CardTemplate): CardDesign {
        var d = copy(template = t.id, userColor = t.userColor, textColor = t.textColor, pos = t.positions, colors = t.colors,
            cols = t.cols ?: cols, rows = t.rows ?: rows)
        t.shown?.let { sh ->
            d = d.copy(showNetwork = El.NETWORK in sh, showTime = El.TIME in sh, showValid = El.VALID in sh, showUptime = El.UPTIME in sh,
                showData = El.DATA in sh, showPrice = El.PRICE in sh, showNote1 = El.NOTE1 in sh)
        }
        return d
    }

    fun visible(e: El) = when (e) {
        El.USER -> true
        El.PASS -> withPassword
        El.NETWORK -> showNetwork
        El.TIME -> showTime
        El.VALID -> showValid
        El.UPTIME -> false // مدة المستخدم لم تعد تُعرض على الكرت
        El.DATA -> showData
        El.PRICE -> showPrice
        El.NOTE1 -> showNote1
        El.NOTE2 -> showNote2
        El.SERIAL -> showSerial
        El.QR -> showQr
    }

    fun posOf(e: El): ElemPos = pos[e] ?: Templates.EXTRA.getValue(e)

    fun toJson(): String = JSONObject().apply {
        put("template", template); put("image", imagePath ?: ""); put("userColor", userColor); put("textColor", textColor)
        put("font", font); put("showNetwork", showNetwork); put("showTime", showTime); put("showPrice", showPrice)
        put("withPassword", withPassword); put("showNote1", showNote1); put("showNote2", showNote2)
        put("showSerial", showSerial); put("showQr", showQr); put("cols", cols); put("rows", rows)
        put("showValid", showValid); put("showUptime", showUptime); put("showData", showData)
        put("colors", JSONObject().apply { colors.forEach { (k, v) -> put(k.name, v) } })
        put("fonts", JSONObject().apply { fonts.forEach { (k, v) -> put(k.name, v) } })
        put("pos", JSONObject().apply { pos.forEach { (k, p) -> put(k.name, "${p.x},${p.y},${p.size}") } })
    }.toString()

    companion object {
        /** التصميم الافتراضي لأول مرة: نموذج WiFi بنفسجي. */
        fun default(): CardDesign = CardDesign().withTemplate(Templates.byId("wifi_purple"))

        fun fromJson(s: String?): CardDesign {
            if (s.isNullOrBlank()) return default()
            return try {
                val o = JSONObject(s)
                val def = CardDesign()
                val po = o.optJSONObject("pos")
                val pos = El.entries.associateWith { e ->
                    po?.optString(e.name)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }
                        ?.let { ElemPos(it[0], it[1], it[2]) } ?: def.posOf(e)
                }
                CardDesign(
                    template = o.optString("template", "table"),
                    imagePath = o.optString("image").ifBlank { null },
                    userColor = o.optInt("userColor", Color.BLACK),
                    textColor = o.optInt("textColor", Color.BLACK),
                    font = o.optString("font", "cairo"),
                    showNetwork = o.optBoolean("showNetwork", true),
                    showTime = o.optBoolean("showTime", true),
                    showPrice = o.optBoolean("showPrice", true),
                    showValid = o.optBoolean("showValid", false),
                    showUptime = o.optBoolean("showUptime", false),
                    showData = o.optBoolean("showData", false),
                    colors = o.optJSONObject("colors")?.let { co -> El.entries.filter { co.has(it.name) }.associateWith { co.getInt(it.name) } } ?: emptyMap(),
                    fonts = o.optJSONObject("fonts")?.let { fo -> El.entries.filter { fo.has(it.name) }.associateWith { fo.getString(it.name) } } ?: emptyMap(),
                    withPassword = o.optBoolean("withPassword", false),
                    showNote1 = o.optBoolean("showNote1", false),
                    showNote2 = o.optBoolean("showNote2", false),
                    showSerial = o.optBoolean("showSerial", false),
                    showQr = o.optBoolean("showQr", false),
                    cols = o.optInt("cols", 5).coerceIn(1, 10),
                    rows = o.optInt("rows", 20).coerceIn(1, 30),
                    pos = pos
                )
            } catch (_: Exception) { default() }
        }
    }
}

data class CardTexts(
    val user: String, val pass: String, val network: String, val time: String, val price: String,
    val note1: String = "", val note2: String = "", val serial: String = "",
    val valid: String = "", val uptime: String = "", val data: String = ""
) {
    fun of(e: El) = when (e) {
        El.USER -> user; El.PASS -> pass; El.NETWORK -> network; El.TIME -> time; El.PRICE -> price
        El.VALID -> valid; El.UPTIME -> uptime; El.DATA -> data
        El.NOTE1 -> note1; El.NOTE2 -> note2; El.SERIAL -> serial; El.QR -> user
    }
}

object Templates {
    /** مواضع العناصر الإضافية (تشترك فيها كل النماذج). */
    val EXTRA = mapOf(
        El.NOTE1 to ElemPos(0.27f, 0.72f, 0.09f),
        El.NOTE2 to ElemPos(0.73f, 0.72f, 0.09f),
        El.SERIAL to ElemPos(0.07f, 0.12f, 0.10f),
        El.QR to ElemPos(0.12f, 0.50f, 0.55f),
        El.VALID to ElemPos(0.25f, 0.62f, 0.10f),
        El.UPTIME to ElemPos(0.75f, 0.62f, 0.10f),
        El.DATA to ElemPos(0.5f, 0.93f, 0.09f),
    )
    /** مواضع نماذج WiFi (خلفية 4:1): رمز الدخول في المربع الأبيض والجدول أسفله. */
    private val wifiPos = mapOf(
        El.NETWORK to ElemPos(0.48f, 0.21f, 0.13f),
        El.USER to ElemPos(0.482f, 0.535f, 0.19f),
        El.PASS to ElemPos(0.137f, 0.58f, 0.13f),
        El.QR to ElemPos(0.137f, 0.58f, 0.60f),
        El.VALID to ElemPos(0.643f, 0.878f, 0.085f),
        El.TIME to ElemPos(0.50f, 0.878f, 0.085f),
        El.UPTIME to ElemPos(0.50f, 0.878f, 0.085f),
        El.DATA to ElemPos(0.362f, 0.878f, 0.085f),
        El.PRICE to ElemPos(0.362f, 0.878f, 0.085f),
        El.NOTE1 to ElemPos(0.742f, 0.878f, 0.075f),
        El.NOTE2 to ElemPos(0.30f, 0.07f, 0.08f),
        El.SERIAL to ElemPos(0.05f, 0.08f, 0.08f),
    )
    private fun wifiColors(accent: Int) = mapOf(
        El.NETWORK to accent, El.USER to Color.BLACK, El.PASS to Color.WHITE, El.VALID to Color.BLACK, El.TIME to Color.BLACK,
        El.UPTIME to Color.BLACK, El.DATA to Color.BLACK, El.PRICE to Color.BLACK, El.NOTE1 to Color.BLACK, El.NOTE2 to accent, El.SERIAL to accent)
    private val wifiShown = setOf(El.NETWORK, El.VALID, El.TIME, El.DATA, El.NOTE1)
    private val tablePos = mapOf(
        El.NETWORK to ElemPos(0.5f, 0.15f, 0.16f),
        El.USER to ElemPos(0.5f, 0.47f, 0.26f),
        El.PASS to ElemPos(0.5f, 0.62f, 0.11f),
        El.TIME to ElemPos(0.75f, 0.85f, 0.14f),
        El.PRICE to ElemPos(0.25f, 0.85f, 0.14f),
    ) + EXTRA
    private val centerPos = mapOf(
        El.NETWORK to ElemPos(0.5f, 0.16f, 0.16f),
        El.USER to ElemPos(0.5f, 0.45f, 0.28f),
        El.PASS to ElemPos(0.5f, 0.65f, 0.12f),
        El.TIME to ElemPos(0.78f, 0.85f, 0.13f),
        El.PRICE to ElemPos(0.22f, 0.85f, 0.13f),
    ) + EXTRA
    val all = listOf(
        CardTemplate("wifi_red", "WiFi أحمر", Color.BLACK, Color.BLACK, wifiPos, R.drawable.card_wifi_red,
            wifiColors(Color.rgb(139, 10, 10)), 4, 22, wifiShown),
        CardTemplate("wifi_purple", "WiFi بنفسجي", Color.BLACK, Color.BLACK, wifiPos, R.drawable.card_wifi_purple,
            wifiColors(Color.rgb(107, 15, 133)), 4, 22, wifiShown),
        CardTemplate("wifi_teal", "WiFi فيروزي", Color.BLACK, Color.BLACK, wifiPos, R.drawable.card_wifi_teal,
            wifiColors(Color.rgb(30, 42, 120)), 4, 22, wifiShown),
        CardTemplate("table", "جدول كلاسيكي", Color.BLACK, Color.BLACK, tablePos),
        CardTemplate("simple", "بسيط", Color.BLACK, Color.rgb(20, 110, 95), centerPos),
        CardTemplate("gradient", "أزرق متدرج", Color.WHITE, Color.WHITE, centerPos),
        CardTemplate("gold", "ذهبي داكن", Color.WHITE, Color.rgb(230, 185, 80), centerPos),
        CardTemplate("green", "أخضر", Color.WHITE, Color.WHITE, centerPos),
    )
    val imageTemplate = CardTemplate("image", "صورة من الهاتف", Color.WHITE, Color.WHITE, centerPos)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: if (id == "image") imageTemplate else all.first()
}

/** رسم الكرت على Canvas — نفس الدالة للمعاينة داخل التطبيق ولملف PDF (ما تراه هو ما يُطبع). */
object CardRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bounds = android.graphics.Rect()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val qrPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }

    fun draw(
        c: Canvas, r: RectF, d: CardDesign, t: CardTexts, bg: Bitmap?, typeface: Typeface,
        boxes: MutableMap<El, RectF>? = null, fonts: Map<String, Typeface> = emptyMap(),
        /** في ملف PDF: ظل بسيط مرسوم كنص (الظل المموّه يُحوَّل لصورة لكل نص فيبطئ الطباعة ويضخّم الملف). */
        vectorShadow: Boolean = false
    ) {
        val h = r.height(); val w = r.width()
        val sw = (h * 0.022f).coerceAtLeast(0.6f)
        val radius = h * 0.08f
        drawBackground(c, r, d.template, bg, sw, radius)

        text.typeface = typeface
        val shadow = d.template == "image"
        for (e in El.entries) {
            if (!d.visible(e)) continue
            val s = t.of(e); if (s.isBlank()) continue
            val p = d.posOf(e)
            val cx = r.left + p.x * w
            val cy = r.top + p.y * h
            if (e == El.QR) {
                val side = p.size * h
                val box = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
                drawQr(c, box, s)
                boxes?.put(e, box)
                continue
            }
            text.color = d.colorOf(e)
            text.typeface = fonts[d.fontOf(e)] ?: typeface
            var size = p.size * h
            text.textSize = size
            val maxW = w * 0.96f
            while (text.measureText(s) > maxW && size > 4f) { size *= 0.92f; text.textSize = size }
            // الظل على الصورة لكل العناصر ما عدا اسم المستخدم
            val withShadow = shadow && e != El.USER
            if (withShadow && !vectorShadow) text.setShadowLayer(size * 0.12f, 0f, size * 0.04f, Color.argb(170, 0, 0, 0)) else text.clearShadowLayer()
            // التوسيط حسب حدود الحروف الفعلية (خط Cairo له مسافات علوية كبيرة)
            text.getTextBounds(s, 0, s.length, bounds)
            val baseline = cy - (bounds.top + bounds.bottom) / 2f
            if (withShadow && vectorShadow) {
                val col = text.color
                text.color = Color.argb(150, 0, 0, 0)
                c.drawText(s, cx + size * 0.03f, baseline + size * 0.05f, text)
                text.color = col
            }
            c.drawText(s, cx, baseline, text)
            if (boxes != null) {
                val tw = text.measureText(s)
                val th = bounds.height().toFloat()
                boxes[e] = RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
            }
        }
        text.clearShadowLayer()
    }

    /** رمز QR مرسوم كمربعات (واضح في الطباعة وخفيف على ملف PDF). */
    private fun drawQr(c: Canvas, box: RectF, content: String) {
        val m = try { Encoder.encode(content, ErrorCorrectionLevel.L).matrix } catch (_: Exception) { return }
        val n = m.width
        val quiet = 1
        val cell = box.width() / (n + quiet * 2)
        fill.shader = null; fill.color = Color.WHITE
        c.drawRect(box, fill)
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (m.get(x, y).toInt() == 1) {
                    var run = x
                    while (run + 1 < n && m.get(run + 1, y).toInt() == 1) run++
                    val l = box.left + (x + quiet) * cell
                    val t = box.top + (y + quiet) * cell
                    c.drawRect(l, t, box.left + (run + 1 + quiet) * cell, t + cell, qrPaint)
                    x = run + 1
                } else x++
            }
        }
    }

    private fun drawBackground(c: Canvas, r: RectF, id: String, bg: Bitmap?, sw: Float, radius: Float) {
        stroke.strokeWidth = sw
        fill.shader = null
        when (if (Templates.byId(id).res != null) "image" else id) {
            "image" -> {
                if (bg != null) {
                    c.save(); c.clipRect(r)
                    c.drawBitmap(bg, null, r, bmpPaint)
                    c.restore()
                } else { fill.color = Color.rgb(40, 60, 90); c.drawRect(r, fill) }
                stroke.color = Color.argb(120, 0, 0, 0); c.drawRect(r, stroke)
            }
            "simple" -> {
                fill.color = Color.WHITE; c.drawRoundRect(r, radius, radius, fill)
                stroke.color = Color.rgb(29, 201, 161); stroke.strokeWidth = sw * 1.6f
                val i = RectF(r); i.inset(sw, sw); c.drawRoundRect(i, radius, radius, stroke)
            }
            "gradient" -> {
                fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.rgb(27, 111, 201), Color.rgb(29, 201, 161), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, radius, radius, fill); fill.shader = null
                stroke.color = Color.argb(90, 255, 255, 255)
                c.drawLine(r.left + r.width() * 0.08f, r.top + r.height() * 0.3f, r.right - r.width() * 0.08f, r.top + r.height() * 0.3f, stroke)
            }
            "gold" -> {
                fill.color = Color.rgb(18, 30, 52); c.drawRoundRect(r, radius, radius, fill)
                stroke.color = Color.rgb(230, 185, 80); stroke.strokeWidth = sw * 1.4f
                val i = RectF(r); i.inset(sw * 1.5f, sw * 1.5f); c.drawRoundRect(i, radius, radius, stroke)
            }
            "green" -> {
                fill.shader = LinearGradient(r.left, r.top, r.left, r.bottom, Color.rgb(28, 150, 90), Color.rgb(14, 90, 60), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, radius, radius, fill); fill.shader = null
            }
            else -> { // table
                fill.color = Color.WHITE; c.drawRect(r, fill)
                stroke.color = Color.BLACK
                val i = RectF(r); i.inset(sw / 2, sw / 2)
                c.drawRect(i, stroke)
                val y1 = r.top + r.height() * 0.30f; val y2 = r.top + r.height() * 0.70f
                c.drawLine(i.left, y1, i.right, y1, stroke)
                c.drawLine(i.left, y2, i.right, y2, stroke)
                c.drawLine(r.centerX(), y2, r.centerX(), i.bottom, stroke)
            }
        }
    }
}
