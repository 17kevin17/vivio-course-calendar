package com.vivio.coursecalendar.domain.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime
import java.time.ZoneId

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

    /** 创建事件，返回系统日历事件 ID；失败返回 null。 */
    override fun insertEvent(source: EventSource, event: UnifiedEvent): Long? {
        val calendarId = ensureCalendar(source)
        if (calendarId < 0) return null

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, millis(event.startTime))
            put(CalendarContract.Events.DTEND, millis(event.endTime))
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
        }
        val eventId = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?.lastPathSegment?.toLongOrNull()
            ?: return null

        event.reminderMinutes?.let { addReminder(eventId, it) }
        return eventId
    }

    /** 更新事件（含时间、标题、地点、描述），并重建提醒。 */
    override fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean {
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, millis(event.startTime))
            put(CalendarContract.Events.DTEND, millis(event.endTime))
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.EVENT_END_TIMEZONE, ZoneId.systemDefault().id)
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
        }
        return updated
    }

    /** 删除事件（仅用于本应用有映射的事件）。 */
    override fun deleteEvent(calendarEventId: Long): Boolean {
        removeReminders(calendarEventId)
        return resolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(calendarEventId.toString())
        ) > 0
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
