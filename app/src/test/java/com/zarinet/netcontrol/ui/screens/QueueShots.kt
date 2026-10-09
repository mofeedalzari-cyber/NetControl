package com.zarinet.netcontrol.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.zarinet.netcontrol.api.ApiResult
import com.zarinet.netcontrol.api.RouterOsClient
import com.zarinet.netcontrol.api.RouterOsException
import com.zarinet.netcontrol.data.Device
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.AppBackground
import com.zarinet.netcontrol.ui.theme.NetControlTheme
import com.zarinet.netcontrol.util.FairSpeed
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** راوتر وهمي بسيط في الذاكرة (لا اتصال حقيقي). */
class FakeRos : RouterOsClient("demo", 0, "demo", "") {
    private var n = 100
    val db = HashMap<String, MutableList<MutableMap<String, String>>>().apply {
        put("/system/resource", mutableListOf(mutableMapOf("version" to "7.16.2 (stable)")))
        put("/queue/simple", mutableListOf())
        put("/queue/type", mutableListOf(mutableMapOf(".id" to "*1", "name" to "default", "kind" to "pfifo")))
        put("/ip/hotspot", mutableListOf(mutableMapOf(".id" to "*1", "name" to "hotspot1", "interface" to "bridge-hs", "disabled" to "false")))
    }
    override val isConnected get() = true
    override suspend fun connect() {}
    override suspend fun exec(command: String, params: Map<String, String>, queries: List<String>, proplist: List<String>?): ApiResult {
        val path = command.substringBeforeLast('/'); val verb = command.substringAfterLast('/')
        if (path.startsWith("/tool/user-manager")) throw RouterOsException("no such command prefix")
        val rows = db.getOrPut(path) { mutableListOf() }
        return when (verb) {
            "print" -> {
                var l = rows.toList()
                queries.filter { it.startsWith("?") && it.contains('=') }.forEach { q ->
                    val k = q.substring(1).substringBefore('='); val v = q.substringAfter('='); l = l.filter { it[k].orEmpty() == v }
                }
                if (params.containsKey("count-only")) ApiResult(emptyList(), mapOf("ret" to l.size.toString())) else ApiResult(l.map { it.toMap() }, emptyMap())
            }
            "add" -> { val id = "*" + (n++).toString(16); rows += (params + (".id" to id)).toMutableMap(); ApiResult(emptyList(), mapOf("ret" to id)) }
            "set" -> { rows.filter { it[".id"] == params[".id"] }.forEach { it.putAll(params - ".id") }; ApiResult(emptyList(), emptyMap()) }
            "remove" -> { rows.removeAll { it[".id"] == params[".id"] }; ApiResult(emptyList(), emptyMap()) }
            else -> ApiResult(emptyList(), emptyMap())
        }
    }
}

class QueueShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6_PRO.copy(softButtons = false, screenHeight = 3120 * 3),
        maxPercentDifference = 100.0, useDeviceResolution = true, showSystemUi = false
    )

    private fun setup(active: Boolean, preset: List<String>?) {
        val ok = com.zarinet.netcontrol.subscription.SubscriptionStatus(com.zarinet.netcontrol.subscription.SubscriptionState.SUBSCRIPTION_ACTIVE,
            null, "@RGdemo", null, null, false)
        com.zarinet.netcontrol.subscription.SubscriptionGuard.decide = { ok }
        com.zarinet.netcontrol.subscription.SubscriptionGuard.decideAsync = { ok }
        val c = FakeRos()
        Session.client = c
        Session.device = Device(id = "demo", name = "شبكتي", host = "192.168.88.1", user = "admin", password = "")
        if (active) runBlocking { FairSpeed.apply(c, FairSpeed.Plan("192.168.1.0/24", 300_000_000, 300_000_000, 20_000_000, 5_000_000)) }
        fairSpeedPreset = preset
    }

    private fun shot(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) {
        NetControlTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.requiredHeight((3120 * 3 / 3.5f).dp)) { AppBackground { content() } }
        } }
    }

    @Test fun q1_empty() { setup(false, null); shot("q1_empty") { ToolsScreen(onBack = {}) } }
    @Test fun q2_filled() { setup(false, listOf("192.168.1.0/24", "300", "300", "20", "5")); shot("q2_filled") { ToolsScreen(onBack = {}) } }
    @Test fun q3_active() { setup(true, listOf("192.168.1.0/24", "300", "300", "20", "5")); shot("q3_active") { ToolsScreen(onBack = {}) } }
}
