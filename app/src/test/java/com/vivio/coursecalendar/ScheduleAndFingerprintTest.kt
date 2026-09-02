package com.vivio.coursecalendar

import com.vivio.coursecalendar.domain.identity.EventIdentity
import com.vivio.coursecalendar.domain.identity.Normalizer
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 作息配置与事件身份/内容哈希（交接包《03》身份与内容分离）。 */
class ScheduleAndFingerprintTest {

    @Test
    fun `夏季五大节均为100分钟`() {
        DefaultSchedule.summer.forEach { p ->
            assertEquals("第${p.number}大节应为 100 分钟", 100L, p.durationMinutes)
        }
        assertEquals("08:00", DefaultSchedule.summer[0].start.toString())
        assertEquals("09:40", DefaultSchedule.summer[0].end.toString())
        assertEquals("15:00", DefaultSchedule.summer[2].start.toString())
        assertEquals("20:00", DefaultSchedule.summer[4].start.toString())
    }

    @Test
    fun `春季下午大节未配置`() {
        assertTrue(DefaultSchedule.spring[0] != null)
        assertNull(DefaultSchedule.spring[2])
        assertNull(DefaultSchedule.spring[3])
        assertEquals("20:00", DefaultSchedule.spring[4]!!.start.toString())
    }

    @Test
    fun `季节枚举标签`() {
        assertEquals("春季", Season.SPRING.label)
        assertEquals("夏季", Season.SUMMER.label)
    }

    // ---- Normalizer ----

    @Test
    fun `规范化_NFKC合并空白统一大小写`() {
        assertEquals("abc 123", Normalizer.normalize(" ＡＢＣ　１２３ "))
        assertEquals("高 等 数 学", Normalizer.normalize("高　等 数 学"))
        assertEquals("", Normalizer.normalize("  \n  "))
        assertEquals("english", Normalizer.normalize("English"))
    }

    // ---- 兼职身份 ----

    @Test
    fun `兼职身份_课节ID稳定且对空白不敏感`() {
        // 规范化：英文字母统一大小写（identityKey 大小写不敏感匹配）
        assertEquals("PART_TIME|pt001", EventIdentity.partTimeIdentityKey("PT001"))
        assertEquals(
            EventIdentity.partTimeIdentityKey("PT001"),
            EventIdentity.partTimeIdentityKey("  PT 001  ")
        )
    }

    @Test
    fun `兼职退化身份_包含学生课节名与日期`() {
        val d = LocalDateTime.of(2026, 9, 3, 20, 0).toLocalDate()
        val a = EventIdentity.partTimeFallbackIdentityKey("小明", "英语", d)
        assertEquals(a, EventIdentity.partTimeFallbackIdentityKey("小明", "英语", d))
        // 日期变化 → 身份变化（退化身份对时间变化敏感）
        assertNotEquals(a, EventIdentity.partTimeFallbackIdentityKey("小明", "英语", d.plusDays(1)))
    }

    // ---- 校内身份 ----

    @Test
    fun `校内身份_教师或教室变化不改变身份`() {
        val key = EventIdentity.universityIdentityKey("2026-2027", "高等数学", 1, "0102")
        assertEquals(key, EventIdentity.universityIdentityKey("2026-2027", "高等数学", 1, "0102"))
        // 身份不含教师/教室（内容变化 → MODIFIED 而非 NEW）
        assertEquals(key, EventIdentity.universityIdentityKey("2026-2027", "高等数学", 1, "0102"))
        // 周次或节次变化 → 新身份
        assertNotEquals(key, EventIdentity.universityIdentityKey("2026-2027", "高等数学", 2, "0102"))
        assertNotEquals(key, EventIdentity.universityIdentityKey("2026-2027", "高等数学", 1, "0304"))
    }

    // ---- 内容哈希 ----

    @Test
    fun `内容哈希_确定性且敏感于内容变化`() {
        val start = LocalDateTime.of(2024, 3, 1, 8, 0)
        val end = LocalDateTime.of(2024, 3, 1, 9, 40)
        val h1 = EventIdentity.universityContentHash("高等数学", "张三", "A101", start, end, null, "PENDING")
        assertEquals(h1, EventIdentity.universityContentHash("高等数学", "张三", "A101", start, end, null, "PENDING"))
        // 教师变化 → 内容哈希变化
        assertNotEquals(h1, EventIdentity.universityContentHash("高等数学", "李四", "A101", start, end, null, "PENDING"))
        // 教室变化 → 内容哈希变化
        assertNotEquals(h1, EventIdentity.universityContentHash("高等数学", "张三", "B202", start, end, null, "PENDING"))
    }

    @Test
    fun `兼职内容哈希_时间变化导致哈希变化`() {
        val start = LocalDateTime.of(2026, 9, 3, 20, 0)
        val end = LocalDateTime.of(2026, 9, 3, 20, 30)
        val h1 = EventIdentity.partTimeContentHash("英语", "小明", "PENDING", start, end, null, 20)
        val h2 = EventIdentity.partTimeContentHash("英语", "小明", "PENDING", start.plusHours(1), end.plusHours(1), null, 20)
        assertNotEquals(h1, h2)
    }
}
