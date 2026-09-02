package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.ScheduleConfigEntity
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.schedule.SchedulePeriod
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/** 作息配置复合主键行为（Room 内存库）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleConfigDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDb.inMemory(context)
        repo = ScheduleRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

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
    fun `同一季节可保存五个大节且互不覆盖`() = runTest {
        repo.save(Season.SUMMER, summer, version = 1)
        val table = repo.get(Season.SUMMER)
        assertEquals(5, table.periods.size)
        for (no in 1..5) {
            assertTrue("第$no 大节缺失", table.period(no) != null)
        }
    }

    @Test
    fun `更新一个大节不覆盖其他大节`() = runTest {
        repo.save(Season.SUMMER, summer, version = 1)
        // 修改第3大节
        val modified = summer.map { if (it.number == 3) period(3, "14:30", "16:10") else it }
        repo.save(Season.SUMMER, modified, version = 2)
        val table = repo.get(Season.SUMMER)
        assertEquals(5, table.periods.size)
        assertEquals("14:30", table.period(3)!!.start.toString())
        assertEquals("08:00", table.period(1)!!.start.toString())
        assertEquals("20:00", table.period(5)!!.start.toString())
    }

    @Test
    fun `春季和夏季配置相互独立`() = runTest {
        repo.save(Season.SUMMER, summer, version = 1)
        val spring = listOf(
            period(1, "08:00", "09:40"),
            period(2, "10:10", "11:50"),
            period(3, "14:00", "15:40"),
            period(4, "16:10", "17:50"),
            period(5, "20:00", "21:40")
        )
        repo.save(Season.SPRING, spring, version = 1)
        val s = repo.get(Season.SUMMER)
        val p = repo.get(Season.SPRING)
        assertEquals("15:00", s.period(3)!!.start.toString())
        assertEquals("14:00", p.period(3)!!.start.toString())
        assertEquals(5, p.periods.size)
    }

    @Test
    fun `非法配置被拒绝不落库`() = runTest {
        val bad = summer.map { if (it.number == 2) period(2, "11:50", "10:10") else it }
        try {
            repo.save(Season.SUMMER, bad, version = 1)
        } catch (_: com.vivio.coursecalendar.data.repository.ScheduleValidationException) {
            // 预期拒绝
        }
        // 表中不应残留坏数据（未保存）
        val table = repo.get(Season.SUMMER)
        assertTrue(table.periods.isEmpty())
    }
}
