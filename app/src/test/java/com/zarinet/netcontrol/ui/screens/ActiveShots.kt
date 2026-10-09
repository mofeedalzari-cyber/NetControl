package com.zarinet.netcontrol.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.zarinet.netcontrol.ui.components.AppBackground
import com.zarinet.netcontrol.ui.theme.NetControlTheme
import org.junit.Rule
import org.junit.Test

/** يتحقق أن شاشة المتصلين تُرسم بدون انهيار (بطاقات مطوية ومفتوحة، قيم كبيرة). */
class ActiveShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6_PRO.copy(softButtons = false),
        maxPercentDifference = 100.0, showSystemUi = false
    )

    private fun row(i: Int, src: ActiveSrc, open: Boolean = false) = ActiveRow(
        id = "*$i", user = "1234567$i", address = "10.0.0.$i", mac = "AA:BB:CC:DD:EE:0$i", uptime = "2h13m5s", left = "",
        server = "hotspot1", profile = "", bytesTotal = 1_664_000_000L + i, rateBps = 3_400_000L, source = src,
        download = 796_800_000L, upload = 864_900_000L,
    ).copy(profile = "30 يوم", remainingMs = 3_600_000L * 50, leftBytes = 2_000_000_000L, totalUsed = 9_000_000_000L,
        limit = "↓4M ↑1M", limitKnown = true, infoLoaded = i % 2 == 0)

    @Test fun active() = paparazzi.snapshot("active") {
        NetControlTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.requiredHeight(900.dp)) { AppBackground {
                ActiveContent(listOf(row(1, ActiveSrc.HOTSPOT), row(2, ActiveSrc.USERMAN), row(3, ActiveSrc.HOTSPOT)),
                    loading = false, error = null, auto = false, onAuto = {}, onBack = {}, onRefresh = {}, onKick = {},
                    initiallyOpen = setOf("*2"))
            } }
        } }
    }
}
