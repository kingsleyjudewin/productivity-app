package com.dayzero.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzPill

/**
 * Pick a limit in minutes: presets or a custom value. [allowOff] adds a "No limit" choice (0),
 * [onRemove] adds a "Stop monitoring" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(
    title: String,
    current: Int,
    presets: List<Int>,
    allowOff: Boolean,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var minutes by remember { mutableStateOf(current) }
    var custom by remember { mutableStateOf(if (current in presets || current == 0) "" else current.toString()) }
    val customValue = custom.toIntOrNull()
    val chosen = if (custom.isNotBlank()) customValue else minutes
    val valid = chosen != null && (chosen in 1..1440 || (allowOff && chosen == 0))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dz.White,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            DzLabel("Daily limit")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowOff) DzPill("Off", custom.isBlank() && minutes == 0, { minutes = 0; custom = "" }, Modifier.weight(1f))
                presets.forEach { p ->
                    DzPill(if (p < 60) "$p" else "${p / 60}h", custom.isBlank() && minutes == p, { minutes = p; custom = "" }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = custom, onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                label = { Text("Custom (minutes)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = custom.isNotBlank() && !valid,
            )
            Spacer(Modifier.height(20.dp))
            DzButton("Save", enabled = valid, onClick = { chosen?.let(onSave); onDismiss() })
            if (onRemove != null) {
                Spacer(Modifier.height(12.dp))
                DzButton("Stop monitoring", secondary = true, onClick = { onRemove(); onDismiss() })
            }
        }
    }
}
