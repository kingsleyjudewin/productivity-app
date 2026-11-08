package com.dayzero.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dayzero.app.ui.home.HomeScreen
import com.dayzero.app.ui.money.MoneyHistoryScreen
import com.dayzero.app.ui.money.MoneyScreen
import com.dayzero.app.ui.money.PaymentSetupScreen
import com.dayzero.app.ui.screen.AppSelectionScreen
import com.dayzero.app.ui.screen.PermissionsScreen
import com.dayzero.app.ui.screen.ScreenTimeScreen
import com.dayzero.app.ui.settings.SettingsScreen
import com.dayzero.app.ui.tasks.HistoryScreen
import com.dayzero.app.ui.tasks.TasksScreen
import com.dayzero.app.ui.theme.Dz

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("home", "HOME"), Tab("tasks", "TASKS"), Tab("screen", "SCREEN"), Tab("money", "MONEY"),
)

@Composable
fun DayZeroNav(rescheduleTaskId: Long = -1L, onRescheduleHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    // Jump to TASKS when a notification asks for a reschedule.
    androidx.compose.runtime.LaunchedEffect(rescheduleTaskId) {
        if (rescheduleTaskId > 0) nav.navigate("tasks") { launchSingleTop = true }
    }

    Scaffold(
        containerColor = Dz.White,
        bottomBar = {
            if (route !in setOf("settings", "history", "screen_apps", "permissions", "money_history", "money_setup")) {
                BottomBar(current = route) { target ->
                    nav.navigate(target) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(onOpenSettings = { nav.navigate("settings") }) }
                composable("tasks") {
                    TasksScreen(rescheduleTaskId, onRescheduleHandled, onOpenHistory = { nav.navigate("history") })
                }
                composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
                composable("screen") {
                    ScreenTimeScreen(onManageApps = { nav.navigate("screen_apps") }, onOpenPermissions = { nav.navigate("permissions") })
                }
                composable("screen_apps") { AppSelectionScreen(onBack = { nav.popBackStack() }) }
                composable("permissions") { PermissionsScreen(onBack = { nav.popBackStack() }) }
                composable("money") { MoneyScreen(onHistory = { nav.navigate("money_history") }, onSetup = { nav.navigate("money_setup") }) }
                composable("money_history") { MoneyHistoryScreen(onBack = { nav.popBackStack() }) }
                composable("money_setup") { PaymentSetupScreen(onBack = { nav.popBackStack() }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Dz.White.copy(alpha = 0.96f)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dz.Divider))
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            tabs.forEach { tab ->
                val selected = tab.route == current
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(role = Role.Tab, onClick = { onSelect(tab.route) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        tab.label, style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Dz.Black else Dz.Muted,
                    )
                    Box(
                        Modifier.padding(top = 6.dp).width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Dz.Black else Dz.White),
                    )
                }
            }
        }
    }
}
