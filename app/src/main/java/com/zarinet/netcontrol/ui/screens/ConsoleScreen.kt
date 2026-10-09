package com.zarinet.netcontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.AppButton
import com.zarinet.netcontrol.ui.components.BarAction
import com.zarinet.netcontrol.ui.components.Field
import com.zarinet.netcontrol.ui.components.TopBar
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D
import com.zarinet.netcontrol.util.TerminalCommand
import kotlinx.coroutines.launch

/**
 * API Console بصيغة الطرفية: الأمر مثل "/ip address print where disabled=no"
 * (يُحوَّل إلى كلمات API)، مع معاملات إضافية خام (=key=value أو ?key=value) سطراً لكل كلمة.
 * عبارات [find ...] تُحلّ إلى ‎.id قبل التنفيذ.
 */
@Composable
fun ConsoleScreen(
    initialPath: String,
    onBack: () -> Unit,
    initialCommand: String? = null,
    initialOutput: String = ""
) {
    val scope = rememberCoroutineScope()
    var command by remember {
        mutableStateOf(
            initialCommand
                ?: initialPath.trim('/').takeIf { it.isNotBlank() }?.let { "/" + it.replace('/', ' ') + " print" }
                ?: "/system resource print"
        )
    }
    var args by remember { mutableStateOf("") }
    var output by remember { mutableStateOf(initialOutput) }
    var running by remember { mutableStateOf(false) }

    fun run() {
        running = true
        scope.launch {
            output = try {
                val plan = TerminalCommand.parse(command, args.lines())
                val api = Session.api()
                // حل [find ...] إلى أرقام ‎.id
                val resolved = plan.finds.map { (key, q) ->
                    val ids = api.print(plan.path, listOf(".id"), q).mapNotNull { it[".id"] }
                    if (ids.isEmpty()) error("لا يوجد عنصر مطابق لـ [find ${q.joinToString(" ")}]")
                    "=$key=${ids.joinToString(",")}"
                }
                val words = plan.words.take(1) + resolved + plan.words.drop(1)
                val shown = words.joinToString(" ")
                "» $shown\n" + api.raw(words).joinToString("\n") { s ->
                    s.joinToString("\n") { if (it.startsWith("!")) it else "   $it" }
                }
            } catch (e: Exception) {
                "خطأ: ${e.message}"
            }
            running = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("API Console", "تنفيذ أوامر RouterOS API مباشرة", onBack) {
            if (output.isNotEmpty()) BarAction(Icons.Filled.DeleteOutline, "مسح", C.Dim) { output = "" }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = D.screen).padding(bottom = D.screen),
            verticalArrangement = Arrangement.spacedBy(D.sm)
        ) {
            Field(command, { command = it }, "الأمر", placeholder = "/ip address print where disabled=no", ltr = true)
            Field(
                args,
                { args = it },
                "معاملات إضافية (اختياري، سطر لكل واحدة)",
                singleLine = false,
                placeholder = "=.proplist=name,address",
                ltr = true
            )
            AppButton(
                if (running) "جاري التنفيذ..." else "تنفيذ",
                ::run,
                Modifier.fillMaxWidth(),
                icon = Icons.Filled.PlayArrow,
                loading = running
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(Color(0xFF050C16), RoundedCornerShape(D.radiusControl))
                        .border(1.dp, C.Border, RoundedCornerShape(D.radiusControl))
                        .padding(D.md)
                ) {
                    Text(
                        output.ifEmpty { "// النتيجة تظهر هنا" },
                        Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        color = Color(0xFF7CF0C4),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
