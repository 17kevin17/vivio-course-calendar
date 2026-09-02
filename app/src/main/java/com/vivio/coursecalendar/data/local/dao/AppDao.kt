package com.vivio.coursecalendar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vivio.coursecalendar.data.local.entity.EventMappingEntity
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
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
}

@Dao
interface EventMappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<EventMappingEntity>)

    @Query("SELECT * FROM event_mapping WHERE batchId = :batchId")
    fun observeByBatch(batchId: Long): Flow<List<EventMappingEntity>>

    @Query("SELECT * FROM event_mapping WHERE batchId = :batchId")
    suspend fun getByBatch(batchId: Long): List<EventMappingEntity>

    @Query("SELECT * FROM event_mapping WHERE eventFingerprint = :fingerprint ORDER BY batchId DESC")
    suspend fun getByFingerprint(fingerprint: String): List<EventMappingEntity>

    @Query("SELECT * FROM event_mapping WHERE batchId = :batchId AND eventFingerprint = :fingerprint")
    suspend fun getByBatchAndFingerprint(batchId: Long, fingerprint: String): EventMappingEntity?

    @Query("SELECT * FROM event_mapping")
    suspend fun getAll(): List<EventMappingEntity>

    @Query("SELECT * FROM event_mapping WHERE batchId = :batchId AND excluded = 0")
    suspend fun getActiveByBatch(batchId: Long): List<EventMappingEntity>

    @Delete
    suspend fun delete(mapping: EventMappingEntity)

    @Query("DELETE FROM event_mapping WHERE batchId = :batchId")
    suspend fun deleteByBatch(batchId: Long)
}
