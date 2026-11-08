package com.dayzero.app.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.support.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.data.local.TaskEntity
import com.dayzero.app.data.repo.TaskResult
import com.dayzero.app.domain.*
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class DatabaseTest {
    private val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun dataSurvivesClosingAndReopeningTheDatabase() {
        val name = "persist-test.db"
        ctx.deleteDatabase(name)
        fun open() = Room.databaseBuilder(ctx, DayZeroDatabase::class.java, name).allowMainThreadQueries().build()
        val first = open()
        val id = runBlocking {
            first.taskDao().insert(TaskEntity(name = "Persisted", startTime = T0, deadline = T0 + HOUR, originalDeadline = T0 + HOUR, nextAlertAt = T0, reminderCount = 2))
        }
        first.close()                                           // simulates the process dying
        val second = open()
        val t = runBlocking { second.taskDao().get(id)!! }
        assertEquals("Persisted", t.name); assertEquals(2, t.reminderCount); assertEquals(T0, t.nextAlertAt)
        second.close(); ctx.deleteDatabase(name)
    }

    @Test fun reminderStatePersists_soAnAlarmAfterRestartDoesNotDoubleFire() {
        val env = TestEnv()
        val id = runBlocking { (env.repo.create(draft()) as TaskResult.Ok).id }
        env.clock.t = T0 + 10 * MIN
        runBlocking { env.engine.onCheckInDue(id) }
        // A "restarted process" is a new engine over the same database.
        val engine2 = com.dayzero.app.data.repo.ReminderEngine(env.db, env.db.taskDao(), env.settings, env.history, env.scheduler, env.notifier, env.clock)
        runBlocking { engine2.onCheckInDue(id) }
        assertEquals(1, env.notifier.checkIns.size)
        env.close()
    }

    @Test fun dayWindow_respectsConfiguredResetTime() {
        val env = TestEnv()
        runBlocking { env.settings.update { it.copy(resetMinuteOfDay = 4 * 60) } }  // day starts at 04:00
        val key = DayKeys.keyFor(T0, 240)                        // 09:00 -> today's logical day
        val start = DayKeys.startMillis(key, 240); val end = DayKeys.endMillis(key, 240)
        fun add(n: String, deadline: Long) = runBlocking {
            env.db.taskDao().insert(TaskEntity(name = n, startTime = deadline - MIN, deadline = deadline, originalDeadline = deadline))
        }
        add("before-reset", start - MIN)       // 03:59 -> still belongs to the previous logical day (and not done => overdue carry-over)
        add("just-after-reset", start + MIN)
        add("late-night", end - MIN)            // 03:59 next calendar day -> still today's logical day
        add("tomorrow", end + MIN)
        val names = runBlocking { env.db.taskDao().observeForDay(start, end).first() }.map { it.name }.toSet()
        assertEquals(setOf("before-reset", "just-after-reset", "late-night"), names)
        env.close()
    }

    @Test fun dayWindow_completedYesterdayIsNotCounted_butUnfinishedYesterdayCarriesOver() {
        val env = TestEnv()
        val key = DayKeys.keyFor(T0, 0)
        val start = DayKeys.startMillis(key, 0); val end = DayKeys.endMillis(key, 0)
        runBlocking {
            env.db.taskDao().insert(TaskEntity(name = "done-yday", startTime = start - 2 * HOUR, deadline = start - HOUR, originalDeadline = start - HOUR, status = TaskStatus.DONE))
            env.db.taskDao().insert(TaskEntity(name = "open-yday", startTime = start - 2 * HOUR, deadline = start - HOUR, originalDeadline = start - HOUR))
        }
        val names = runBlocking { env.db.taskDao().observeForDay(start, end).first() }.map { it.name }
        assertEquals(listOf("open-yday"), names)
        env.close()
    }

    @Test fun dashboardProgress_followsDatabaseChanges() {
        val env = TestEnv()
        val key = DayKeys.keyFor(T0, 0)
        val start = DayKeys.startMillis(key, 0); val end = DayKeys.endMillis(key, 0)
        val ids = (1..4).map { i ->
            runBlocking { (env.repo.create(draft("t$i", start = T0 + i * MIN, deadline = T0 + HOUR + i * MIN)) as TaskResult.Ok).id }
        }
        fun progress() = DailyProgress.of(runBlocking { env.db.taskDao().observeForDay(start, end).first() }, env.clock.t)
        assertEquals(0, progress().percent)
        runBlocking { env.repo.setStatus(ids[0], TaskStatus.DONE); env.repo.setStatus(ids[1], TaskStatus.DONE); env.repo.setStatus(ids[2], TaskStatus.DONE) }
        assertEquals(DailyProgress(total = 4, done = 3, pending = 1, overdue = 0, percent = 75), progress())
        env.clock.t = T0 + 3 * HOUR
        assertEquals(1, progress().overdue)
        env.close()
    }

    @Test fun migration_v1_to_v2_keepsExistingData_andAddsColumnsWithDefaults() {
        val dbName = "migration-test.db"
        ctx.deleteDatabase(dbName)
        val file = ctx.getDatabasePath(dbName); file.parentFile?.mkdirs()
        val schema = listOf("schemas/com.dayzero.app.data.local.DayZeroDatabase/1.json", "app/schemas/com.dayzero.app.data.local.DayZeroDatabase/1.json")
            .map(::File).first { it.exists() }
        val dbJson = JSONObject(schema.readText()).getJSONObject("database")

        // Build a genuine version-1 database straight from the exported v1 schema.
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = dbJson.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val idx = e.getJSONArray("indices")
            for (j in 0 until idx.length()) raw.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = dbJson.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.execSQL("INSERT INTO tasks (id,name,priority,category,startTime,deadline,originalDeadline,estimatedMinutes,status,completedAt,createdAt,lastCheckInAt) VALUES (1,'Legacy task','HIGH','Work',1000,2000,2000,45,'IN_PROGRESS',NULL,500,NULL)")
        raw.execSQL("INSERT INTO user_settings (id,dailyBudgetMinor,currency,resetMinuteOfDay,defaultLimitMinutes,taskReminderIntervalMin,persistentTaskNotification,notificationsEnabled,overlayInterventions,interventionCooldownMin) VALUES (1,30000,'INR',240,30,20,0,1,0,10)")
        raw.version = 1
        raw.close()

        // Opening through Room runs MIGRATION_1_2 and then validates against the v2 schema.
        val db = Room.databaseBuilder(ctx, DayZeroDatabase::class.java, dbName)
            .addMigrations(*DayZeroDatabase.MIGRATIONS).allowMainThreadQueries().build()
        val t = runBlocking { db.taskDao().get(1)!! }
        assertEquals("Legacy task", t.name); assertEquals(Priority.HIGH, t.priority)
        assertEquals(TaskStatus.IN_PROGRESS, t.status); assertEquals(2000L, t.deadline)
        assertEquals(0, t.reminderCount); assertNull(t.nextAlertAt); assertNull(t.overdueNotifiedAt)
        val s = runBlocking { db.settingsDao().get()!! }
        assertEquals(30000L, s.dailyBudgetMinor); assertEquals(240, s.resetMinuteOfDay)
        assertEquals(20, s.taskReminderIntervalMin); assertEquals(30, s.firstReminderMin)
        db.close(); ctx.deleteDatabase(dbName)
    }
}
