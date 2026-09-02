package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 批次操作动作类型 */
object BatchActionType {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val NOOP = "NOOP"
    const val MARK_MISSING = "MARK_MISSING"
}

/** 批次操作状态 */
object BatchActionState {
    const val PLANNED = "PLANNED"
    const val CALENDAR_APPLIED = "CALENDAR_APPLIED"
    const val DB_APPLIED = "DB_APPLIED"
    const val FAILED = "FAILED"
    const val REVERTED = "REVERTED"
    const val REVERT_FAILED = "REVERT_FAILED"
}

/**
 * 批次操作日志（交接包《02》第四节）：用于恢复和撤销。
 * 每条 CREATE/UPDATE/DELETE 可追溯，先记录操作意图再修改系统日历。
 */
@Entity(
    tableName = "batch_event_action",
    indices = [
        Index(value = ["batchId", "identityKey"], unique = true),
        Index(value = ["batchId", "state"]),
        Index(value = ["operationToken"], unique = true)
    ]
)
data class BatchEventActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    /** 逻辑事件 ID，可在创建前为空 */
    val managedEventId: Long?,
    /** 即使映射未创建也可定位 */
    val identityKey: String,
    /** CREATE / UPDATE / DELETE / NOOP / MARK_MISSING */
    val actionType: String,
    /** 操作前 JSON 快照，可空 */
    val beforeSnapshot: String?,
    /** 目标 JSON 快照，可空 */
    val afterSnapshot: String?,
    val calendarEventIdBefore: Long?,
    val calendarEventIdAfter: Long?,
    /** PLANNED / CALENDAR_APPLIED / DB_APPLIED / FAILED / REVERTED */
    val state: String,
    /** 结构化错误码 */
    val errorCode: String?,
    /** 跨存储幂等标识（v2 R2）：调用 CalendarProvider 前写入，用于崩溃后按 token 找回 */
    val operationToken: String? = null
)
