package com.vivio.coursecalendar

import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.data.repository.ScheduleValidationException
import com.vivio.coursecalendar.domain.schedule.SchedulePeriod
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 作息配置约束校验（纯逻辑，无需数据库）。 */
class ScheduleValidationTest {

    private fun period(no: Int, start: String, end: String) =
        SchedulePeriod(no, LocalTime.parse(start), LocalTime.parse(end))

    private val summer = listOf(
        period(1, "08:00", "09:40"),
        period(2, "10:10", "11:50"),
        period(3, "15:00", "16:40"),
        period(4, "17:10", "18:50"),
        period(5, "20:00", "21:40")
    )

    @Test
    fun `合法配置通过校验`() {
        ScheduleRepository.validatePeriodsStatic(Season.SUMMER, summer)
    }

    @Test
    fun `缺少大节被拒绝`() {
        val missing = summer.filter { it.number != 3 }
        assertThrows(ScheduleValidationException::class.java) {
            ScheduleRepository.validatePeriodsStatic(Season.SUMMER, missing)
        }
    }

    @Test
    fun `开始时间晚于结束被拒绝`() {
        val bad = summer.map { if (it.number == 2) period(2, "11:50", "10:10") else it }
        assertThrows(ScheduleValidationException::class.java) {
            ScheduleRepository.validatePeriodsStatic(Season.SUMMER, bad)
        }
    }

    @Test
    fun `相邻大节重叠被拒绝`() {
        // 第3大节 14:30 开始，与第2大节 11:50 结束不重叠但与第3节自身…
        // 构造：第2大节结束 11:50，第3大节开始 11:00 → 重叠
        val overlap = listOf(
            period(1, "08:00", "09:40"),
            period(2, "10:10", "11:50"),
            period(3, "11:00", "12:40"),
            period(4, "17:10", "18:50"),
            period(5, "20:00", "21:40")
        )
        assertThrows(ScheduleValidationException::class.java) {
            ScheduleRepository.validatePeriodsStatic(Season.SUMMER, overlap)
        }
    }

    @Test
    fun `时间相等被拒绝`() {
        val bad = summer.map { if (it.number == 1) period(1, "08:00", "08:00") else it }
        assertThrows(ScheduleValidationException::class.java) {
            ScheduleRepository.validatePeriodsStatic(Season.SUMMER, bad)
        }
    }
}
