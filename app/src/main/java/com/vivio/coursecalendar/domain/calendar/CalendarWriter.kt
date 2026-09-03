package com.vivio.coursecalendar.domain.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime

/**
 * 系统日历写入（交接包《04》第五、六节）。
 *
 * 创建两个独立日历：校内课程 / 兼职课程；事件按统一事件模型映射。
 * 只更新或删除本应用记录过映射关系的事件；不依赖隐藏 vivo API。
 */
class CalendarWriter(private val context: Context) : CalendarGateway {

    private val resolver = context.contentResolver

    private val accountName = "com.vivio.coursecalendar"

    fun calendarDisplayName(source: EventSource): String = when (source) {
        EventSource.UNIVERSITY -> "校内课程"
        EventSource.PART_TIME -> "兼职课程"
    }

    private fun calendarColor(source: EventSource): Int = when (source) {
        EventSource.UNIVERSITY -> 0xFF1B6FE0.toInt()
        EventSource.PART_TIME -> 0xFFE8871E.toInt()
    }

    /** 查找或创建对应来源的独立日历。 */
    override fun ensureCalendar(source: EventSource): Long {
        val displayName = calendarDisplayName(source)
        findCalendar(displayName)?.let { return it }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, calendarColor(source))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, CourseTime.ZONE.id)
            put(CalendarContract.Calendars.SYNC_EVENTS, 0)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        return resolver.insert(uri, values)?.lastPathSegment?.toLongOrNull() ?: -1L
    }

    private fun findCalendar(displayName: String): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} = ?"
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            arrayOf(accountName, displayName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    /** 创建事件，返回系统日历事件 ID；失败返回 null。operationToken 写入同步字段 SYNC_DATA1（v2 R2）。 */
    override fun insertEvent(source: EventSource, event: UnifiedEvent, operationToken: String?): Long? {
        val calendarId = ensureCalendar(source)
        if (calendarId < 0) return null

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, millis(event.startTime))
            put(CalendarContract.Events.DTEND, millis(event.endTime))
            // v2 F8：DTSTART/DTEND 解释与 EVENT_TIMEZONE 统一使用 Asia/Shanghai，避免随系统时区漂移
            put(CalendarContract.Events.EVENT_TIMEZONE, CourseTime.ZONE.id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, CourseTime.ZONE.id)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            // v2 R2：跨存储幂等标识（需 vivo 真机验证 CalendarProvider 是否保留同步字段）
            if (operationToken != null) put(CalendarContract.Events.SYNC_DATA1, operationToken)
        }
        val eventId = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?.lastPathSegment?.toLongOrNull()
            ?: return null

        event.reminderMinutes?.let { addReminder(eventId, it) }
        // U3：插入后回读核验提醒；未同步时返回 null（事件已创建，调用方可用 token 找回，不重复创建）
        if (event.reminderMinutes != null && readReminderMinutes(eventId) != event.reminderMinutes) {
            return null
        }
        return eventId
    }

    /** 更新事件（含时间、标题、地点、描述），并重建提醒；提醒回读核验（N9）。 */
    override fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, millis(event.startTime))
            put(CalendarContract.Events.DTEND, millis(event.endTime))
            put(CalendarContract.Events.EVENT_TIMEZONE, CourseTime.ZONE.id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, CourseTime.ZONE.id)
        }
        val updated = resolver.update(
            CalendarContract.Events.CONTENT_URI,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(calendarEventId.toString())
        ) > 0
        if (updated) {
            removeReminders(calendarEventId)
            event.reminderMinutes?.let { addReminder(calendarEventId, it) }
            // N9：回读核验提醒已同步；不一致视为整体更新未完成（调用方可判定 FAILED）
            if (readReminderMinutes(calendarEventId) != event.reminderMinutes) return false
        }
        return updated
    }

    /** 删除事件（仅用于本应用有映射的事件）。N10：不提前破坏提醒，依赖 CalendarProvider 级联删除。 */
    override fun deleteEvent(calendarEventId: Long): Boolean {
        return resolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(calendarEventId.toString())
        ) > 0
    }

    /** 系统事件是否存在（v2 F5 恢复核对）。 */
    override fun eventExists(calendarEventId: Long): Boolean {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events._ID} = ?",
            arrayOf(calendarEventId.toString()),
            null
        )?.use { return it.count > 0 }
        return false
    }

    /** 读取系统事件快照（v2 F5 恢复核对 / N2 完整可见字段 + 提醒）。 */
    override fun getEvent(calendarEventId: Long): CalendarEventSnapshot? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(calendarEventId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return CalendarEventSnapshot(
                    calendarEventId = cursor.getLong(0),
                    title = cursor.getString(1),
                    startMillis = cursor.getLong(2),
                    endMillis = cursor.getLong(3),
                    eventTimezone = cursor.getString(4),
                    operationToken = cursor.getString(5),
                    location = cursor.getString(6),
                    description = cursor.getString(7),
                    reminderMinutes = readReminderMinutes(calendarEventId)
                )
            }
        }
        return null
    }

    /** 按幂等标识查找系统事件（v2 R2 / N11 限定本应用日历）；命中多条时标记歧义。 */
    override fun findEventByOperationToken(token: String): CalendarEventSnapshot? {
        if (token.isBlank()) return null
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        // N11：查询限定本应用日历，避免扫描其他账户的同步字段
        val appCalendarIds = listOf(ensureCalendar(EventSource.UNIVERSITY), ensureCalendar(EventSource.PART_TIME))
            .filter { it > 0 }.joinToString(",")
        val selection = if (appCalendarIds.isNotEmpty()) {
            "${CalendarContract.Events.SYNC_DATA1} = ? AND ${CalendarContract.Events.CALENDAR_ID} IN ($appCalendarIds)"
        } else {
            "${CalendarContract.Events.SYNC_DATA1} = ?"
        }
        val matches = mutableListOf<CalendarEventSnapshot>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            arrayOf(token),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                matches += CalendarEventSnapshot(
                    calendarEventId = cursor.getLong(0),
                    title = cursor.getString(1),
                    startMillis = cursor.getLong(2),
                    endMillis = cursor.getLong(3),
                    eventTimezone = cursor.getString(4),
                    operationToken = cursor.getString(5),
                    location = cursor.getString(6),
                    description = cursor.getString(7),
                    reminderMinutes = readReminderMinutes(cursor.getLong(0))
                )
            }
        }
        if (matches.isEmpty()) return null
        return if (matches.size > 1) matches[0].copy(ambiguousTokenMatch = true) else matches[0]
    }

    /** 读取事件首个提醒分钟数（N9：提醒成为可核验状态）。 */
    private fun readReminderMinutes(eventId: Long): Int? {
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return null
    }

    private fun addReminder(eventId: Long, minutes: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
    }

    private fun removeReminders(eventId: Long) {
        resolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
    }

    private fun millis(t: java.time.LocalDateTime): Long = CourseTime.toMillis(t)
}
