package com.dayzero.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzCorner

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(PermissionStatus.read(ctx)) }
    LifecycleResumeEffect(Unit) { status = PermissionStatus.read(ctx); onPauseOrDispose { } }

    val notifRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = PermissionStatus.read(ctx)
        if (!granted) ctx.startActivity(appNotificationSettings(ctx))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dz.Black) }
            Text("Setup", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        PermissionRow(
            "Usage access", "Required", status.usageAccess,
            "Lets DAYZERO see how long each app is open. Only the app name and time are read, never what is on screen.",
            "Open usage access",
        ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.height(14.dp))
        PermissionRow(
            "Notifications", "Required", status.notifications,
            "Interventions and task reminders arrive as notifications.",
            "Allow notifications",
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(appNotificationSettings(ctx))
        }
        Spacer(Modifier.height(14.dp))
        PermissionRow(
            "Background running", "Recommended", status.batteryUnrestricted,
            "Vivo (Funtouch OS) closes background apps aggressively. In iManager or Settings > Battery, set DAYZERO to " +
                "\"No restrictions\" and allow Autostart. Without this, interventions can arrive late or not at all.",
            "Open app settings",
        ) { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.height(24.dp))
        Text(
            "DAYZERO works fully offline. Nothing is sent anywhere.",
            style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(
    title: String, tag: String, granted: Boolean, why: String, action: String, onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(DzCorner).background(Dz.Surface).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) "ON" else "OFF · ${tag.uppercase()}", style = MaterialTheme.typography.labelSmall,
                color = if (granted) Dz.Black else Dz.Muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(why, style = MaterialTheme.typography.bodyMedium, color = Dz.Muted)
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            DzButton(action, onClick = onClick)
        }
    }
}

private fun appNotificationSettings(ctx: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
