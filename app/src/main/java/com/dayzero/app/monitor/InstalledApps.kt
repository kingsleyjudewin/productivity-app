package com.dayzero.app.monitor

import android.content.Context
import android.content.Intent
import com.dayzero.app.domain.usage.SuggestedApps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(val pkg: String, val label: String, val suggested: Boolean)

@Singleton
class InstalledApps @Inject constructor(@ApplicationContext private val ctx: Context) {
    /** Launchable apps only (what a person can actually open), excluding DAYZERO itself. */
    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> InstalledApp(pkg, label, pkg in SuggestedApps.packages) }
    }
}
