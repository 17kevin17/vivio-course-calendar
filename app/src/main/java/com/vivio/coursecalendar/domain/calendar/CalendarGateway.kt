package com.vivio.coursecalendar.domain.calendar

import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent

/** 系统日历事件快照：用于恢复时核对真实状态（v2 F5）。 */
data class CalendarEventSnapshot(
    val calendarEventId: Long,
    val title: String?,
    val startMillis: Long,
    val endMillis: Long,
    val eventTimezone: String?,
    /** 跨存储幂等标识（v2 R2）：写入 CalendarProvider 的同步字段 */
    val operationToken: String? = null,
    /** 按 token 查询命中多条（v2 R2）：调用方应停止自动处理，标记人工确认 */
    val ambiguousTokenMatch: Boolean = false
)

/**
 * 系统日历操作网关（交接包《04》：CalendarProvider 与 Room 解耦）。
 * 抽象后导入/撤销流程可注入 fake 进行自动化测试。
 */
interface CalendarGateway {
    /** 查找或创建对应来源的独立日历，返回日历 ID */
    fun ensureCalendar(source: EventSource): Long

    /**
     * 创建事件，返回系统日历事件 ID；失败返回 null。
     * @param operationToken 跨存储幂等标识（v2 R2）：写入同步字段，崩溃后可按 token 找回
     */
    fun insertEvent(source: EventSource, event: UnifiedEvent, operationToken: String? = null): Long?

    /** 更新事件（含时间、标题、地点、描述），并重建提醒 */
    fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean

    /** 删除事件（仅用于本应用有映射的事件） */
    fun deleteEvent(calendarEventId: Long): Boolean

    /** 系统事件是否存在（v2 F5：恢复时核对真实状态） */
    fun eventExists(calendarEventId: Long): Boolean

    /** 读取系统事件快照；不存在返回 null（v2 F5） */
    fun getEvent(calendarEventId: Long): CalendarEventSnapshot?

    /**
     * 按幂等标识查找系统事件（v2 R2）。
     * 用于 CREATE / 撤销 DELETE 重建后 ID 未落库时找回，避免重复创建。
     * @return null 表示未找到；存在重复 token 时由实现标记，调用方停止自动处理
     */
    fun findEventByOperationToken(token: String): CalendarEventSnapshot?
}
