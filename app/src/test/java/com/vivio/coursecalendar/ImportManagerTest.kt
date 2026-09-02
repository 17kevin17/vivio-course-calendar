package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarGateway
import com.vivio.coursecalendar.domain.import.DiffEngine
import com.vivio.coursecalendar.domain.import.ImportManager
import com.vivio.coursecalendar.domain.import.ImportPreview
import com.vivio.coursecalendar.domain.import.PreviewItem
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/** 内存日历网关：模拟 CalendarProvider，便于验证导入/撤销行为。 */
private class FakeCalendarGateway : CalendarGateway {
    val events = mutableMapOf<Long, UnifiedEvent>()
    private var nextId = 1L
    override fun ensureCalendar(source: EventSource): Long = 1L
    override fun insertEvent(source: EventSource, event: UnifiedEvent): Long? {
        val id = nextId++
        events[id] = event
        return id
    }
    override fun updateEvent(source: EventSource, calendarEventId: Long, event: UnifiedEvent): Boolean {
        if (!events.containsKey(calendarEventId)) return false
        events[calendarEventId] = event
        return true
    }
    override fun deleteEvent(calendarEventId: Long): Boolean = events.remove(calendarEventId) != null
}

/** 导入状态机：重复导入幂等、撤销 CREATE/UPDATE、撤销幂等（交接包《05》P0）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var gateway: FakeCalendarGateway
    private lateinit var importManager: ImportManager
    private lateinit var diffEngine: DiffEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDb.inMemory(context)
        gateway = FakeCalendarGateway()
        importManager = ImportManager(db, ScheduleRepository(db), gateway)
        diffEngine = DiffEngine(db.managedEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(title: String, key: String, hash: String) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = CourseStatus.PENDING,
        identityKey = key,
        contentHash = hash
    )

    private fun preview(events: List<UnifiedEvent>, states: Map<String, EventState> = emptyMap()) =
        ImportPreview(
            source = EventSource.PART_TIME,
            season = null,
            items = events.map { PreviewItem(it, state = states[it.identityKey] ?: EventState.NEW) },
            warnings = emptyList(),
            fileHash = "f-hash",
            fileName = "t.xls"
        )

    @Test
    fun `连续导入三次不新增系统事件`() = runTest {
        val e = event("英语", "pt001", "h1")

        // 第一次：CREATE
        val r1 = importManager.commit(preview(listOf(e)), null, emptySet())
        assertEquals(1, r1.created)
        assertEquals(1, gateway.events.size)

        // 第二次：同身份同内容 → UNCHANGED，不新增
        val plan2 = diffEngine.compute(listOf(e), EventSource.PART_TIME, null)
        assertEquals(EventState.UNCHANGED, plan2.items[0].state)
        val r2 = importManager.commit(preview(listOf(e), mapOf("pt001" to EventState.UNCHANGED)), null, emptySet())
        assertEquals(0, r2.created)
        assertEquals(1, gateway.events.size)

        // 第三次：仍然不新增
        val r3 = importManager.commit(preview(listOf(e), mapOf("pt001" to EventState.UNCHANGED)), null, emptySet())
        assertEquals(0, r3.created)
        assertEquals(1, gateway.events.size)
    }

    @Test
    fun `撤销CREATE删除系统事件`() = runTest {
        val e = event("英语", "pt001", "h1")
        val r = importManager.commit(preview(listOf(e)), null, emptySet())
        assertEquals(1, gateway.events.size)

        assertTrue(importManager.undo(r.batchId))
        assertTrue("撤销后系统事件应清空", gateway.events.isEmpty())
        assertTrue("撤销后映射应清空", db.managedEventDao().getAll().isEmpty())
    }

    @Test
    fun `撤销UPDATE恢复原标题时间`() = runTest {
        // 首次导入
        val e1 = event("英语", "pt001", "h1")
        val r1 = importManager.commit(preview(listOf(e1)), null, emptySet())
        assertEquals("英语", gateway.events.values.first().title)

        // 修改后再次导入 → UPDATE
        val e2 = e1.copy(title = "英语（改）", contentHash = "h2")
        val plan = diffEngine.compute(listOf(e2), EventSource.PART_TIME, null)
        assertEquals(EventState.MODIFIED, plan.items[0].state)
        val r2 = importManager.commit(
            preview(listOf(e2), mapOf("pt001" to EventState.MODIFIED)),
            null,
            emptySet()
        )
        assertEquals(1, r2.updated)
        assertEquals("英语（改）", gateway.events.values.first().title)

        // 撤销 → 恢复原标题
        assertTrue(importManager.undo(r2.batchId))
        assertEquals("英语", gateway.events.values.first().title)
    }

    @Test
    fun `重复撤销幂等`() = runTest {
        val e = event("英语", "pt001", "h1")
        val r = importManager.commit(preview(listOf(e)), null, emptySet())
        assertTrue(importManager.undo(r.batchId))
        // 第二次撤销不应产生副作用或报错
        assertTrue(importManager.undo(r.batchId))
        assertTrue(gateway.events.isEmpty())
    }

    @Test
    fun `撤销一个批次不影响其他批次事件`() = runTest {
        val e1 = event("英语", "pt001", "h1")
        val e2 = event("数学", "pt002", "h2")
        val r1 = importManager.commit(preview(listOf(e1)), null, emptySet())
        val r2 = importManager.commit(preview(listOf(e2)), null, emptySet())
        assertEquals(2, gateway.events.size)

        importManager.undo(r1.batchId)
        assertEquals(1, gateway.events.size)
        assertEquals("数学", gateway.events.values.first().title)
    }

    @Test
    fun `已存在事件收到取消删除系统事件并标记取消`() = runTest {
        val e1 = event("英语", "pt001", "h1")
        val r1 = importManager.commit(preview(listOf(e1)), null, emptySet())
        assertEquals(1, gateway.events.size)

        // 同一身份收到 CANCELLED → DELETE：删除系统事件，managed 标记取消
        val cancelled = e1.copy(status = CourseStatus.CANCELLED)
        val r2 = importManager.commit(
            preview(listOf(cancelled), mapOf("pt001" to EventState.CANCELLED)),
            null,
            emptySet()
        )
        assertEquals(1, r2.deleted)
        assertTrue("取消后系统事件应清空", gateway.events.isEmpty())
        val me = db.managedEventDao().getAll().first()
        assertEquals("CANCELLED", me.status)
    }

    @Test
    fun `不存在事件收到取消不创建系统事件`() = runTest {
        val cancelled = event("英语", "pt999", "h1").copy(status = CourseStatus.CANCELLED)
        val r = importManager.commit(
            preview(listOf(cancelled), mapOf("pt999" to EventState.UNCHANGED)),
            null,
            emptySet()
        )
        assertEquals(0, r.created)
        assertEquals(0, r.deleted)
        assertTrue("不应创建任何系统事件", gateway.events.isEmpty())
        assertTrue("不应有 managed_event", db.managedEventDao().getAll().isEmpty())
    }
}
