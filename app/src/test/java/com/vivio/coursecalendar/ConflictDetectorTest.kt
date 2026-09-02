package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.import.ConflictDetector
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDetectorTest {

    private fun event(title: String, source: EventSource, start: String, end: String) = UnifiedEvent(
        source = source,
        title = title,
        startTime = LocalDateTime.parse(start),
        endTime = LocalDateTime.parse(end),
        identityKey = title
    )

    @Test
    fun `重叠事件标记冲突`() {
        val events = listOf(
            event("校内课", EventSource.UNIVERSITY, "2024-03-01T08:00", "2024-03-01T09:40"),
            event("兼职课", EventSource.PART_TIME, "2024-03-01T09:00", "2024-03-01T09:45")
        )
        val result = ConflictDetector.detect(events)
        assertTrue(result.containsKey("校内课"))
        assertTrue(result.containsKey("兼职课"))
        assertTrue(result["校内课"]!!.conflictWith.isNotEmpty())
    }

    @Test
    fun `不重叠不标记`() {
        val events = listOf(
            event("课1", EventSource.UNIVERSITY, "2024-03-01T08:00", "2024-03-01T09:40"),
            event("课2", EventSource.UNIVERSITY, "2024-03-01T10:10", "2024-03-01T11:50")
        )
        val result = ConflictDetector.detect(events)
        assertEquals(0, result.size)
    }

    @Test
    fun `同一天同一大节多课程标记冲突`() {
        val events = listOf(
            event("高等数学", EventSource.UNIVERSITY, "2024-03-01T08:00", "2024-03-01T09:40"),
            event("大学物理", EventSource.UNIVERSITY, "2024-03-01T08:00", "2024-03-01T09:40")
        )
        val result = ConflictDetector.detect(events)
        assertEquals(2, result.size)
    }

    @Test
    fun `blocker事件不参与冲突`() {
        val a = event("课A", EventSource.UNIVERSITY, "2024-03-01T08:00", "2024-03-01T09:40").copy(blocker = "缺年份")
        val b = event("课B", EventSource.UNIVERSITY, "2024-03-01T08:30", "2024-03-01T09:00")
        val result = ConflictDetector.detect(listOf(a, b))
        assertEquals(0, result.size)
    }
}
