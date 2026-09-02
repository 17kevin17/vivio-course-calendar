package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.import.EventFingerprint
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAndFingerprintTest {

    @Test
    fun `夏季五大节均为100分钟`() {
        DefaultSchedule.summer.forEach { p ->
            assertEquals("第${p.number}大节应为 100 分钟", 100L, p.durationMinutes)
        }
        // 已确认的关键时间点
        assertEquals("08:00", DefaultSchedule.summer[0].start.toString())
        assertEquals("09:40", DefaultSchedule.summer[0].end.toString())
        assertEquals("15:00", DefaultSchedule.summer[2].start.toString())
        assertEquals("20:00", DefaultSchedule.summer[4].start.toString())
    }

    @Test
    fun `春季下午大节未配置`() {
        assertTrue(DefaultSchedule.spring[0] != null) // 08:00
        assertNull(DefaultSchedule.spring[2]) // 下午待确认
        assertNull(DefaultSchedule.spring[3])
        assertEquals("20:00", DefaultSchedule.spring[4]!!.start.toString())
    }

    @Test
    fun `校内指纹_日期大节课程教室`() {
        val fp1 = EventFingerprint.university(
            LocalDateTime.of(2024, 3, 1, 8, 0), 1, "高等数学", "A101"
        )
        val fp2 = EventFingerprint.university(
            LocalDateTime.of(2024, 3, 1, 8, 0), 1, "高等数学", "A101"
        )
        assertEquals(fp1, fp2)

        // 教师变化不影响指纹（事件修改而非新建）
        val fp3 = EventFingerprint.university(
            LocalDateTime.of(2024, 3, 1, 8, 0), 1, "高等数学", "A101"
        )
        assertEquals(fp1, fp3)

        // 教室或大节变化 → 指纹变化
        assertNotEquals(fp1, EventFingerprint.university(LocalDateTime.of(2024, 3, 1, 8, 0), 2, "高等数学", "A101"))
        assertNotEquals(fp1, EventFingerprint.university(LocalDateTime.of(2024, 3, 2, 8, 0), 1, "高等数学", "A101"))
    }

    @Test
    fun `规范化_全角转半角去空白`() {
        assertEquals("ABC123", EventFingerprint.normalize(" ＡＢＣ　１２３ "))
        assertEquals("高等数学", EventFingerprint.normalize("高 等 数 学"))
        assertEquals("", EventFingerprint.normalize("  \n  "))
    }

    @Test
    fun `兼职指纹_优先课节ID`() {
        val fp = EventFingerprint.partTimeWithId("PT001")
        assertEquals("P|ID|PT001", fp)
        assertNotEquals(EventFingerprint.partTimeWithId("PT002"), fp)
    }

    @Test
    fun `季节枚举标签`() {
        assertEquals("春季", Season.SPRING.label)
        assertEquals("夏季", Season.SUMMER.label)
    }
}
