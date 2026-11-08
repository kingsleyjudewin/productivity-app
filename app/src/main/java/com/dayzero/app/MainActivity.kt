package com.dayzero.app

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.dayzero.app.reminders.TaskIntents
import com.dayzero.app.ui.nav.DayZeroNav
import com.dayzero.app.ui.theme.DayZeroTheme
import androidx.lifecycle.lifecycleScope
import com.dayzero.app.monitor.MonitorController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Set when the overdue notification's RESCHEDULE button opened the app. -1 = nothing pending. */
    private var rescheduleTaskId by mutableLongStateOf(-1L)

    @Inject lateinit var monitor: MonitorController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        consume(intent)
        setContent {
            DayZeroTheme {
                DayZeroNav(rescheduleTaskId = rescheduleTaskId, onRescheduleHandled = { rescheduleTaskId = -1L })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app is in the foreground here, so starting the monitoring service is always allowed.
        lifecycleScope.launch { monitor.sync() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        val id = intent?.getLongExtra(TaskIntents.EXTRA_RESCHEDULE_TASK_ID, -1L) ?: -1L
        if (id > 0) rescheduleTaskId = id
    }
}
