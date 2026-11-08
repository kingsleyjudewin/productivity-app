package com.dayzero.app.ui.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.Priority
import com.dayzero.app.domain.TaskDraft
import com.dayzero.app.domain.TaskError
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzCorner
import com.dayzero.app.ui.theme.DzLabel
import com.dayzero.app.ui.theme.DzPill
import com.dayzero.app.ui.util.fmtDateTime
import java.util.Calendar

private val durations = listOf(15, 30, 45, 60, 90, 120)
private const val MIN = 60_000L

/** Add (task == null) or edit/reschedule (task != null). Only the title is required. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormSheet(
    task: TaskEntity?,
    nowMs: Long,
    onDismiss: () -> Unit,
    onSave: (TaskDraft, (List<TaskError>) -> Unit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val roundedNow = nowMs - nowMs % MIN
    var title by remember { mutableStateOf(task?.name ?: "") }
    var priority by remember { mutableStateOf(task?.priority ?: Priority.NORMAL) }
    var duration by remember { mutableIntStateOf(task?.estimatedMinutes ?: 30) }
    var start by remember { mutableLongStateOf(task?.startTime ?: roundedNow) }
    var deadline by remember { mutableLongStateOf(task?.deadline ?: (roundedNow + 30 * MIN)) }
    var deadlineTouched by remember { mutableStateOf(task != null) }
    var category by remember { mutableStateOf(task?.category ?: "") }
    var errors by remember { mutableStateOf<List<TaskError>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dz.White,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                .padding(horizontal = 24.dp).padding(bottom = 16.dp),
        ) {
            Text(if (task == null) "New task" else "Edit task", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = errors.any { it == TaskError.TITLE_EMPTY || it == TaskError.TITLE_TOO_LONG },
            )

            Spacer(Modifier.height(16.dp))
            DzLabel("Priority")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    DzPill(p.label, priority == p, { priority = p }, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            DzLabel("Start")
            Spacer(Modifier.height(8.dp))
            TimeField(fmtDateTime(start)) {
                pickDateTime(ctx, start) {
                    start = it
                    if (!deadlineTouched) deadline = it + duration * MIN
                }
            }
            Spacer(Modifier.height(12.dp))
            DzLabel("Deadline")
            Spacer(Modifier.height(8.dp))
            TimeField(fmtDateTime(deadline)) {
                pickDateTime(ctx, deadline) { deadline = it; deadlineTouched = true }
            }

            Spacer(Modifier.height(16.dp))
            DzLabel("Estimated duration")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                durations.forEach { d ->
                    DzPill(if (d < 60) "${d}m" else "${d / 60.0}h".replace(".0h", "h"), duration == d, {
                        duration = d
                        if (!deadlineTouched) deadline = start + d * MIN
                    }, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = category, onValueChange = { category = it.take(30) }, label = { Text("Category (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            errors.firstOrNull()?.let {
                Spacer(Modifier.height(12.dp))
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = Dz.Black)
            }

            Spacer(Modifier.height(20.dp))
            DzButton("Save", onClick = {
                onSave(
                    TaskDraft(title, priority, start, deadline, duration, category.ifBlank { null }),
                ) { errs -> if (errs.isEmpty()) onDismiss() else errors = errs }
            })
            if (onDelete != null) {
                Spacer(Modifier.height(12.dp))
                DzButton("Delete task", onClick = { confirmDelete = true }, secondary = true)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            text = { Text("Its reminders are cancelled. History keeps a record that it existed.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke(); onDismiss() }) { Text("Delete", color = Dz.Black) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Dz.Muted) } },
            containerColor = Dz.White,
        )
    }
}

@Composable
private fun TimeField(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

private fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            val r = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }
            onPicked(r.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
