package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.parser.ParseContext
import com.vivio.coursecalendar.domain.parser.ParseResult
import com.vivio.coursecalendar.domain.parser.PartTimeScheduleParser
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartTimeScheduleParserTest {

    private fun buildWorkbook(status: String): XSSFWorkbook {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("学员排课")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("课节ID")
        header.createCell(1).setCellValue("课节名称")
        header.createCell(2).setCellValue("学员")
        header.createCell(3).setCellValue("主讲")
        header.createCell(4).setCellValue("上课时间")
        header.createCell(5).setCellValue("结束时间")
        header.createCell(6).setCellValue("状态")

        val row = sheet.createRow(1)
        row.createCell(0).setCellValue("PT001")
        row.createCell(1).setCellValue("英语一对一")
        row.createCell(2).setCellValue("小明")
        row.createCell(3).setCellValue("李老师")
        row.createCell(4).setCellValue("2024-03-01 08:00")
        row.createCell(5).setCellValue("2024-03-01 08:45")
        row.createCell(6).setCellValue(status)
        return wb
    }

    @Test
    fun `待上课记录正确形成事件`() {
        val result = PartTimeScheduleParser().parse(buildWorkbook("待上课"), ParseContext("h"))
        val events = (result as ParseResult.Success).events
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("英语一对一", e.title)
        assertEquals("PT001", e.sourceRecordId)
        assertEquals("2024-03-01T08:00", e.startTime.toString())
        assertEquals("2024-03-01T08:45", e.endTime.toString())
        assertEquals(CourseStatus.PENDING, e.status)
        assertNull(e.blocker)
        assertTrue(e.description!!.contains("小明"))
        assertTrue(e.description!!.contains("李老师"))
    }

    @Test
    fun `已结课默认排除但不标记异常`() {
        val result = PartTimeScheduleParser().parse(buildWorkbook("已结课"), ParseContext("h"))
        val e = ((result as ParseResult.Success).events)[0]
        assertEquals(CourseStatus.COMPLETED, e.status)
        assertNull(e.blocker)
    }

    @Test
    fun `未知状态进入待确认`() {
        val result = PartTimeScheduleParser().parse(buildWorkbook("调课中"), ParseContext("h"))
        val e = ((result as ParseResult.Success).events)[0]
        assertEquals(CourseStatus.UNKNOWN, e.status)
        assertNotNull(e.blocker)
        assertTrue(e.blocker!!.contains("状态未知"))
    }

    @Test
    fun `无结束时间使用默认时长`() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("学员排课")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("课节名称")
        header.createCell(1).setCellValue("上课时间")
        header.createCell(2).setCellValue("状态")
        val row = sheet.createRow(1)
        row.createCell(0).setCellValue("数学辅导")
        row.createCell(1).setCellValue("2024-03-01 09:00")
        row.createCell(2).setCellValue("待上课")

        val result = PartTimeScheduleParser().parse(wb, ParseContext("h"))
        val e = ((result as ParseResult.Success).events)[0]
        assertEquals("2024-03-01T09:45", e.endTime.toString()) // 默认45分钟
    }

    @Test
    fun `无课节ID时退化为内容指纹`() {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("排课")
        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("课节名称")
        header.createCell(1).setCellValue("学员")
        header.createCell(2).setCellValue("上课时间")
        header.createCell(3).setCellValue("状态")
        val row = sheet.createRow(1)
        row.createCell(0).setCellValue("化学")
        row.createCell(1).setCellValue("小红")
        row.createCell(2).setCellValue("2024-03-01 14:00")
        row.createCell(3).setCellValue("待上课")

        val result = PartTimeScheduleParser().parse(wb, ParseContext("h"))
        val e = ((result as ParseResult.Success).events)[0]
        assertNull(e.sourceRecordId)
        assertTrue(e.identityKey.startsWith("PART_TIME|FALLBACK|"))
    }
}
