package com.dayzero.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.usage.UsageFormat
import com.dayzero.app.monitor.MonitorService
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzCorner
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzStat

@Composable
fun ScreenTimeScreen(onManageApps: () -> Unit, onOpenPermissions: () -> Unit, vm: ScreenTimeViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(PermissionStatus.read(ctx)) }
    var editing by remember { mutableStateOf<AppRow?>(null) }
    var editingTotal by remember { mutableStateOf(false) }

    // Permissions are re-read on every resume: the user can revoke them at any time in Settings.
    LifecycleResumeEffect(Unit) {
        perms = PermissionStatus.read(ctx)
        vm.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("SCREEN", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            if (!perms.ready) {
                Warning(
                    if (!perms.usageAccess) "Usage access is off. Nothing is being measured."
                    else "Notifications are off. Interventions can't reach you.",
                    onClick = onOpenPermissions,
                )
                Spacer(Modifier.height(16.dp))
            }
            DzLabel("Screen time today")
            Text(
                UsageFormat.minutes(s.totalMs),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DzStat(
                    "Limit", if (s.limitMin > 0) UsageFormat.minutesOf(s.limitMin) else "None",
                    Modifier.weight(1f).clip(DzCorner).clickable { editingTotal = true },
                )
                val rem = s.remainingMs
                DzStat(
                    "Left", when { rem == null -> "—"; rem > 0 -> UsageFormat.minutes(rem); else -> "0 min" },
                    Modifier.weight(1f),
                    sub = if (rem != null && rem <= 0) "Over limit" else null,
                )
                DzStat("Alerts", "${s.interventions}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            MonitoringRow(s.monitoringEnabled, perms.usageAccess, vm::setMonitoring)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DzLabel("Monitored apps · ${UsageFormat.minutes(s.monitoredMs)}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
        }
        if (s.loaded && s.rows.isEmpty()) {
            item { Text("No apps selected.", style = MaterialTheme.typography.bodyLarge, color = Dz.Muted) }
        }
        items(s.rows, key = { it.pkg }) { row -> AppUsageRow(row) { editing = row } }
        item {
            Spacer(Modifier.height(4.dp))
            DzButton(if (s.rows.isEmpty()) "Choose apps" else "Manage apps", onClick = onManageApps)
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { row ->
        LimitSheet(
            title = row.label, current = row.limitMin, presets = listOf(15, 30, 45, 60), allowOff = false,
            onSave = { vm.setAppLimit(row.pkg, row.label, it) }, onDismiss = { editing = null },
            onRemove = { vm.removeApp(row.pkg) },
        )
    }
    if (editingTotal) {
        LimitSheet(
            title = "Daily screen time", current = s.limitMin, presets = listOf(60, 120, 180, 240), allowOff = true,
            onSave = vm::setTotalLimit, onDismiss = { editingTotal = false },
        )
    }
}

@Composable
private fun MonitoringRow(enabled: Boolean, usageAccess: Boolean, onToggle: (Boolean) -> Unit) {
    // Status is only "active" when the service is genuinely running; never claim more than is true.
    val status = when {
        !enabled -> "Off"
        !usageAccess -> "Paused · usage access is off"
        MonitorService.running -> "Active while the screen is on"
        else -> "Starting… open the app again if this stays"
    }
    Row(
        Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Real-time monitoring", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
        }
        Switch(
            checked = enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Dz.Black, checkedThumbColor = Dz.White,
                uncheckedTrackColor = Dz.White, uncheckedThumbColor = Dz.Muted, uncheckedBorderColor = Dz.Divider,
            ),
        )
    }
}

@Composable
private fun AppUsageRow(row: AppRow, onClick: () -> Unit) {
    val over = row.remainingMs <= 0
    Column(Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${UsageFormat.short(row.usedMs)} of ${UsageFormat.minutesOf(row.limitMin)}",
                    style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
                )
            }
            Text(
                if (over) "Over by ${UsageFormat.short(-row.remainingMs)}" else "${UsageFormat.short(row.remainingMs)} left",
                style = MaterialTheme.typography.labelSmall, color = if (over) Dz.Black else Dz.Muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        val frac = (row.usedMs.toFloat() / row.limitMs).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Dz.Divider)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Dz.Black))
        }
    }
}

@Composable
private fun Warning(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Black).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Dz.White)
        Text("SET UP", style = MaterialTheme.typography.labelSmall, color = Dz.White, modifier = Modifier.padding(start = 12.dp))
    }
}
