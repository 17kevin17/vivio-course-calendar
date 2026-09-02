package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** managed_event 生命周期状态 */
object ManagedStatus {
    const val ACTIVE = "ACTIVE"
    const val CANCELLED = "CANCELLED"
    const val MISSING = "MISSING"
    const val BROKEN = "BROKEN"
}

/**
 * 长期事件映射（交接包《02》第二节）：应用当前管理的逻辑事件，与导入批次解耦。
 * 一个逻辑事件（source + identityKey）只有一个当前映射。
 */
@Entity(
    tableName = "managed_event",
    indices = [
        Index(value = ["source", "identityKey"], unique = true),
        Index("calendarEventId"),
        Index("lastSeenBatchId")
    ]
)
data class ManagedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    /** 跨批次稳定身份 */
    val identityKey: String,
    /** 当前内容哈希 */
    val contentHash: String,
    val sourceRecordId: String?,
    /** 当前系统日历事件 ID；可能为 null（如已取消） */
    val calendarEventId: Long?,
    val title: String,
    val location: String?,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    /** 当前提醒（分钟，v2 F7：纳入最终哈希与快照，撤销可恢复） */
    val reminderMinutes: Int? = null,
    /** ACTIVE / CANCELLED / MISSING / BROKEN */
    val status: String,
    val lastSeenBatchId: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
