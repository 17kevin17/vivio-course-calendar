package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.calendar.CalendarEventSnapshot
import com.vivio.coursecalendar.domain.calendar.CalendarGateway
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime

/** 模拟进程中断：插入/删除成功后抛出，表示写 CalendarProvider 后、Room 写回前崩溃。 */
class SimulatedCrash : RuntimeException("simulated process crash")

/**
 * 崩溃边界测试用日历网关（v2 下一轮 R1-R6）。
 * 能力：
 * - 指定某次 insert/update/delete 失败（单次标志，触发后自动复位）；
 * - 调用成功后抛出模拟进程中断异常（crashAfterNextInsert/Delete）；
 * - 按 operation token 查询；可注入重复 token 结果（ambiguousTokens）；
 * - 检查事件真实存在；记录各操作调用次数。
 */
class FaultyCalendarGateway : CalendarGateway {
    val events = mutableMapOf<Long, UnifiedEvent>()
    val tokenByEvent = mutableMapOf<Long, String?>()
    private var nextId = 1L

    // 调用计数
    var insertCalls = 0
        private set
    var updateCalls = 0
        private set
    var deleteCalls = 0
        private set
    var findTokenCalls = 0
        private set

    // 单次失败注入
    var nextInsertShouldFail = false
    var nextUpdateShouldFail = false
    var nextDeleteShouldFail = false

    // 单次崩溃注入（成功后抛异常）
    var crashAfterNextInsert = false
    var crashAfterNextDelete = false

    // U3：单次提醒写入失败注入（insert 后事件 reminder 被清空，模拟 provider 未保存提醒）
    var dropReminderAfterNextInsert = false

    // U5：单次权限异常注入（抛 SecurityException，模拟日历权限被撤销）
    var throwSecurityOnNextInsert = false
    var throwSecurityOnNextUpdate = false
    var throwSecurityOnNextDelete = false

    // 视为重复 token 的集合：find 时标记歧义
    val ambiguousTokens = mutableSetOf<String>()

    override fun ensureCalendar(source: EventSource): Long = 1L

    override fun insertEvent(source: EventSource, event: UnifiedEvent, operationToken: String?): Long? {
        insertCalls++
        if (throwSecurityOnNextInsert) {
            throwSecurityOnNextInsert = false
            throw SecurityException("simulated calendar permission revoked")
        }
        if (nextInsertShouldFail) {
            nextInsertShouldFail = false
            return null
        }
        val id = nextId++
        var stored = event
        if (dropReminderAfterNextInsert) {
            dropReminderAfterNextInsert = false
            stored = event.copy(reminderMinutes = null)
        }
        events[id] = stored
        tokenByEvent[id] = operationToken
        if (crashAfterNextInsert) {
            crashAfterNextInsert = false
            throw SimulatedCrash()
        }
        return id
    }

    override fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean {
        updateCalls++
        if (throwSecurityOnNextUpdate) {
            throwSecurityOnNextUpdate = false
            throw SecurityException("simulated calendar permission revoked")
        }
        if (nextUpdateShouldFail) {
            nextUpdateShouldFail = false
            return false
        }
        if (!events.containsKey(calendarEventId)) return false
        events[calendarEventId] = event
        return true
    }

    override fun deleteEvent(calendarEventId: Long): Boolean {
        deleteCalls++
        if (throwSecurityOnNextDelete) {
            throwSecurityOnNextDelete = false
            throw SecurityException("simulated calendar permission revoked")
        }
        if (nextDeleteShouldFail) {
            nextDeleteShouldFail = false
            return false
        }
        val removed = events.remove(calendarEventId) != null
        if (removed) tokenByEvent.remove(calendarEventId)
        if (crashAfterNextDelete) {
            crashAfterNextDelete = false
            throw SimulatedCrash()
        }
        return removed
    }

    override fun eventExists(calendarEventId: Long): Boolean = events.containsKey(calendarEventId)

    override fun getEvent(calendarEventId: Long): CalendarEventSnapshot? {
        val e = events[calendarEventId] ?: return null
        return CalendarEventSnapshot(
            calendarEventId = calendarEventId,
            title = e.title,
            startMillis = CourseTime.toMillis(e.startTime),
            endMillis = CourseTime.toMillis(e.endTime),
            eventTimezone = null,
            operationToken = tokenByEvent[calendarEventId],
            location = e.location,
            description = e.description,
            reminderMinutes = e.reminderMinutes
        )
    }

    override fun findEventByOperationToken(token: String): CalendarEventSnapshot? {
        findTokenCalls++
        val ids = tokenByEvent.filterValues { it == token }.keys
        if (ids.isEmpty()) return null
        val first = getEvent(ids.first())!!
        return if (ids.size > 1 || token in ambiguousTokens) {
            first.copy(ambiguousTokenMatch = true)
        } else {
            first
        }
    }
}
