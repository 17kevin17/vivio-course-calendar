package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.identity.EventIdentity
import com.vivio.coursecalendar.domain.parser.ExcelIO
import com.vivio.coursecalendar.domain.parser.FormatDetector
import com.vivio.coursecalendar.domain.parser.ParseContext
import com.vivio.coursecalendar.domain.parser.ScheduleTable
import com.vivio.coursecalendar.domain.parser.UniversityScheduleParser
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.Season
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 临时诊断：统计校内样表 identityKey 唯一数（验证 F1 碰撞）。 */
class F1DiagnosisTest {

    @Test
    fun `统计校内样表身份唯一数`() {
        val file = File("e:\\vivio\\download (5).xls")
        assertTrue(file.exists())
        val bytes = file.readBytes()
        val wb = ExcelIO.openSafely(bytes.inputStream(), bytes)!!
        val table = ScheduleTable(Season.SUMMER, DefaultSchedule.summer.associateBy { it.number })
        val result = UniversityScheduleParser().parse(wb, ParseContext("diag", Season.SUMMER, table))
        wb.close()
        val events = (result as com.vivio.coursecalendar.domain.parser.ParseResult.Success).events
        val byKey = events.groupBy { it.identityKey }
        val dup = byKey.filter { it.value.size > 1 }
        println("=== F1 诊断 ===")
        println("事件总数: ${events.size}")
        println("唯一 key: ${byKey.size}")
        println("碰撞组数: ${dup.size}")
        // F1 完成门槛：解析 136 条，唯一 key 也为 136，不允许静默丢弃
        assertTrue("碰撞组数应=0，实际 ${dup.size}", dup.isEmpty())
        assertTrue("唯一 key 应=事件数 136，实际 ${byKey.size}", byKey.size == events.size)
        dup.forEach { (k, list) ->
            val detail = list.map { "${it.title}@${it.startTime.toLocalDate()}周${it.weekNo}/${it.periodCode}" }
            println("KEY=$k -> ${list.size} 条: $detail")
        }
    }
}
