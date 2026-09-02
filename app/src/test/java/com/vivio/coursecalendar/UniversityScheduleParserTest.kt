package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.parser.ParseContext
import com.vivio.coursecalendar.domain.parser.ParseResult
import com.vivio.coursecalendar.domain.parser.ScheduleTable
import com.vivio.coursecalendar.domain.parser.UniversityScheduleParser
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.Season
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversityScheduleParserTest {

    private val summerTable = ScheduleTable(
        Season.SUMMER,
        DefaultSchedule.summer.associateBy { it.number }
    )

    private fun buildGrid(): HSSFWorkbook {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("课表")
        // 区块列头：日期（带年份）
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("日期")
        header.createCell(1).setCellValue("2024/3/1")
        header.createCell(2).setCellValue("2024/3/2")
        header.createCell(3).setCellValue("2024/3/3")
        // 第一大节
        val row1 = sheet.createRow(1)
        row1.createCell(0).setCellValue("第一大节")
        row1.createCell(1).setCellValue("高等数学\n张三\n1-0102\nA101")
        row1.createCell(2).setCellValue("")
        row1.createCell(3).setCellValue("大学物理\n李四、王五\n1-0304\nB-202")
        // 第二大节
        val row2 = sheet.createRow(2)
        row2.createCell(0).setCellValue("第二大节")
        row2.createCell(1).setCellValue("")
        row2.createCell(2).setCellValue("线性代数\n赵六\n1-0202\n7-101")
        return wb
    }

    @Test
    fun `解析校内网格课表`() {
        val wb = buildGrid()
        val result = UniversityScheduleParser().parse(wb, ParseContext("hash-1", Season.SUMMER, summerTable))
        assertTrue(result is ParseResult.Success)
        val events = (result as ParseResult.Success).events

        // 3/1 高等数学 第1大节
        val math = events.first { it.title == "高等数学" }
        assertEquals("2024-03-01T08:00", math.startTime.toString())
        assertEquals("2024-03-01T09:40", math.endTime.toString())
        assertEquals(1, math.periodIndex)
        assertEquals("A101", math.location)
        assertEquals("1-0102", math.periodCode)
        assertNull(math.blocker)
        assertTrue(math.rawText!!.contains("张三"))

        // 3/3 大学物理 第1大节（多教师）
        val physics = events.first { it.title == "大学物理" }
        assertTrue(physics.description!!.contains("李四"))
        assertTrue(physics.description!!.contains("王五"))
        assertEquals("B-202", physics.location)

        // 3/2 线性代数 第2大节 → 10:10 开始
        val linear = events.first { it.title == "线性代数" }
        assertEquals("2024-03-02T10:10", linear.startTime.toString())
        assertEquals("2024-03-02T11:50", linear.endTime.toString())
    }

    @Test
    fun `无年份日期标记为待确认`() {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("课表")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("日期")
        header.createCell(1).setCellValue("3/1")
        header.createCell(2).setCellValue("3/2")
        val row1 = sheet.createRow(1)
        row1.createCell(0).setCellValue("第1大节")
        row1.createCell(1).setCellValue("英语\n王老师\nA101")

        val result = UniversityScheduleParser().parse(wb, ParseContext("h", Season.SUMMER, summerTable))
        val events = (result as ParseResult.Success).events
        assertEquals(1, events.size)
        assertNotNull(events[0].blocker)
        assertTrue(events[0].blocker!!.contains("年份"))
    }

    @Test
    fun `作息未配置的大节被跳过并告警`() {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("课表")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("日期")
        header.createCell(1).setCellValue("2024/3/1")
        header.createCell(2).setCellValue("2024/3/2")
        val row1 = sheet.createRow(1)
        row1.createCell(0).setCellValue("第3大节")
        row1.createCell(1).setCellValue("体育\n刘老师\n操场")
        val row2 = sheet.createRow(2)
        row2.createCell(0).setCellValue("第1大节")
        row2.createCell(1).setCellValue("高数\nA101")

        val springTable = ScheduleTable(
            Season.SPRING,
            DefaultSchedule.spring.filterNotNull().associateBy { it.number }
        )
        val result = UniversityScheduleParser().parse(wb, ParseContext("h", Season.SPRING, springTable))
        val success = result as ParseResult.Success
        // 第3大节春季未配置 → 体育被跳过；第1大节正常
        assertTrue(success.events.none { it.title == "体育" })
        assertEquals(1, success.events.size)
        assertEquals("高数", success.events[0].title)
        assertTrue(success.warnings.any { it.contains("第3大节") })
    }

    @Test
    fun `合并单元格日期可识别`() {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("课表")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("日期")
        header.createCell(1).setCellValue("2024/9/2")
        header.createCell(2).setCellValue("2024/9/3")
        // 合并 A1:B1 之类不影响
        val row1 = sheet.createRow(1)
        row1.createCell(0).setCellValue("第一大节")
        row1.createCell(1).setCellValue("课程A\nA101")
        val result = UniversityScheduleParser().parse(wb, ParseContext("h", Season.SUMMER, summerTable))
        assertEquals(1, (result as ParseResult.Success).events.size)
    }
}
