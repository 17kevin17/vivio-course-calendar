package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime
import java.time.LocalDateTime
import org.json.JSONObject

/**
 * 事件快照 JSON 序列化（交接包《02》batch_event_action 的 before/after 快照）。
 * 撤销 UPDATE 时用 beforeSnapshot 恢复原事件。
 */
object EventSnapshot {

    fun toJson(event: UnifiedEvent): String = JSONObject()
        .put("source", event.source.name)
        .put("identityKey", event.identityKey)
        .put("contentHash", event.contentHash)
        .put("sourceRecordId", event.sourceRecordId ?: JSONObject.NULL)
        .put("title", event.title)
        .put("location", event.location ?: JSONObject.NULL)
        .put("description", event.description ?: JSONObject.NULL)
        .put("startMillis", millis(event.startTime))
        .put("endMillis", millis(event.endTime))
        .put("status", event.status.name)
        .put("reminderMinutes", event.reminderMinutes ?: JSONObject.NULL)
        .put("calendarEventId", event.calendarEventId ?: JSONObject.NULL)
        .toString()

    fun fromJson(json: String?): UnifiedEvent? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            UnifiedEvent(
                source = EventSource.valueOf(o.optString("source", "UNIVERSITY")),
                sourceRecordId = o.optString("sourceRecordId").takeIf { it.isNotBlank() },
                title = o.optString("title"),
                location = o.optString("location").takeIf { it.isNotBlank() },
                description = o.optString("description").takeIf { it.isNotBlank() },
                startTime = fromMillis(o.optLong("startMillis")),
                endTime = fromMillis(o.optLong("endMillis")),
                status = runCatching { CourseStatus.valueOf(o.optString("status", "PENDING")) }.getOrDefault(CourseStatus.PENDING),
                reminderMinutes = if (o.has("reminderMinutes") && !o.isNull("reminderMinutes")) o.getInt("reminderMinutes") else null,
                identityKey = o.optString("identityKey"),
                contentHash = o.optString("contentHash"),
                calendarEventId = if (o.has("calendarEventId") && !o.isNull("calendarEventId")) o.getLong("calendarEventId") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun millis(t: LocalDateTime): Long = CourseTime.toMillis(t)

    private fun fromMillis(ms: Long): LocalDateTime = CourseTime.fromMillis(ms)
}
