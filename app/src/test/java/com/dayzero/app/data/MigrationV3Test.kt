package com.dayzero.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.data.local.AppUsageEntity
import com.dayzero.app.data.local.DayZeroDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class MigrationV3Test {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun legacyDb(name: String, version: Int): SQLiteDatabase {
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name); file.parentFile?.mkdirs()
        val schema = listOf("schemas", "app/schemas").map { File("$it/com.dayzero.app.data.local.DayZeroDatabase/$version.json") }.first { it.exists() }
        val dbJson = JSONObject(schema.readText()).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = dbJson.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i); val t = e.getString("tableName")
            raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", t))
            val idx = e.getJSONArray("indices")
            for (j in 0 until idx.length()) raw.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", t))
        }
        val setup = dbJson.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.version = version
        return raw
    }

    private fun open(name: String) = Room.databaseBuilder(ctx, DayZeroDatabase::class.java, name)
        .addMigrations(*DayZeroDatabase.MIGRATIONS).allowMainThreadQueries().build()

    @Test fun v2_to_v3_keepsAllPhase2Data_andAddsScreenTimeColumnsWithSafeDefaults() {
        val raw = legacyDb("m23.db", 2)
        raw.execSQL("INSERT INTO tasks (id,name,priority,category,startTime,deadline,originalDeadline,estimatedMinutes,status,completedAt,createdAt,lastCheckInAt,reminderCount,nextAlertAt,overdueNotifiedAt) VALUES (1,'Keep me','CRITICAL',NULL,1000,2000,2000,30,'NOT_STARTED',NULL,1,NULL,2,1500,NULL)")
        raw.execSQL("INSERT INTO user_settings (id,dailyBudgetMinor,currency,resetMinuteOfDay,defaultLimitMinutes,taskReminderIntervalMin,firstReminderMin,persistentTaskNotification,notificationsEnabled,overlayInterventions,interventionCooldownMin) VALUES (1,50000,'INR',120,45,20,40,0,1,0,15)")
        raw.execSQL("INSERT INTO usage_limits (packageName,appLabel,limitMinutes,enabled) VALUES ('com.instagram.android','Instagram',30,1)")
        raw.execSQL("INSERT INTO app_usage (id,packageName,dayKey,usageMs,interventions,lastInterventionAt) VALUES (1,'com.instagram.android','2026-11-07',600000,2,12345)")
        raw.close()

        val db = open("m23.db")
        val t = runBlocking { db.taskDao().get(1)!! }
        assertEquals("Keep me", t.name); assertEquals(2, t.reminderCount); assertEquals(1500L, t.nextAlertAt)
        val s = runBlocking { db.settingsDao().get()!! }
        assertEquals(50000L, s.dailyBudgetMinor); assertEquals(120, s.resetMinuteOfDay)
        assertEquals(40, s.firstReminderMin); assertEquals(15, s.interventionCooldownMin)
        assertEquals(0, s.dailyScreenLimitMinutes)       // no overall limit until chosen
        assertTrue(s.monitoringEnabled)                  // monitoring defaults on
        val u = runBlocking { db.appUsageDao().get("com.instagram.android", "2026-11-07")!! }
        assertEquals(600000L, u.usageMs); assertEquals(2, u.interventions); assertNull(u.snoozeUntil)
        assertEquals(1, runBlocking { db.usageLimitDao().getEnabled() }.size)
        db.close(); ctx.deleteDatabase("m23.db")
    }

    @Test fun v1_to_v3_fullChain() {
        val raw = legacyDb("m13.db", 1)
        raw.execSQL("INSERT INTO tasks (id,name,priority,category,startTime,deadline,originalDeadline,estimatedMinutes,status,completedAt,createdAt,lastCheckInAt) VALUES (1,'Old','LOW',NULL,1,2,2,10,'DONE',2,1,NULL)")
        raw.close()
        val db = open("m13.db")
        assertEquals("Old", runBlocking { db.taskDao().get(1)!! }.name)
        // New tables/columns are usable after the chain.
        runBlocking { db.appUsageDao().upsert(AppUsageEntity(packageName = AppUsageEntity.TOTAL, dayKey = "2026-11-08", usageMs = 5, snoozeUntil = 9)) }
        assertEquals(9L, runBlocking { db.appUsageDao().get(AppUsageEntity.TOTAL, "2026-11-08")!! }.snoozeUntil)
        db.close(); ctx.deleteDatabase("m13.db")
    }
}
