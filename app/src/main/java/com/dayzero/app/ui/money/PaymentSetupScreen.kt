package com.dayzero.app.ui.money

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayzero.app.domain.money.KnownPaymentApps
import com.dayzero.app.ui.theme.Dz
import com.dayzero.app.ui.theme.DzButton
import com.dayzero.app.ui.theme.DzLabel

@Composable
fun PaymentSetupScreen(onBack: () -> Unit, vm: MoneyViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val selected by vm.selectedApps.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val pm = ctx.packageManager
    val installed = KnownPaymentApps.apps.filter { runCatching { pm.getPackageInfo(it.first, 0) }.isSuccess }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dz.Black) }
            Text("Payment apps", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "DAYZERO can log your UPI payments automatically by reading the payment notifications those apps show. " +
                "Android groups this under \"Notification access\", which technically covers every app, so DAYZERO " +
                "discards everything except the apps you tick below, without reading it. " +
                "It never reads SMS or OTPs, never stores notification text (only amount, merchant and time), " +
                "and nothing ever leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        DzLabel("Notification access")
        Text(if (access) "On" else "Off — automatic tracking is not running", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        DzButton(if (access) "Manage notification access" else "Turn on notification access", onClick = {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Spacer(Modifier.height(24.dp))
        DzLabel("Track these apps")
        Spacer(Modifier.height(8.dp))
        if (installed.isEmpty()) Text("None of the supported payment apps were found on this phone. You can still add expenses by hand.", color = Dz.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            installed.forEach { (pkg, label) ->
                val on = selected.any { it.packageName == pkg }
                DzButton(if (on) "$label — tracking" else label, onClick = { vm.toggleApp(pkg, label, !on) }, secondary = !on)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Notification wording differs between apps and versions. Anything DAYZERO is not sure about is saved as " +
                "\"Unverified\" and never counted until you confirm it.",
            style = MaterialTheme.typography.bodyMedium, color = Dz.Muted,
        )
    }
}
