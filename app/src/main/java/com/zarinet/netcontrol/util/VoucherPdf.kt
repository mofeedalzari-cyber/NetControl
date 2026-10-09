package com.zarinet.netcontrol.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.zarinet.netcontrol.R
import java.io.File

data class Voucher(val user: String, val pass: String)

/** إعدادات الدفعة المطبوعة على الكروت والمرسلة للراوتر. */
enum class CardTarget { HOTSPOT, USERMAN }

data class BatchInfo(
    val target: CardTarget,
    val name: String,
    val profile: String,
    val network: String,
    val timeText: String,
    val price: String,
    val limitUptime: String,
    val limitBytes: Long,
    val comment: String,
    val note1: String = "",
    val note2: String = "",
    val serialStart: Long = 1,
    val bind: Boolean = false,
    val umV6: Boolean = false,
    val customer: String = "admin",
    val validText: String = "",
    val uptimeText: String = "",
    val dataText: String = "",
    val email: String = ""
)

object VoucherPdf {
    const val PAGE_W = 595 // A4 بالنقاط
    const val PAGE_H = 842
    const val MARGIN = 18f
    const val DOWNLOAD_FOLDER = "ميكروتك الشامل"

    fun cellAspect(cols: Int, rows: Int): Float =
        ((PAGE_W - MARGIN * 2) / cols) / ((PAGE_H - MARGIN * 2) / rows)

    fun font(ctx: Context, name: String = "cairo"): Typeface = when (name) {
        "sans" -> Typeface.DEFAULT_BOLD
        "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        "serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "cairo_semi" -> try { ResourcesCompat.getFont(ctx, R.font.cairo_semibold) ?: Typeface.DEFAULT_BOLD } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        "cairo_reg" -> try { ResourcesCompat.getFont(ctx, R.font.cairo_regular) ?: Typeface.DEFAULT } catch (_: Exception) { Typeface.DEFAULT }
        else -> try { ResourcesCompat.getFont(ctx, R.font.cairo_bold) ?: Typeface.DEFAULT_BOLD } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    }

    /** كل الخطوط (لاختيار خط مختلف لكل عنصر). */
    fun fonts(ctx: Context): Map<String, Typeface> = CARD_FONTS.keys.associateWith { font(ctx, it) }

    fun loadBg(ctx: Context, d: CardDesign, maxW: Int = 900): Bitmap? {
        Templates.byId(d.template).res?.let { res ->
            return try {
                val b = BitmapFactory.decodeResource(ctx.resources, res) ?: return null
                if (b.width > maxW) Bitmap.createScaledBitmap(b, maxW, (b.height * maxW.toFloat() / b.width).toInt(), true) else b
            } catch (_: Exception) { null }
        }
        if (d.template != "image") return null
        val path = d.imagePath ?: return null
        return try {
            val b = BitmapFactory.decodeFile(path) ?: return null
            if (b.width > maxW) Bitmap.createScaledBitmap(b, maxW, (b.height * maxW.toFloat() / b.width).toInt(), true) else b
        } catch (_: Exception) { null }
    }

    /** [onPage] يُستدعى قبل رسم كل صفحة (رقم الصفحة، عدد الصفحات) لعرض التقدم. */
    fun build(
        ctx: Context, vouchers: List<Voucher>, design: CardDesign, info: BatchInfo, fileName: String,
        onPage: (Int, Int) -> Unit = { _, _ -> }
    ): File {
        val doc = PdfDocument()
        val cols = design.cols; val rows = design.rows
        val perPage = cols * rows
        val cellW = (PAGE_W - MARGIN * 2) / cols
        val cellH = (PAGE_H - MARGIN * 2) / rows
        val gap = 1.2f
        val tf = font(ctx, design.font)
        // صورة الخلفية تُصغَّر مرة واحدة ويُعاد استخدامها لكل الكروت (حجم PDF صغير)
        val bg = loadBg(ctx, design, (cellW * 4).toInt().coerceAtLeast(200))
        val fonts = fonts(ctx)

        var serial = info.serialStart
        val pages = vouchers.chunked(perPage)
        pages.forEachIndexed { pageIdx, chunk ->
            onPage(pageIdx + 1, pages.size)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIdx + 1).create())
            val c = page.canvas
            chunk.forEachIndexed { i, v ->
                val col = cols - 1 - (i % cols) // من اليمين لليسار
                val row = i / cols
                val x = MARGIN + col * cellW
                val y = MARGIN + row * cellH
                val r = RectF(x + gap, y + gap, x + cellW - gap, y + cellH - gap)
                CardRenderer.draw(c, r, design,
                    CardTexts(v.user, v.pass, info.network, info.timeText, info.price, info.note1, info.note2, (serial++).toString(),
                        info.validText, info.uptimeText, info.dataText), bg, tf, null, fonts, vectorShadow = true)
            }
            doc.finishPage(page)
        }
        val file = File(outDir(ctx), "$fileName.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** سكربت .rsc لإضافة الكروت يدوياً عبر Winbox / Terminal. */
    fun buildScript(ctx: Context, vouchers: List<Voucher>, info: BatchInfo, installScriptFor: String?, fileName: String): File {
        val sb = StringBuilder()
        sb.append("# MikroAdmin — ").append(info.name).append(" — ").append(vouchers.size).append(" cards\n")
        if (installScriptFor != null) {
            sb.append("# نظام الصلاحية وربط الجهاز (بدون تنبيهات خارجية)\n:local s \"")
            sb.append(rsc(ZariValidity.ON_LOGIN))
                .append("\"\n:foreach p in=[/ip hotspot user profile find] do={ :local o [/ip hotspot user profile get \$p on-login]; " +
                    ":if ([:typeof [:find \$o \"bind \\\"bind\\\"\"]] != \"num\") do={ /ip hotspot user profile set \$p on-login=(\$o . \"\\r\\n\" . \$s) } }\n" +
                    ":if ([:len [/system scheduler find where name=\"Zari Net - hotspot card expiry\"]] = 0) do={ /system scheduler add name=\"Zari Net - hotspot card expiry\" interval=00:10:00 on-event=\"")
            sb.append(rsc(ZariValidity.SCHEDULER_SOURCE)).append("\" }\n")
        }
        if (info.target == CardTarget.USERMAN && info.umV6) {
            sb.append("# User Manager v6 — customer: ").append(info.customer).append("\n")
            vouchers.forEach { v ->
                sb.append("/tool user-manager user add customer=\"").append(rsc(info.customer)).append("\" username=\"").append(rsc(v.user))
                    .append("\" password=\"").append(rsc(v.pass)).append("\" comment=\"").append(rsc(info.comment)).append("\"")
                if (info.bind) sb.append(" caller-id-bind-on-first-use=yes")
                sb.append("\n/tool user-manager user create-and-activate-profile [find username=\"").append(rsc(v.user))
                    .append("\"] customer=\"").append(rsc(info.customer)).append("\" profile=\"").append(rsc(info.profile)).append("\"\n")
            }
        } else if (info.target == CardTarget.USERMAN) {
            vouchers.forEach { v ->
                sb.append("/user-manager user add name=\"").append(rsc(v.user)).append("\" password=\"").append(rsc(v.pass))
                    .append("\" comment=\"").append(rsc(info.comment)).append("\"")
                if (info.bind) sb.append(" caller-id-bind-on-first-use=yes")
                sb.append("\n/user-manager user-profile add user=\"").append(rsc(v.user)).append("\" profile=\"").append(rsc(info.profile)).append("\"\n")
            }
        } else {
            sb.append("/ip hotspot user\n")
            vouchers.forEach { v ->
                sb.append("add name=\"").append(rsc(v.user)).append("\" password=\"").append(rsc(v.pass))
                    .append("\" profile=\"").append(rsc(info.profile)).append("\"")
                if (info.limitUptime.isNotBlank()) sb.append(" limit-uptime=").append(info.limitUptime)
                if (info.limitBytes > 0) sb.append(" limit-bytes-total=").append(info.limitBytes)
                if (info.email.isNotBlank()) sb.append(" email=\"").append(rsc(info.email)).append("\"")
                sb.append(" comment=\"").append(rsc(info.comment)).append("\"\n")
            }
        }
        val file = File(outDir(ctx), "$fileName.rsc")
        file.writeText(sb.toString())
        return file
    }

    private fun rsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        .replace("\r", "\\r").replace("\n", "\\n")

    /** ملفات الطباعة تُحفظ داخل ملفات التطبيق (لا تُحذف مع الذاكرة المؤقتة). */
    private fun outDir(ctx: Context) = File(ctx.filesDir, "prints").apply { mkdirs() }

    /** ملفات PDF السابقة (الجديد أولاً) — تشمل المجلد القديم في الذاكرة المؤقتة. */
    fun previousFiles(ctx: Context): List<File> {
        val now = outDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".pdf") }?.toList() ?: emptyList()
        val old = File(ctx.cacheDir, "pdf").listFiles { f -> f.isFile && f.name.endsWith(".pdf") }?.toList() ?: emptyList()
        return (now + old).sortedByDescending { it.lastModified() }
    }

    /** نسخ الملف إلى مجلد التنزيلات/ميكروتك الشامل. يعيد المسار الظاهر للمستخدم أو null عند الفشل. */
    fun saveToDownloads(ctx: Context, file: File): String? = try {
        val mime = if (file.name.endsWith(".pdf")) "application/pdf" else "text/plain"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rel = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER
            // حذف نسخة سابقة بنفس الاسم
            runCatching {
                resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(file.name, "$rel%"))
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("insert")
            (resolver.openOutputStream(uri) ?: error("open")).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/$DOWNLOAD_FOLDER/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER)
            dir.mkdirs()
            file.copyTo(File(dir, file.name), overwrite = true)
            "Download/$DOWNLOAD_FOLDER/${file.name}"
        }
    } catch (_: Exception) { null }

    private fun uri(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun share(ctx: Context, file: File) {
        val mime = if (file.name.endsWith(".pdf")) "application/pdf" else "text/plain"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri(ctx, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "مشاركة / طباعة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(ctx: Context, file: File) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(ctx, file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ctx.startActivity(view) } catch (_: Exception) { share(ctx, file) }
    }
}

/**
 * سكربت on-login يُضاف لبروفايل الهوتسبوت:
 * - valid=1d في ملاحظة الكرت ← يبدأ العد عند أول دخول ثم يُحذف الكرت تلقائياً.
 * - bind=1 ← يرتبط الكرت بعنوان MAC لأول جهاز يدخل به.
 */
object OnLoginScript {
    const val MARKER = "#MikroAdmin"
    val CODE = """
#MikroAdmin
:do {
:local u ${'$'}user
:local m ${'$'}"mac-address"
:local id [/ip hotspot user find where name=${'$'}u]
:local c [/ip hotspot user get ${'$'}id comment]
:if ([:typeof [:find ${'$'}c "bind=1"]] = "num") do={
:local cm [/ip hotspot user get ${'$'}id mac-address]
:if ([:len ${'$'}cm] = 0 || ${'$'}cm = "00:00:00:00:00:00") do={ /ip hotspot user set ${'$'}id mac-address=${'$'}m }
}
:local p [:find ${'$'}c "valid="]
:if ([:typeof ${'$'}p] = "num") do={
:local e [:find ${'$'}c ";" ${'$'}p]
:if ([:typeof ${'$'}e] != "num") do={ :set e [:len ${'$'}c] }
:local v [:pick ${'$'}c (${'$'}p + 6) ${'$'}e]
:if ([:len [/system scheduler find where name=("exp-" . ${'$'}u)]] = 0) do={
/system scheduler add name=("exp-" . ${'$'}u) interval=${'$'}v on-event=("/ip hotspot active remove [find where user=\"" . ${'$'}u . "\"]\r\n/ip hotspot user remove [find where name=\"" . ${'$'}u . "\"]\r\n/system scheduler remove [find where name=\"exp-" . ${'$'}u . "\"]")
}
}
} on-error={}
#/MikroAdmin
""".trim()
}
