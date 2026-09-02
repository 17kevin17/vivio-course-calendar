package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.import.DedupEngine
import com.vivio.coursecalendar.domain.import.ExistingMapping
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.test.runTest

class DedupEngineTest {

    private val store = mutableMapOf<String, MutableList<ExistingMapping>>()

    private fun engine() = DedupEngine { fp -> store[fp] ?: emptyList() }

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun mapping(fingerprint: String, title: String, start: LocalDateTime, end: LocalDateTime) =
        ExistingMapping(title, "A101", millis(start), millis(end))

    private fun event(fingerprint: String, title: String, start: LocalDateTime, end: LocalDateTime) = UnifiedEvent(
        source = EventSource.UNIVERSITY,
        title = title,
        location = "A101",
        startTime = start,
        endTime = end,
        eventFingerprint = fingerprint
    )

    @Test
    fun `内容一致标记为无变化`() = runTest {
        val start = LocalDateTime.of(2024, 3, 1, 8, 0)
        val end = LocalDateTime.of(2024, 3, 1, 9, 40)
        store["fp1"] = mutableListOf(mapping("fp1", "高数", start, end))

        val result = engine().evaluate(listOf(event("fp1", "高数", start, end)))
        assertEquals(EventState.UNCHANGED, result["fp1"])
    }

    @Test
    fun `时间变化标记为修改`() = runTest {
        val start = LocalDateTime.of(2024, 3, 1, 8, 0)
        val end = LocalDateTime.of(2024, 3, 1, 9, 40)
        store["fp1"] = mutableListOf(mapping("fp1", "高数", start, end))

        val newStart = start.plusDays(1)
        val result = engine().evaluate(listOf(event("fp1", "高数", newStart, end)))
        assertEquals(EventState.MODIFIED, result["fp1"])
    }

    @Test
    fun `标题变化标记为修改`() = runTest {
        val start = LocalDateTime.of(2024, 3, 1, 8, 0)
        val end = LocalDateTime.of(2024, 3, 1, 9, 40)
        store["fp1"] = mutableListOf(mapping("fp1", "高等数学", start, end))

        val result = engine().evaluate(listOf(event("fp1", "高等数学(上)", start, end)))
        assertEquals(EventState.MODIFIED, result["fp1"])
    }

    @Test
    fun `新指纹标记为新增`() = runTest {
        store["fp1"] = mutableListOf(mapping("fp1", "高数", LocalDateTime.of(2024, 3, 1, 8, 0), LocalDateTime.of(2024, 3, 1, 9, 40)))
        val result = engine().evaluate(listOf(event("fp2", "英语", LocalDateTime.of(2024, 3, 2, 8, 0), LocalDateTime.of(2024, 3, 2, 9, 40))))
        assertEquals(EventState.NEW, result["fp2"])
    }
}
