package com.dayzero.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.MoneyFormat
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzPill

private val currencies = listOf("INR", "USD", "EUR", "GBP")

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val settings = s ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Dz.Black)
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(24.dp))

        DzLabel("Daily spending limit")
        Spacer(Modifier.height(8.dp))
        Text("Set it on the MONEY tab. Changes there never rewrite past days.", style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)

        Spacer(Modifier.height(28.dp))
        DzLabel("Currency")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            currencies.forEach { c ->
                Column(Modifier.weight(1f)) {
                    DzButton(c, onClick = { vm.update { it.copy(currency = c) } }, secondary = c != settings.currency)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        DzLabel("Day starts at")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(72.dp)) {
                DzButton("−", onClick = { vm.update { it.copy(resetMinuteOfDay = (it.resetMinuteOfDay - 60 + 1440) % 1440) } }, secondary = true)
            }
            Text(
                "%02d:%02d".format(settings.resetMinuteOfDay / 60, settings.resetMinuteOfDay % 60),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Column(Modifier.width(72.dp)) {
                DzButton("+", onClick = { vm.update { it.copy(resetMinuteOfDay = (it.resetMinuteOfDay + 60) % 1440) } }, secondary = true)
            }
        }

        Spacer(Modifier.height(28.dp))
        DzLabel("Task reminders")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.notificationsEnabled,
                onCheckedChange = { on -> vm.update { it.copy(notificationsEnabled = on) } },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Dz.Black, checkedThumbColor = Dz.White,
                    uncheckedTrackColor = Dz.Surface, uncheckedThumbColor = Dz.Muted, uncheckedBorderColor = Dz.Divider,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
        OptionRow("First reminder after", listOf(15, 30, 45, 60), settings.firstReminderMin) { m ->
            vm.update { it.copy(firstReminderMin = m) }
        }
        Spacer(Modifier.height(16.dp))
        OptionRow("Then every", listOf(10, 15, 30, 60), settings.taskReminderIntervalMin) { m ->
            vm.update { it.copy(taskReminderIntervalMin = m) }
        }

        Spacer(Modifier.height(28.dp))
        DzLabel("Screen-time interventions")
        Spacer(Modifier.height(8.dp))
        OptionRow("Wait at least this long between alerts for the same app", listOf(5, 10, 15, 30), settings.interventionCooldownMin) { m ->
            vm.update { it.copy(interventionCooldownMin = m) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun OptionRow(label: String, options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { m ->
            DzPill("$m min", m == selected, { onSelect(m) }, Modifier.weight(1f))
        }
    }
}
