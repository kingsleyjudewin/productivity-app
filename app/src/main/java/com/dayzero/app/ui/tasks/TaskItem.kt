package com.dayzero.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.domain.Priority
import com.dayzero.app.domain.TaskLogic
import com.dayzero.app.domain.TaskStatus
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzCorner
import com.dayzero.app.ui.theme.DzPill
import com.dayzero.app.ui.util.fmtSmart

@Composable
fun TaskItem(task: TaskEntity, nowMs: Long, onStatus: (TaskStatus) -> Unit, onClick: () -> Unit) {
    val overdue = TaskLogic.isOverdue(task, nowMs)
    val done = task.status == TaskStatus.DONE
    Column(
        Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                task.name, Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = if (done) Dz.Muted else Dz.Black,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(start = 8.dp))
            if (overdue) Tag("OVERDUE", filled = true) else if (!done) PriorityTag(task.priority)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            meta(task, nowMs),
            style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
        )
        if (task.deadline != task.originalDeadline) {
            Text(
                "Originally due ${fmtSmart(task.originalDeadline, nowMs)}",
                style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DzPill("To do", task.status == TaskStatus.NOT_STARTED, { onStatus(TaskStatus.NOT_STARTED) }, Modifier.weight(1f))
            DzPill("Doing", task.status == TaskStatus.IN_PROGRESS, { onStatus(TaskStatus.IN_PROGRESS) }, Modifier.weight(1f))
            DzPill("Done", done, { onStatus(TaskStatus.DONE) }, Modifier.weight(1f))
        }
    }
}

private fun meta(t: TaskEntity, now: Long): String = buildList {
    add("${fmtSmart(t.startTime, now)} → ${fmtSmart(t.deadline, now)}")
    add("${t.estimatedMinutes} min")
    t.category?.let { add(it) }
}.joinToString(" · ")

@Composable
private fun PriorityTag(p: Priority) {
    // Restrained: only the two urgent levels get weight; the rest recede.
    when (p) {
        Priority.CRITICAL -> Tag("CRITICAL", filled = false, strong = true)
        Priority.HIGH -> Tag("HIGH", filled = false)
        Priority.NORMAL -> Text("NORMAL", style = MaterialTheme.typography.labelSmall, color = Dz.Muted)
        Priority.LOW -> Text("LOW", style = MaterialTheme.typography.labelSmall, color = Dz.Divider)
    }
}

@Composable
private fun Tag(text: String, filled: Boolean, strong: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text, style = MaterialTheme.typography.labelSmall,
        color = if (filled) Dz.White else Dz.Black,
        modifier = Modifier
            .clip(shape)
            .then(if (filled) Modifier.background(Dz.Black) else Modifier.border(if (strong) 1.5.dp else 1.dp, Dz.Black, shape))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
