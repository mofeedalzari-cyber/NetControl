package com.zarinet.netcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zarinet.netcontrol.data.Session
import com.zarinet.netcontrol.ui.components.AppBackground
import com.zarinet.netcontrol.ui.screens.*
import com.zarinet.netcontrol.ui.theme.NetControlTheme
import com.zarinet.netcontrol.util.AppForeground

class MainActivity : ComponentActivity() {
    // هل الواجهة ظاهرة؟ (تُوقف التحديث التلقائي في الخلفية)
    override fun onStart() {
        super.onStart()
        AppForeground.visible.value = true
    }

    override fun onStop() {
        AppForeground.visible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.zarinet.netcontrol.ui.L10n.init(this)
        com.zarinet.netcontrol.ui.theme.AppTheme.init(this)
        com.zarinet.netcontrol.account.AuthService.init(this)
        com.zarinet.netcontrol.subscription.SubscriptionService.init(this)
        setContent {
            // الوضع الليلي/النهاري + اتجاه الواجهة حسب اللغة
            val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
            val theme = com.zarinet.netcontrol.ui.theme.AppTheme
            theme.dark = when (theme.mode) { "dark" -> true; "system" -> sysDark; else -> false }
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !theme.dark; isAppearanceLightNavigationBars = !theme.dark
                }
            }
            val dir = if (com.zarinet.netcontrol.ui.L10n.isEn) LayoutDirection.Ltr else LayoutDirection.Rtl
            NetControlTheme {
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    AppBackground {
                        val nav = rememberNavController()
                        // عند انتهاء/إيقاف الاشتراك: أي شاشة تعمل على الراوتر تُغلق ويُفتح شاشة الاشتراك
                        val subStatus by com.zarinet.netcontrol.subscription.SubscriptionService.status.collectAsState()
                        LaunchedEffect(subStatus?.state) {
                            val s = subStatus ?: return@LaunchedEffect
                            val route = nav.currentDestination?.route
                            if (!s.allowed && route != null && route !in setOf("auth", "devices", "subscription", "subAdmin", "settings")) {
                                Session.disconnect()
                                nav.navigate("subscription") { popUpTo("devices") }
                            }
                        }
                        // بدون حساب مسجّل: شاشة الدخول أولاً؛ وإن أُلغيت الجلسة من الخادم نعود إليها
                        val account by com.zarinet.netcontrol.account.AuthService.session.collectAsState()
                        val start = remember { if (account == null) "auth" else "devices" }
                        LaunchedEffect(account == null) {
                            if (account == null && nav.currentDestination?.route !in setOf(null, "auth")) {
                                Session.disconnect()
                                nav.navigate("auth") { popUpTo(0) }
                            }
                        }
                        NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                            composable("auth") {
                                AuthScreen(onLoggedIn = {
                                    com.zarinet.netcontrol.subscription.SubscriptionService.onLogin()
                                    nav.navigate("devices") { popUpTo("auth") { inclusive = true } }
                                })
                            }
                            composable("devices") {
                                DevicesScreen(onConnected = { nav.navigate("dashboard") }, onSubscription = { nav.navigate("subscription") },
                                    onLogout = { nav.navigate("auth") { popUpTo(0) } }, onSettings = { nav.navigate("settings") })
                            }
                            composable("dashboard") {
                                DashboardScreen(
                                    onOpen = { route -> nav.navigate(route) },
                                    onExit = { Session.disconnect(); nav.popBackStack("devices", false) }
                                )
                            }
                            composable("hotspot/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                                HotspotScreen(onBack = { nav.popBackStack() }, initialTab = e.arguments?.getInt("tab") ?: 0)
                            }
                            composable("userman/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                                UserManagerScreen(onBack = { nav.popBackStack() }, initialTab = e.arguments?.getInt("tab") ?: 0)
                            }
                            composable("hotspotUsers") { HotspotScreen(onBack = { nav.popBackStack() }, initialTab = 1) }
                            composable("active") { ActiveScreen(onBack = { nav.popBackStack() }) }
                            composable("vouchers") { HotspotScreen(onBack = { nav.popBackStack() }, initialTab = 2) }
                            composable("userman") { UserManagerScreen(onBack = { nav.popBackStack() }, initialTab = 1) }
                            composable("neighbors") { NeighborsScreen(onBack = { nav.popBackStack() }, onOpenCloud = { nav.navigate("cloud") }) }
                            composable("tools") { ToolsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) }) }
                            composable("blocked") { BlockedScreen(onBack = { nav.popBackStack() }) }
                            composable("packages") { PackagesScreen(onBack = { nav.popBackStack() }) }
                            composable("sales") { SalesScreen(onBack = { nav.popBackStack() }) }
                            composable("subscription") {
                                // المدير يرى «إدارة الاشتراكات» مباشرة بدل صفحة الاشتراك
                                val isAdmin by com.zarinet.netcontrol.account.AuthService.isAdmin.collectAsState()
                                if (isAdmin) SubscriptionAdminScreen(onBack = { nav.popBackStack() })
                                else SubscriptionScreen(onBack = { nav.popBackStack() }, onAdmin = { nav.navigate("subAdmin") })
                            }
                            composable("subAdmin") { SubscriptionAdminScreen(onBack = { nav.popBackStack() }) }
                            composable("firewall/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                                FirewallScreen(
                                    onBack = { nav.popBackStack() },
                                    onConsole = { path -> nav.navigate("console?path=" + android.net.Uri.encode(path)) },
                                    initialTab = e.arguments?.getInt("tab") ?: 0
                                )
                            }
                            composable("dhcps/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                                DhcpServerScreen(
                                    onBack = { nav.popBackStack() },
                                    onConsole = { path -> nav.navigate("console?path=" + android.net.Uri.encode(path)) },
                                    initialTab = e.arguments?.getInt("tab") ?: 0
                                )
                            }
                            composable("dhcpc/{tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType })) { e ->
                                DhcpClientScreen(
                                    onBack = { nav.popBackStack() },
                                    onConsole = { path -> nav.navigate("console?path=" + android.net.Uri.encode(path)) },
                                    initialTab = e.arguments?.getInt("tab") ?: 0
                                )
                            }
                            composable("more") { MoreScreen(onOpen = { nav.navigate(it) }, onBack = { nav.popBackStack() }) }
                            composable("broadband") { BroadbandScreen(onBack = { nav.popBackStack() }) }
                            composable("cloud") { CloudScreen(onBack = { nav.popBackStack() }) }
                            composable("settings") {
                                AccountSettingsScreen(onBack = { nav.popBackStack() }, onLoggedOut = { nav.navigate("auth") { popUpTo(0) } })
                            }
                            composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
                            composable(
                                "resource/{key}",
                                arguments = listOf(navArgument("key") { type = NavType.StringType })
                            ) { e ->
                                val key = e.arguments?.getString("key") ?: ""
                                ResourceScreen(
                                    section = Sections.byKey(key),
                                    onBack = { nav.popBackStack() },
                                    onConsole = { path -> nav.navigate("console?path=" + android.net.Uri.encode(path)) }
                                )
                            }
                            composable(
                                "console?path={path}",
                                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "/system/resource" })
                            ) { e ->
                                ConsoleScreen(initialPath = e.arguments?.getString("path") ?: "", onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Session.disconnect()
    }
}
