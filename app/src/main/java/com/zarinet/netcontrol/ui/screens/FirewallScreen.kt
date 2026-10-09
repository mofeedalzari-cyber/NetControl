package com.zarinet.netcontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zarinet.netcontrol.ui.components.*
import com.zarinet.netcontrol.ui.theme.C
import com.zarinet.netcontrol.ui.theme.D

/** تبويبات شاشة جدار الحماية (مفاتيح Sections). */
val FIREWALL_TABS = listOf("filter", "nat", "mangle", "raw", "svcport", "conn", "tracking", "addrlist", "layer7")

/** تبويبات خادم DHCP. */
val DHCP_SERVER_TABS = listOf("dhcpsrv", "dhcpconfig", "dhcpnet", "dhcp", "dhcpopt", "dhcpoptset", "dhcpmatch", "dhcpalert")

/** تبويبات عميل DHCP. */
val DHCP_CLIENT_TABS = listOf("dhcpclient", "dhcpclientopt")

@Composable
fun FirewallScreen(onBack: () -> Unit, onConsole: (String) -> Unit, initialTab: Int = 0) {
    TabHubScreen("جدار الحماية", "Firewall", FIREWALL_TABS, onBack, onConsole, initialTab)
}

@Composable
fun DhcpServerScreen(onBack: () -> Unit, onConsole: (String) -> Unit, initialTab: Int = 0) {
    TabHubScreen("خادم DHCP", "DHCP Server", DHCP_SERVER_TABS, onBack, onConsole, initialTab)
}

@Composable
fun DhcpClientScreen(onBack: () -> Unit, onConsole: (String) -> Unit, initialTab: Int = 0) {
    TabHubScreen("عميل DHCP", "DHCP Client", DHCP_CLIENT_TABS, onBack, onConsole, initialTab)
}

/** شاشة تجمع عدة أقسام تحت شريط تبويبات أفقي؛ كل تبويب يعرض ResourceScreen مضمّنة. */
@Composable
fun TabHubScreen(
    title: String,
    en: String,
    tabs: List<String>,
    onBack: () -> Unit,
    onConsole: (String) -> Unit,
    initialTab: Int = 0
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }
    val section = Sections.byKey(tabs[tab])

    Column(Modifier.fillMaxSize()) {
        TopBar(title, "$en — ${section.title}", onBack) {
            BarAction(Icons.Filled.Terminal, "API Console", C.Dim) { onConsole(section.path) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = D.screen),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { i, key ->
                val selected = i == tab
                val label = if (key == "addrlist") "Address Lists" else Sections.byKey(key).title
                Text(
                    label,
                    modifier = Modifier.clip(RoundedCornerShape(D.radiusSmall))
                        .background(if (selected) C.Teal else C.Card)
                        .border(1.dp, if (selected) C.Teal else C.Border, RoundedCornerShape(D.radiusSmall))
                        .clickable { tab = i }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (selected) C.OnTeal else C.Text,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.height(D.sm))
        // key: تُعاد تهيئة حالة القسم عند تبديل التبويب
        key(section.key) {
            ResourceScreen(section, onBack, onConsole, embedded = true)
        }
    }
}
