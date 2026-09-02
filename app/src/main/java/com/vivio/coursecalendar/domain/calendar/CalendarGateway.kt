package com.vivio.coursecalendar.domain.calendar

import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent

/** 系统日历事件快照：用于恢复时核对真实状态（v2 F5）。 */
data class CalendarEventSnapshot(
    val calendarEventId: Long,
    val title: String?,
    val startMillis: Long,
    val endMillis: Long,
    val eventTimezone: String?
)

/**
 * 系统日历操作网关（交接包《04》：CalendarProvider 与 Room 解耦）。
 * 抽象后导入/撤销流程可注入 fake 进行自动化测试。
 */
interface CalendarGateway {
    /** 查找或创建对应来源的独立日历，返回日历 ID */
    fun ensureCalendar(source: EventSource): Long

    /** 创建事件，返回系统日历事件 ID；失败返回 null */
    fun insertEvent(source: EventSource, event: UnifiedEvent): Long?

    /** 更新事件（含时间、标题、地点、描述），并重建提醒 */
    fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean

    /** 删除事件（仅用于本应用有映射的事件） */
    fun deleteEvent(calendarEventId: Long): Boolean

    /** 系统事件是否存在（v2 F5：恢复时核对真实状态） */
    fun eventExists(calendarEventId: Long): Boolean

    /** 读取系统事件快照；不存在返回 null（v2 F5） */
    fun getEvent(calendarEventId: Long): CalendarEventSnapshot?
}
