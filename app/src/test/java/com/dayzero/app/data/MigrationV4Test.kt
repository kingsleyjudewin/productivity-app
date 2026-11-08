package com.dayzero.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayzero.app.data.local.DayZeroDatabase
import com.dayzero.app.domain.TransactionSource
import com.dayzero.app.domain.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class MigrationV4Test {
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

    private fun seedMoney(raw: SQLiteDatabase) {
        raw.execSQL("INSERT INTO transactions (id,amountMinor,type,merchant,timestamp,dayKey,sourcePackage,source,note,dedupeKey) VALUES (1,25000,'SPENT','Swiggy',1000,'2026-11-07','com.phonepe.app','AUTO',NULL,'k1')")
        raw.execSQL("INSERT INTO transactions (id,amountMinor,type,merchant,timestamp,dayKey,sourcePackage,source,note,dedupeKey) VALUES (2,50000,'RECEIVED','Friend',2000,'2026-11-07',NULL,'MANUAL','keep',NULL)")
        raw.execSQL("INSERT INTO transactions (id,amountMinor,type,merchant,timestamp,dayKey,sourcePackage,source,note,dedupeKey) VALUES (3,900,'REFUND','Shop',3000,'2026-11-07',NULL,'MANUAL',NULL,NULL)")
        raw.execSQL("INSERT INTO transactions (id,amountMinor,type,merchant,timestamp,dayKey,sourcePackage,source,note,dedupeKey) VALUES (4,100,'UNKNOWN',NULL,4000,'2026-11-07',NULL,'AUTO',NULL,NULL)")
        raw.execSQL("INSERT INTO budgets (dayKey,limitMinor,currency) VALUES ('2026-11-05',30000,'INR')")
    }

    private fun check(db: DayZeroDatabase) = runBlocking {
        val t = db.transactionDao()
        val a = t.get(1)!!
        assertEquals(TransactionType.EXPENSE, a.type); assertEquals(25000L, a.amountMinor); assertEquals("Swiggy", a.merchant)
        assertEquals("k1", a.dedupeKey); assertEquals(TransactionSource.AUTO, a.source)
        assertNull(a.category); assertNull(a.txnRef); assertNull(a.originalAmountMinor); assertFalse(a.userEdited)
        assertEquals(TransactionType.INCOME, t.get(2)!!.type); assertEquals("keep", t.get(2)!!.note)
        assertEquals(TransactionType.REFUND, t.get(3)!!.type); assertEquals(TransactionType.UNKNOWN, t.get(4)!!.type)
        assertEquals(25000L, t.spentFor("2026-11-07"))                 // old SPENT rows still count as spending
        assertEquals(30000L, db.budgetDao().get("2026-11-05")!!.limitMinor)
        assertNull(db.budgetDao().get("2026-11-05")!!.exceededNotifiedAt)
        // New things work.
        assertEquals(-1L, t.insert(a.copy(id = 0)))                    // unique dedupeKey still enforced
        db.notificationSeenDao().insert(com.dayzero.app.data.local.NotificationSeenEntity("fp", 1))
        assertEquals(-1L, db.notificationSeenDao().insert(com.dayzero.app.data.local.NotificationSeenEntity("fp", 2)))
        assertNotNull(db.openHelper.readableDatabase)
    }

    @Test fun v3_to_v4_keepsMoneyData_andConvertsTypes() {
        val raw = legacyDb("m34.db", 3); seedMoney(raw)
        raw.execSQL("INSERT INTO user_settings (id,dailyBudgetMinor,currency,resetMinuteOfDay,defaultLimitMinutes,taskReminderIntervalMin,firstReminderMin,persistentTaskNotification,notificationsEnabled,overlayInterventions,interventionCooldownMin,dailyScreenLimitMinutes,monitoringEnabled) VALUES (1,40000,'INR',120,45,20,40,0,1,0,15,90,1)")
        raw.execSQL("INSERT INTO tasks (id,name,priority,category,startTime,deadline,originalDeadline,estimatedMinutes,status,completedAt,createdAt,lastCheckInAt,reminderCount,nextAlertAt,overdueNotifiedAt) VALUES (1,'Task stays','HIGH',NULL,1,2,2,10,'NOT_STARTED',NULL,1,NULL,0,NULL,NULL)")
        raw.close()
        val db = open("m34.db"); check(db)
        runBlocking {
            assertEquals("Task stays", db.taskDao().get(1)!!.name)
            val s = db.settingsDao().get()!!; assertEquals(40000L, s.dailyBudgetMinor); assertEquals(90, s.dailyScreenLimitMinutes)
            // The old single global budget became a day-zero budget so no day lost its limit.
            assertEquals(40000L, db.budgetDao().limitFor("2026-11-04"))     // before any per-day row: the old global budget
            assertEquals(30000L, db.budgetDao().limitFor("2026-11-05"))     // an existing per-day row wins on its day...
            assertEquals(30000L, db.budgetDao().limitFor("2026-11-06"))     // ...and carries forward after it
            assertEquals(40000L, db.budgetDao().limitFor("2026-01-01"))
        }
        db.close(); ctx.deleteDatabase("m34.db")
    }

    @Test fun v2_to_v4() {
        val raw = legacyDb("m24.db", 2); seedMoney(raw)
        raw.execSQL("INSERT INTO usage_limits (packageName,appLabel,limitMinutes,enabled) VALUES ('com.instagram.android','Instagram',30,1)")
        raw.close()
        val db = open("m24.db"); check(db)
        assertEquals(1, runBlocking { db.usageLimitDao().getEnabled() }.size)
        db.close(); ctx.deleteDatabase("m24.db")
    }

    @Test fun v1_to_v4_fullChain() {
        val raw = legacyDb("m14.db", 1); seedMoney(raw)
        raw.execSQL("INSERT INTO tasks (id,name,priority,category,startTime,deadline,originalDeadline,estimatedMinutes,status,completedAt,createdAt,lastCheckInAt) VALUES (1,'Old','LOW',NULL,1,2,2,10,'DONE',2,1,NULL)")
        raw.close()
        val db = open("m14.db"); check(db)
        assertEquals("Old", runBlocking { db.taskDao().get(1)!!.name })
        db.close(); ctx.deleteDatabase("m14.db")
    }

    @Test fun freshInstall_hasNoBudgetRowAndWorks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, DayZeroDatabase::class.java).allowMainThreadQueries().build()
        assertNull(db.budgetDao().limitFor("2026-11-08"))
        db.close()
    }
}
