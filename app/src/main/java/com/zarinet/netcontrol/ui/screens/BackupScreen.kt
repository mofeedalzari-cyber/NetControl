package com.zarinet.netcontrol.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.api.FtpClient
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RFile(val id: String, val name: String, val size: Long, val date: String) {
    val isBackup get() = name.endsWith(".backup")
    val isScript get() = name.endsWith(".rsc")
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<RFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<RFile?>(null) }
    var deleting by remember { mutableStateOf<RFile?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                files = Session.api().print("/file", listOf(".id", "name", "size", "creation-time"))
                    .map { RFile(it[".id"].orEmpty(), it["name"].orEmpty(), it["size"]?.filter(Char::isDigit)?.toLongOrNull() ?: 0, it["creation-time"].orEmpty()) }
                    .filter { it.isBackup || it.isScript }
                    .sortedByDescending { it.date }
            } catch (e: Exception) { error = e.message ?: "خطأ" }
            loading = false
        }
    }
    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            try { block() } catch (e: Exception) { com.zarinet.netcontrol.ui.components.toast(ctx, e.message ?: "خطأ", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }
    LaunchedEffect(Unit) { load() }

    val stamp = { "MikroShamel-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date()) }

    Column(Modifier.fillMaxSize()) {
        TopBar("نسخ / استعادة", "النسخ الاحتياطي وتصدير الإعدادات", onBack) {
            BarAction(Icons.Filled.Refresh, "تحديث", onClick = ::load)
        }
        Column(Modifier.padding(horizontal = D.screen), verticalArrangement = Arrangement.spacedBy(D.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                AppButton("نسخة احتياطية", {
                    run("جاري إنشاء النسخة...") {
                        val api = Session.api()
                        val name = stamp()
                        // v7 يدعم dont-encrypt؛ v6 لا يعرفه فنعيد بدونه
                        try { api.exec("/system/backup/save", mapOf("name" to name, "dont-encrypt" to "yes")) }
                        catch (_: Exception) { api.exec("/system/backup/save", mapOf("name" to name)) }
                        delay(1500); load()
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم حفظ $name.backup على الراوتر", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.weight(1f).height(52.dp), icon = Icons.Filled.Backup, enabled = busy == null)
                AppButton("تصدير .rsc", {
                    run("جاري التصدير...") {
                        Session.api().exec("/export", mapOf("file" to stamp()))
                        delay(2000); load()
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم تصدير الإعدادات", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.weight(1f).height(52.dp), BtnKind.Secondary, Icons.Filled.Code, enabled = busy == null)
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = C.Teal, strokeWidth = 2.dp)
                    Spacer(Modifier.width(D.sm)); Text(it, style = MaterialTheme.typography.bodyMedium, color = C.Dim)
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.screen, end = D.screen, top = D.md, bottom = D.xl),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            item { SectionTitle("الملفات على الراوتر") { CountBadge(files.size, C.Cyan) } }
            item { StateView(loading && files.isEmpty(), error, !loading && error == null && files.isEmpty(), "لا توجد نسخ احتياطية", ::load) }
            items(files, key = { it.id }) { f ->
                AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(D.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(if (f.isBackup) Icons.Filled.Backup else Icons.Filled.Description, if (f.isBackup) C.Teal else C.Steel, 36.dp, 20.dp)
                        Spacer(Modifier.width(D.sm))
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.ltr(f.name), style = MaterialTheme.typography.titleSmall, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${Fmt.bytes(f.size)} • ${f.date}", style = MaterialTheme.typography.bodySmall, color = C.Dim)
                        }
                        Pill(if (f.isBackup) "نسخة" else "سكربت", if (f.isBackup) C.Teal else C.Steel)
                    }
                    Spacer(Modifier.height(D.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(D.sm)) {
                        SmallAction("استعادة", Icons.Filled.SettingsBackupRestore, C.Gold) { restoring = f }
                        SmallAction("تنزيل للهاتف", Icons.Filled.Download, C.Steel) {
                            run("جاري التنزيل عبر FTP...") {
                                val d = Session.requireDevice()
                                val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
                                val out = File(dir, f.name.substringAfterLast('/'))
                                FtpClient.download(d.host, d.user, d.password, f.name, out)
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "حفظ / مشاركة النسخة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        SmallAction("حذف", Icons.Filled.DeleteOutline, C.Red) { deleting = f }
                    }
                }
            }
            item {
                Text("• التنزيل للهاتف يحتاج تفعيل خدمة FTP في الراوتر (/ip service enable ftp).\n• استعادة ملف .backup تعيد تشغيل الراوتر.",
                    style = MaterialTheme.typography.bodySmall, color = C.Faint, modifier = Modifier.padding(top = D.sm))
            }
        }
    }

    restoring?.let { f ->
        ConfirmDialog("استعادة ${f.name}",
            if (f.isBackup) "سيتم استبدال إعدادات الراوتر الحالية بهذه النسخة وإعادة تشغيله. سينقطع الاتصال."
            else "سيتم تنفيذ أوامر السكربت على الراوتر (/import). قد يغيّر أو يكرر إعدادات موجودة.",
            "استعادة",
            onConfirm = {
                run("جاري الاستعادة...") {
                    if (f.isBackup) {
                        runCatching { Session.api().exec("/system/backup/load", mapOf("name" to f.name, "password" to "")) }
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم إرسال أمر الاستعادة — الراوتر يعيد التشغيل", Toast.LENGTH_LONG).show()
                        Session.disconnect()
                    } else {
                        Session.api().exec("/import", mapOf("file-name" to f.name))
                        com.zarinet.netcontrol.ui.components.toast(ctx, "تم تنفيذ السكربت", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { restoring = null })
    }
    deleting?.let { f ->
        ConfirmDialog("حذف ملف", "حذف ${f.name} من الراوتر؟", "حذف",
            onConfirm = { run("جاري الحذف...") { Session.api().exec("/file/remove", mapOf(".id" to f.id)); load() } },
            onDismiss = { deleting = null })
    }
}
