package com.dayzero.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.HistoryFilter
import com.dayzero.app.domain.HistoryFormatter
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzPill
import com.dayzero.app.ui.util.fmtDateTime
import com.dayzero.app.ui.util.fmtTime

@Composable
fun HistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val grouped = entries.groupBy { it.dayKey }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dz.Black) }
            Text("History", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { f ->
                DzPill(f.label, filter == f, { vm.filter.value = f }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (entries.isEmpty()) {
            Text("Nothing recorded.", style = MaterialTheme.typography.bodyLarge, color = Dz.Muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            grouped.forEach { (day, list) ->
                item(key = "d-$day") { Spacer(Modifier.height(12.dp)); DzLabel(day) ; Spacer(Modifier.height(6.dp)) }
                items(list, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row {
                            Text(e.taskName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(fmtTime(e.timestamp), style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
                        }
                        Text(HistoryFormatter.describe(e, ::fmtDateTime), style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
