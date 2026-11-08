package com.dayzero.app.ui.tasks

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzCorner
import com.dayzero.app.ui.theme.DzLabel

@Composable
fun TasksScreen(
    rescheduleTaskId: Long,
    onRescheduleHandled: () -> Unit,
    onOpenHistory: () -> Unit,
    vm: TasksViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    // A tap on the overdue notification's RESCHEDULE opens the editor for that task.
    LaunchedEffect(rescheduleTaskId, s.loaded) {
        if (rescheduleTaskId > 0 && s.loaded) {
            val all = s.board.now + s.board.upcoming + s.board.completed
            all.firstOrNull { it.id == rescheduleTaskId }?.let { editing = it }
            onRescheduleHandled()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TASKS", style = MaterialTheme.typography.headlineLarge)
                    Text(s.dateLabel, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
                }
                Text(
                    "HISTORY", style = MaterialTheme.typography.labelSmall, color = Dz.Black,
                    modifier = Modifier.clip(DzCorner).clickable(onClick = onOpenHistory).padding(12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Summary("Done", "${s.progress.done}", Modifier.weight(1f))
                Summary("Left", "${s.progress.pending}", Modifier.weight(1f))
                Summary("Complete", "${s.progress.percent}%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            DzButton("Add task", onClick = { adding = true })
            PermissionBanner()
        }
        if (s.loaded && s.board.isEmpty) {
            item { Text("No tasks yet.", style = MaterialTheme.typography.bodyLarge, color = Dz.Muted, modifier = Modifier.padding(top = 8.dp)) }
        }
        section("Now", s.board.now, s.nowMs, vm) { editing = it }
        section("Upcoming", s.board.upcoming, s.nowMs, vm) { editing = it }
        section("Completed", s.board.completed, s.nowMs, vm) { editing = it }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (adding) {
        TaskFormSheet(
            task = null, nowMs = s.nowMs, onDismiss = { adding = false },
            onSave = { draft, cb -> vm.save(null, draft, cb) }, onDelete = null,
        )
    }
    editing?.let { t ->
        TaskFormSheet(
            task = t, nowMs = s.nowMs, onDismiss = { editing = null },
            onSave = { draft, cb -> vm.save(t.id, draft, cb) }, onDelete = { vm.delete(t.id) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, tasks: List<TaskEntity>, now: Long, vm: TasksViewModel, onClick: (TaskEntity) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "h-$title") {
        Spacer(Modifier.height(10.dp))
        DzLabel("$title · ${tasks.size}")
    }
    items(tasks, key = { it.id }) { t ->
        TaskItem(t, now, onStatus = { vm.setStatus(t, it) }, onClick = { onClick(t) })
    }
}

@Composable
private fun Summary(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(DzCorner).background(Dz.Surface).padding(vertical = 12.dp, horizontal = 14.dp)) {
        DzLabel(label)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
    }
}

/** One compact line, shown only while something prevents on-time reminders. */
@Composable
private fun PermissionBanner() {
    val ctx = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    var exactOk by remember { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        exactOk = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = granted
        if (!granted) openNotificationSettings(ctx)
    }

    when {
        !notificationsOn -> Banner("Notifications are off. Reminders can't reach you.", "Enable") {
            if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(ctx)
        }
        !exactOk -> Banner("Allow exact alarms for on-time reminders.", "Allow") {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(DzCorner).background(Dz.Surface).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            action.uppercase(), style = MaterialTheme.typography.labelSmall, color = Dz.Black,
            modifier = Modifier.clip(DzCorner).clickable(onClick = onClick).padding(12.dp),
        )
    }
}

private fun openNotificationSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
