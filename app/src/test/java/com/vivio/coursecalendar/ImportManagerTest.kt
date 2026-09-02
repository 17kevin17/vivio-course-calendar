package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarEventSnapshot
import com.vivio.coursecalendar.domain.calendar.CalendarGateway
import com.vivio.coursecalendar.domain.import.DiffEngine
import com.vivio.coursecalendar.domain.import.ImportManager
import com.vivio.coursecalendar.domain.import.ImportPreview
import com.vivio.coursecalendar.domain.import.PreviewItem
import com.vivio.coursecalendar.domain.identity.EventIdentity
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime
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
    override fun eventExists(calendarEventId: Long): Boolean = events.containsKey(calendarEventId)
    override fun getEvent(calendarEventId: Long): CalendarEventSnapshot? {
        val e = events[calendarEventId] ?: return null
        return CalendarEventSnapshot(
            calendarEventId = calendarEventId,
            title = e.title,
            startMillis = CourseTime.toMillis(e.startTime),
            endMillis = CourseTime.toMillis(e.endTime),
            eventTimezone = null
        )
    }
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

    private fun event(title: String, key: String, hash: String? = null) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = CourseStatus.PENDING,
        identityKey = key,
        contentHash = hash ?: EventIdentity.partTimeContentHash(
            title = title, student = null, status = CourseStatus.PENDING.name,
            start = LocalDateTime.of(2026, 9, 1, 20, 0),
            end = LocalDateTime.of(2026, 9, 1, 20, 30),
            location = null, reminderMinutes = null
        )
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
        val e = event("英语", "pt001")

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

    @Test
    fun `撤销DELETE重建事件且不重复创建`() = runTest {
        val e1 = event("英语", "pt001", "h1")
        val r1 = importManager.commit(preview(listOf(e1)), null, emptySet())
        assertEquals(1, gateway.events.size)

        // 标记 CANCELLED → DELETE 批次，删除系统事件
        val cancelled = e1.copy(status = CourseStatus.CANCELLED)
        val r2 = importManager.commit(
            preview(listOf(cancelled), mapOf("pt001" to EventState.CANCELLED)),
            null,
            emptySet()
        )
        assertEquals(1, r2.deleted)
        assertTrue(gateway.events.isEmpty())

        // 撤销 DELETE → 重建原事件
        assertTrue(importManager.undo(r2.batchId))
        assertEquals("重建后应有 1 个事件", 1, gateway.events.size)
        val rebuiltId = gateway.events.keys.first()

        // F3：再次 undo（或中断重试）不得重复创建；已 REVERTED 动作跳过
        assertTrue(importManager.undo(r2.batchId))
        assertEquals("重复撤销不得再次创建", 1, gateway.events.size)
        assertEquals("重复撤销不得更换事件 ID", rebuiltId, gateway.events.keys.first())
        // managed_event 恢复 ACTIVE 且指向新 ID
        val me = db.managedEventDao().getAll().first()
        assertEquals("ACTIVE", me.status)
        assertEquals(rebuiltId, me.calendarEventId)
    }

    @Test
    fun `撤销NOOP不修改事件`() = runTest {
        val e1 = event("英语", "pt001", "h1")
        val r1 = importManager.commit(preview(listOf(e1)), null, emptySet())

        // 再次导入同内容 → UNCHANGED (NOOP)
        val r2 = importManager.commit(
            preview(listOf(e1), mapOf("pt001" to EventState.UNCHANGED)),
            null,
            emptySet()
        )
        assertEquals(1, gateway.events.size)
        // 撤销 NOOP 批次：事件保持不变
        assertTrue(importManager.undo(r2.batchId))
        assertEquals(1, gateway.events.size)
        assertEquals("英语", gateway.events.values.first().title)
    }

    @Test
    fun `提醒变化产生MODIFIED且managed哈希与最终提醒一致`() = runTest {
        val e = event("英语", "pt001") // 真实解析哈希（无提醒）
        // 首次导入：10 分钟提醒
        val r1 = importManager.commit(preview(listOf(e)), 10, emptySet())
        assertEquals(1, r1.created)
        assertEquals(10, gateway.events.values.first().reminderMinutes)
        val me1 = db.managedEventDao().getAll().first()
        assertEquals(10, me1.reminderMinutes)

        // 再次导入同一文件，预览 UNCHANGED，但提交 30 分钟 → 最终哈希变化 → 重新判定 MODIFIED(UPDATE)
        val r2 = importManager.commit(
            preview(listOf(e), mapOf("pt001" to EventState.UNCHANGED)),
            30,
            emptySet()
        )
        assertEquals("提醒 10→30 应产生 UPDATE", 1, r2.updated)
        assertEquals(30, gateway.events.values.first().reminderMinutes)
        val me2 = db.managedEventDao().getAll().first()
        assertEquals("managed contentHash 与最终提醒一致", 30, me2.reminderMinutes)
        assertEquals("afterSnapshot 含 30 分钟提醒", 30, gateway.events.values.first().reminderMinutes)

        // 撤销 → 恢复 10 分钟提醒
        assertTrue(importManager.undo(r2.batchId))
        assertEquals(10, gateway.events.values.first().reminderMinutes)
        val me3 = db.managedEventDao().getAll().first()
        assertEquals(10, me3.reminderMinutes)
    }

    @Test
    fun `同提醒重复导入不产生更新`() = runTest {
        val e = event("英语", "pt001")
        val r1 = importManager.commit(preview(listOf(e)), 10, emptySet())
        assertEquals(1, r1.created)

        // 同一文件 + 同一提醒：最终哈希与 stored 一致 → 仍为 NOOP，不新增不更新
        val r2 = importManager.commit(
            preview(listOf(e), mapOf("pt001" to EventState.UNCHANGED)),
            10,
            emptySet()
        )
        assertEquals(0, r2.created)
        assertEquals(0, r2.updated)
        assertEquals(1, gateway.events.size)
    }

    @Test
    fun `managed_event重复插入不REPLACE重建主键`() = runTest {
        // v2 F9：同 (source, identityKey) 重复插入应抛约束冲突（ABORT），绝不 REPLACE 重建主键。
        val now = System.currentTimeMillis()
        val e = com.vivio.coursecalendar.data.local.entity.ManagedEventEntity(
            source = "PART_TIME",
            identityKey = "pt001",
            contentHash = "h",
            sourceRecordId = null,
            calendarEventId = 100L,
            title = "课",
            location = null,
            description = null,
            startMillis = now,
            endMillis = now + 1800_000,
            status = com.vivio.coursecalendar.data.local.entity.ManagedStatus.ACTIVE,
            lastSeenBatchId = 1L,
            createdAt = now,
            updatedAt = now
        )
        val id1 = db.managedEventDao().insert(e)
        var threw = false
        try {
            db.managedEventDao().insert(e.copy(id = 0))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("重复插入应抛约束冲突而非 REPLACE 静默重建", threw)
        val all = db.managedEventDao().getAll()
        assertEquals(1, all.size)
        assertEquals("主键不得被重建", id1, all[0].id)
    }
}
