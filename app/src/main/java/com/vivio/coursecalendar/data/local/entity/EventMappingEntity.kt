package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件映射：本应用写入系统日历的事件记录。
 * 只更新/删除有映射的事件；撤销导入只删除对应批次的事件。
 */
@Entity(
    tableName = "event_mapping",
    indices = [
        Index("batchId"),
        Index(value = ["eventFingerprint", "batchId"], unique = true)
    ]
)
data class EventMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val source: String,
    val sourceRecordId: String?,
    val eventFingerprint: String,
    val calendarEventId: Long,
    val title: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val state: String,
    val excluded: Boolean = false
)
