package com.zarinet.netcontrol.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** تقرير المبيعات كملف PDF (A4، من اليمين لليسار، ينقسم على عدة صفحات تلقائياً). */
object SalesPdf {
    const val W = 595f
    const val H = 842f
    private const val M = 32f

    private val teal = Color.rgb(16, 150, 120)
    private val dark = Color.rgb(20, 32, 52)
    private val gray = Color.rgb(110, 120, 135)
    private val line = Color.rgb(220, 226, 234)
    private val zebra = Color.rgb(245, 248, 251)

    fun build(ctx: Context, network: String, source: String, period: String, reps: List<PosRep>): File {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        render(VoucherPdf.font(ctx), network, source, period, reps,
            newPage = { n -> page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), n).create()); page!!.canvas },
            endPage = { doc.finishPage(page) })
        val stamp = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
        val file = File(File(ctx.cacheDir, "pdf").apply { mkdirs() }, "sales-report-$stamp.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** يرسم التقرير على صفحات A4 (يُستخدم لملف PDF وللمعاينة). */
    fun render(
        tf: android.graphics.Typeface, network: String, source: String, period: String, reps: List<PosRep>,
        newPage: (Int) -> Canvas, endPage: () -> Unit
    ) {
        measure.typeface = tf
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; color = dark }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = line }
        val dateFmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        val shortDate = SimpleDateFormat("yyyy/MM/dd", Locale.US)

        var pageNo = 0
        lateinit var c: Canvas
        var y = 0f

        fun text(s: String, x: Float, yy: Float, size: Float, color: Int = dark, align: Paint.Align = Paint.Align.RIGHT) {
            txt.textSize = size; txt.color = color; txt.textAlign = align
            c.drawText(s, x, yy, txt)
        }
        fun footer() {
            text("ميكروتك الشامل — صفحة $pageNo", W / 2, H - 16f, 8f, gray, Paint.Align.CENTER)
        }
        fun newPage() {
            if (pageNo > 0) { footer(); endPage() }
            pageNo++
            c = newPage(pageNo)
            fill.color = Color.WHITE; c.drawRect(0f, 0f, W, H, fill)
            y = M
        }
        fun ensure(h: Float) { if (y + h > H - 40f) newPage() }

        /** صف جدول: الأعمدة معرّفة بأوزان من اليمين لليسار. */
        fun row(cells: List<String>, weights: List<Float>, header: Boolean, shade: Boolean, bold: Set<Int> = emptySet()) {
            val rh = 20f
            ensure(rh)
            val total = weights.sum()
            val rect = RectF(M, y, W - M, y + rh)
            fill.color = if (header) teal else if (shade) zebra else Color.WHITE
            c.drawRect(rect, fill)
            c.drawLine(M, y + rh, W - M, y + rh, stroke)
            var right = W - M
            cells.forEachIndexed { i, s ->
                val cw = (W - 2 * M) * weights[i] / total
                val col = if (header) Color.WHITE else if (i in bold) dark else Color.rgb(45, 55, 72)
                txt.typeface = tf
                // أول عمود (الاسم) محاذى لليمين، والباقي في الوسط
                if (i == 0) text(fit(s, cw - 8f, 9.5f), right - 6f, y + 13.5f, 9.5f, col)
                else text(fit(s, cw - 4f, 9.5f), right - cw / 2, y + 13.5f, 9.5f, col, Paint.Align.CENTER)
                right -= cw
            }
            y += rh
        }

        newPage()
        // ── الترويسة ──
        fill.color = dark
        c.drawRoundRect(RectF(M, y, W - M, y + 64f), 8f, 8f, fill)
        text("تقرير المبيعات حسب نقطة البيع", W - M - 14f, y + 26f, 16f, Color.WHITE)
        text("$network • $source • الفترة: $period", W - M - 14f, y + 48f, 10f, Color.rgb(190, 205, 222))
        text(dateFmt.format(Date()), M + 14f, y + 26f, 9f, Color.rgb(190, 205, 222), Paint.Align.LEFT)
        y += 80f

        // ── الملخص ──
        val boxes = listOf(
            "إجمالي المبيعات" to Sales.money(reps.sumOf { it.revenue }),
            "كروت مباعة" to "${reps.sumOf { it.sold }}",
            "كروت متبقية" to "${reps.sumOf { it.remaining }}",
            "قيمة المتبقي" to Sales.money(reps.sumOf { it.stockValue }),
        )
        val bw = (W - 2 * M - 3 * 8f) / 4
        boxes.forEachIndexed { i, (label, value) ->
            val right = W - M - i * (bw + 8f)
            val r = RectF(right - bw, y, right, y + 52f)
            fill.color = zebra; c.drawRoundRect(r, 6f, 6f, fill)
            c.drawRoundRect(r, 6f, 6f, stroke)
            text(label, r.right - 8f, r.top + 17f, 8.5f, gray)
            text(value, r.right - 8f, r.top + 40f, 14f, if (i == 0) teal else dark)
        }
        y += 68f

        // ── جدول نقاط البيع ──
        text("ملخص نقاط البيع", W - M, y + 12f, 12f, dark); y += 22f
        val w1 = listOf(3.2f, 1f, 1f, 1f, 1f, 1.6f)
        row(listOf("نقطة البيع", "مُصدَر", "مباع", "متبقي", "نسبة البيع", "المبيعات"), w1, header = true, shade = false)
        reps.forEachIndexed { i, r ->
            val pct = if (r.issued > 0) r.sold * 100 / r.issued else 0
            row(listOf(r.pos, "${r.issued}", "${r.sold}", "${r.remaining}", "$pct%", Sales.money(r.revenue)), w1, false, i % 2 == 1, setOf(0, 5))
        }
        row(listOf("الإجمالي", "${reps.sumOf { it.issued }}", "${reps.sumOf { it.sold }}", "${reps.sumOf { it.remaining }}", "",
            Sales.money(reps.sumOf { it.revenue })), w1, header = true, shade = false)
        y += 18f

        // ── تفاصيل الدفعات لكل نقطة بيع ──
        val w2 = listOf(2.2f, 1.6f, 1.4f, 1f, 1.2f, 1.4f)
        reps.forEach { r ->
            ensure(64f)
            fill.color = teal; c.drawRect(RectF(W - M - 4f, y + 2f, W - M, y + 16f), fill)
            text("${r.pos} — ${r.batches.size} دفعة", W - M - 10f, y + 14f, 11f, dark); y += 22f
            row(listOf("الدفعة", "التاريخ", "الباقة", "السعر", "مباع/مصدر", "المبيعات"), w2, header = true, shade = false)
            r.batches.forEachIndexed { i, b ->
                row(listOf(b.tag, b.date?.let { shortDate.format(Date(it)) } ?: "—", b.profile.ifBlank { "—" },
                    if (b.price > 0) Sales.money(b.price) else "—", "${b.sold}/${b.issued}", Sales.money(b.revenue)), w2, false, i % 2 == 1, setOf(5))
            }
            y += 14f
        }
        ensure(30f)
        text("المباع = الكروت المستخدمة + الكروت التي انتهت وحُذفت من الراوتر. الفترة حسب تاريخ طباعة الدفعة.", W - M, y + 10f, 8f, gray)

        footer(); endPage()
    }

    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fit(s: String, maxW: Float, size: Float): String {
        measure.textSize = size
        if (measure.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && measure.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
