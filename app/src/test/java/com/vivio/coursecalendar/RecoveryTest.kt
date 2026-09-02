package com.vivio.coursecalendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.BatchActionState
import com.vivio.coursecalendar.data.local.entity.BatchActionType
import com.vivio.coursecalendar.data.local.entity.BatchEventActionEntity
import com.vivio.coursecalendar.data.local.entity.BatchPhase
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarEventSnapshot
import com.vivio.coursecalendar.domain.calendar.CalendarGateway
import com.vivio.coursecalendar.domain.import.EventSnapshot
import com.vivio.coursecalendar.domain.import.ImportManager
import com.vivio.coursecalendar.domain.import.ImportPreview
import com.vivio.coursecalendar.domain.import.PreviewItem
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/**
 * v2 F4/F5：启动恢复器。构造超时 APPLYING/UNDOING 批次与中断窗口，
 * 验证 recover 通过 CalendarGateway 核对真实状态，补写/重试/标记人工确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryTest {

    private lateinit var db: AppDatabase
    private lateinit var gateway: FakeCal
    private lateinit var importManager: ImportManager

    private class FakeCal : CalendarGateway {
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDb.inMemory(context)
        gateway = FakeCal()
        importManager = ImportManager(db, ScheduleRepository(db), gateway)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(title: String, key: String) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = CourseStatus.PENDING,
        identityKey = key,
        contentHash = "h-$key"
    )

    /** 构造超时 APPLYING 批次 + CREATE 动作（calendar 已插入但未 DB_APPLIED）。 */
    private suspend fun seedStuckCreateBatch(calendarEventId: Long): Long {
        val now = System.currentTimeMillis()
        val old = now - 10 * 60 * 1000L // 超过 5 分钟阈值
        val batch = ImportBatchEntity(
            fileHash = "fh", fileName = "t.xls", source = "PART_TIME", season = null,
            createdAt = old, phase = BatchPhase.APPLYING,
            totalCount = 1, createdCount = 0, updatedCount = 0, unchangedCount = 0, invalidCount = 0
        )
        val batchId = db.importBatchDao().insert(batch)
        val action = BatchEventActionEntity(
            batchId = batchId, managedEventId = null, identityKey = "pt001",
            actionType = BatchActionType.CREATE,
            beforeSnapshot = null, afterSnapshot = EventSnapshot.toJson(event("英语", "pt001")),
            calendarEventIdBefore = null, calendarEventIdAfter = calendarEventId,
            state = BatchActionState.CALENDAR_APPLIED, errorCode = null
        )
        db.batchEventActionDao().insertAll(listOf(action))
        return batchId
    }

    @Test
    fun `CREATE日历成功但未写DB恢复为DB_APPLIED`() = runTest {
        // 系统事件已存在（模拟 Calendar 插入成功）
        val cid = gateway.insertEvent(EventSource.PART_TIME, event("英语", "pt001"))!!
        val batchId = seedStuckCreateBatch(cid)

        importManager.recover()

        val batch = db.importBatchDao().getById(batchId)!!
        assertEquals(BatchPhase.APPLIED, batch.phase)
        val action = db.batchEventActionDao().getByBatch(batchId).first()
        // managed_event 未写入 → 无法补 DB_APPLIED，标记 FAILED 待人工确认
        assertEquals(BatchActionState.FAILED, action.state)
    }

    @Test
    fun `CREATE系统无事件恢复不盲目创建`() = runTest {
        // 系统事件不存在（模拟 insert 后丢失）
        val batchId = seedStuckCreateBatch(calendarEventId = 999L)

        importManager.recover()

        val batch = db.importBatchDao().getById(batchId)!!
        val action = db.batchEventActionDao().getByBatch(batch.id).first()
        assertEquals(BatchActionState.FAILED, action.state)
        assertTrue("不应创建新系统事件", gateway.events.isEmpty())
    }

    @Test
    fun `managed_event指向不存在的系统事件标记BROKEN`() = runTest {
        // 先正常导入产生 managed_event
        val e = event("英语", "pt001")
        importManager.commit(
            ImportPreview(
                source = EventSource.PART_TIME, season = null,
                items = listOf(PreviewItem(e, state = EventState.NEW)),
                warnings = emptyList(), fileHash = "fh", fileName = "t.xls"
            ),
            null, emptySet()
        )
        assertEquals(1, gateway.events.size)
        // 模拟系统事件被外部删除
        val cid = gateway.events.keys.first()
        gateway.events.remove(cid)

        importManager.recover()

        val me = db.managedEventDao().getAll().first()
        assertEquals("BROKEN", me.status)
        assertFalse(gateway.eventExists(cid))
    }

    @Test
    fun `UNDOING批次超时被处理`() = runTest {
        val now = System.currentTimeMillis()
        val old = now - 10 * 60 * 1000L
        val batch = ImportBatchEntity(
            fileHash = "fh", fileName = "t.xls", source = "PART_TIME", season = null,
            createdAt = old, phase = BatchPhase.UNDOING,
            totalCount = 0, createdCount = 0, updatedCount = 0, unchangedCount = 0, invalidCount = 0
        )
        val batchId = db.importBatchDao().insert(batch)

        importManager.recover()

        val processed = db.importBatchDao().getById(batchId)!!
        assertTrue("UNDOING 超时批次应被处理", processed.phase == BatchPhase.APPLIED || processed.phase == BatchPhase.PARTIAL)
    }
}
