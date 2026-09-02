package com.vivio.coursecalendar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vivio.coursecalendar.data.local.entity.BatchEventActionEntity
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.local.entity.ScheduleConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleConfigDao {
    @Query("SELECT * FROM schedule_config WHERE season = :season ORDER BY periodNumber")
    fun observeBySeason(season: String): Flow<List<ScheduleConfigEntity>>

    @Query("SELECT * FROM schedule_config WHERE season = :season ORDER BY periodNumber")
    suspend fun getBySeason(season: String): List<ScheduleConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ScheduleConfigEntity>)

    @Query("DELETE FROM schedule_config")
    suspend fun clear()
}

@Dao
interface ImportBatchDao {
    @Insert
    suspend fun insert(batch: ImportBatchEntity): Long

    @Update
    suspend fun update(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batch ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batch WHERE id = :id")
    suspend fun getById(id: Long): ImportBatchEntity?

    @Query("SELECT * FROM import_batch WHERE fileHash = :fileHash ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestByFileHash(fileHash: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch WHERE phase = :phase ORDER BY createdAt")
    suspend fun getByPhase(phase: String): List<ImportBatchEntity>
}

@Dao
interface ManagedEventDao {
    // v2 F9：不用 REPLACE——冲突时 REPLACE 会删旧行重建（新主键），破坏 batch_event_action.managedEventId 引用。
    // 明确区分：insert（默认 ABORT，NEW 事件不应冲突）/ update（同主键更新）/ upsert（按 source+identityKey 先查后写，见 ImportManager）。
    @Insert
    suspend fun insert(event: ManagedEventEntity): Long

    @Update
    suspend fun update(event: ManagedEventEntity)

    @Query("SELECT * FROM managed_event WHERE source = :source AND identityKey = :identityKey LIMIT 1")
    suspend fun getByIdentity(source: String, identityKey: String): ManagedEventEntity?

    @Query("SELECT * FROM managed_event WHERE source = :source AND status != 'BROKEN'")
    suspend fun getActiveBySource(source: String): List<ManagedEventEntity>

    @Query("SELECT * FROM managed_event")
    suspend fun getAll(): List<ManagedEventEntity>

    @Query("SELECT * FROM managed_event WHERE calendarEventId = :calendarEventId")
    suspend fun getByCalendarEventId(calendarEventId: Long): ManagedEventEntity?

    @Query("SELECT * FROM managed_event WHERE id = :id")
    suspend fun getById(id: Long): ManagedEventEntity?

    @Query("SELECT * FROM managed_event WHERE lastSeenBatchId = :batchId")
    suspend fun getByBatch(batchId: Long): List<ManagedEventEntity>

    @Delete
    suspend fun delete(event: ManagedEventEntity)
}

@Dao
interface BatchEventActionDao {
    @Insert
    suspend fun insertAll(actions: List<BatchEventActionEntity>): LongArray

    @Update
    suspend fun update(action: BatchEventActionEntity)

    @Query("SELECT * FROM batch_event_action WHERE batchId = :batchId ORDER BY id")
    suspend fun getByBatch(batchId: Long): List<BatchEventActionEntity>

    @Query("SELECT * FROM batch_event_action WHERE batchId = :batchId AND state = :state")
    suspend fun getByBatchAndState(batchId: Long, state: String): List<BatchEventActionEntity>

    @Query("SELECT * FROM batch_event_action WHERE state IN (:states) ORDER BY id")
    suspend fun getByStates(states: List<String>): List<BatchEventActionEntity>
}
