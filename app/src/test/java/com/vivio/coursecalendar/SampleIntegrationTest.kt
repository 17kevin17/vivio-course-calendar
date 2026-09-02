package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.import.EventFingerprint
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.parser.ExcelIO
import com.vivio.coursecalendar.domain.parser.FormatDetector
import com.vivio.coursecalendar.domain.parser.ParseContext
import com.vivio.coursecalendar.domain.parser.ParseResult
import com.vivio.coursecalendar.domain.parser.PartTimeScheduleParser
import com.vivio.coursecalendar.domain.parser.ScheduleTable
import com.vivio.coursecalendar.domain.parser.UniversityScheduleParser
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.Season
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实样表集成测试：直接读取 e:\vivio 下的两份样表验证解析规则。
 * 这是「两份提供的样表均可完成解析预览」验收门槛的自动化回归。
 */
class SampleIntegrationTest {

    private fun sample(name: String): File = File("e:\\vivio", name)

    @Test
    fun `校内样表可识别并完整解析`() {
        val file = sample("download (5).xls")
        assertTrue("样表不存在：${file.absolutePath}", file.exists())
        val bytes = file.readBytes()
        val wb = ExcelIO.openSafely(bytes.inputStream(), bytes)
        assertNotNull("校内样表无法打开", wb)

        val detected = FormatDetector.detect(wb!!)!!.source
        assertEquals(EventSource.UNIVERSITY, detected)

        val table = ScheduleTable(Season.SUMMER, DefaultSchedule.summer.associateBy { it.number })
        val result = UniversityScheduleParser().parse(wb, ParseContext("it-hash", Season.SUMMER, table))
        wb.close()
        assertTrue("解析失败: ${(result as? ParseResult.Failure)?.message}", result is ParseResult.Success)
        val events = (result as ParseResult.Success).events
        println("校内样表 → ${events.size} 个事件")

        assertTrue("事件数应较多，实际 ${events.size}", events.size > 50)

        // 学年推断：日期无年份，但标题含 2026-2027 → 月份>=9 用 2026，否则 2027
        assertTrue(events.all { it.startTime.year == 2026 || it.startTime.year == 2027 })
        assertTrue("年份推断后不应有 blocker", events.all { it.blocker == null })

        // 教室识别（中文楼名）
        assertTrue("教室未全部识别", events.all { it.location != null })
        assertEquals("求是西楼102", events.first { it.title.startsWith("单片机") }.location)
        assertEquals("西校区新联楼0103", events.first { it.title.startsWith("电磁场") }.location)

        // 每个大节 = 100 分钟日历事件
        val durations = events.map { Duration.between(it.startTime, it.endTime).toMinutes() }.toSet()
        assertEquals(setOf(100L), durations)

        // 周次码与节次
        val code = events.first { it.title.startsWith("单片机") }.periodCode
        assertEquals("1-0102", code)
    }

    @Test
    fun `兼职样表可识别并完整解析`() {
        val file = sample("download (6).xls")
        assertTrue("样表不存在：${file.absolutePath}", file.exists())
        val bytes = file.readBytes()
        val wb = ExcelIO.openSafely(bytes.inputStream(), bytes)
        assertNotNull("兼职样表无法打开", wb)

        val detected = FormatDetector.detect(wb!!)!!.source
        assertEquals(EventSource.PART_TIME, detected)

        val result = PartTimeScheduleParser().parse(wb, ParseContext("it-hash"))
        wb.close()
        assertTrue("解析失败: ${(result as? ParseResult.Failure)?.message}", result is ParseResult.Success)
        val events = (result as ParseResult.Success).events
        println("兼职样表 → ${events.size} 个事件")

        assertTrue("兼职样表应有 14 行记录，实际 ${events.size}", events.size >= 14)

        // 课节 ID（课节 number 列）→ sourceRecordId，且指纹按 ID
        val first = events.first()
        assertNotNull("课节ID未识别", first.sourceRecordId)
        assertEquals(EventFingerprint.partTimeWithId(first.sourceRecordId!!), first.eventFingerprint)

        // 待上课：起止时间从「上课时间」范围拆分
        val pending = events.first { it.status == CourseStatus.PENDING }
        assertEquals(LocalDateTime.of(2026, 9, 3, 20, 0), pending.startTime)
        assertEquals(LocalDateTime.of(2026, 9, 3, 20, 30), pending.endTime)

        // 已结课作为历史保留
        assertTrue(events.any { it.status == CourseStatus.COMPLETED })

        // 隐私：描述与原始文本不含内部 ID（学员ID/主讲ID/班级ID 等）
        assertTrue(events.all { !(it.description?.contains("ID") ?: false) })
        assertTrue(events.all { !(it.rawText?.contains("ID") ?: false) })

        // 学员姓名进入描述
        assertTrue(events.all { it.description!!.contains("学员") })
    }
}
