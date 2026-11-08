package com.dayzero.app.data.repo

import com.dayzero.app.data.local.AppUsageDao
import com.dayzero.app.data.local.SettingsDao
import com.dayzero.app.data.local.UserSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dao: SettingsDao) {
    /** Emits defaults until the user changes something, so callers never see null. */
    val settings: Flow<UserSettingsEntity> = dao.observe().map { it ?: UserSettingsEntity() }
    suspend fun current(): UserSettingsEntity = dao.get() ?: UserSettingsEntity()
    suspend fun update(transform: (UserSettingsEntity) -> UserSettingsEntity) =
        dao.save(transform(current()))
}

@Singleton
class UsageRepository @Inject constructor(private val dao: AppUsageDao) {
    /** Sum of monitored apps' foreground time. */
    fun observeTotalMs(dayKey: String): Flow<Long> = dao.observeTotalMs(dayKey)
    /** Whole-device screen-on time. */
    fun observeTotalScreenMs(dayKey: String): Flow<Long> = dao.observeTotalScreenMs(dayKey)
    fun observeForDay(dayKey: String): Flow<List<com.dayzero.app.data.local.AppUsageEntity>> = dao.observeForDay(dayKey)
}

@Singleton
class UsageLimitRepository @Inject constructor(private val dao: com.dayzero.app.data.local.UsageLimitDao) {
    fun observeAll(): Flow<List<com.dayzero.app.data.local.UsageLimitEntity>> = dao.observeAll()
    suspend fun set(pkg: String, label: String, minutes: Int) =
        dao.upsert(com.dayzero.app.data.local.UsageLimitEntity(pkg, label, minutes.coerceIn(1, 1440), true))
    suspend fun remove(pkg: String) = dao.deleteByPackage(pkg)
}
