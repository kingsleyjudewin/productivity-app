package com.dayzero.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzProgressRing
import com.dayzero.app.ui.theme.DzStat

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DAYZERO", style = MaterialTheme.typography.headlineLarge)
                Text(s.dateLabel, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Dz.Black)
            }
        }
        Spacer(Modifier.height(28.dp))
        DzLabel("Daily progress")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DzProgressRing(s.progressPercent / 100f, 188.dp) {
                Text("${s.progressPercent}%", style = MaterialTheme.typography.displayLarge.copy(fontSize = 52.sp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DzStat("Tasks", "${s.tasksDone}/${s.tasksTotal}", Modifier.weight(1f), sub = if (s.overdue > 0) "${s.overdue} overdue" else "done")
            DzStat("Screen", s.screenText, Modifier.weight(1f), sub = s.screenSub)
        }
        Spacer(Modifier.height(12.dp))
        DzStat(
            "Budget",
            if (s.budgetText != null) "${s.spentText} / ${s.budgetText}" else s.spentText,
            Modifier.fillMaxWidth(),
            sub = s.budgetSub,
        )
        Spacer(Modifier.height(28.dp))
        DzLabel("Today's priorities")
        Spacer(Modifier.height(10.dp))
        if (s.priorities.isEmpty()) {
            Text("Nothing pending.", style = MaterialTheme.typography.bodyLarge, color = Dz.Muted)
        } else {
            s.priorities.forEachIndexed { i, name ->
                Text("${i + 1}.  $name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

