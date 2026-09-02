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
import com.vivio.coursecalendar.domain.import.EventSnapshot
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/** 步骤 7：压力与幂等回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StressTest {

    private lateinit var db: AppDatabase
    private lateinit var gateway: FaultyCalendarGateway
    private lateinit var importManager: ImportManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDb.inMemory(context)
        gateway = FaultyCalendarGateway()
        importManager = ImportManager(db, ScheduleRepository(db), gateway)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(title: String, key: String, status: CourseStatus = CourseStatus.PENDING) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = status,
        identityKey = key,
        contentHash = "h-$key-$status"
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
    fun `同一文件连续导入20次事件数量稳定`() = runTest {
        val e = event("英语", "pt001")
        var created = 0
        for (i in 1..20) {
            val state = if (i == 1) EventState.NEW else EventState.UNCHANGED
            val r = importManager.commit(preview(listOf(e), mapOf("pt001" to state)), null, emptySet())
            if (i == 1) created = r.created
        }
        assertEquals(1, gateway.events.size)
        assertEquals(1, db.managedEventDao().getAll().size)
        assertEquals(1, created)
    }

    @Test
    fun `取消恢复撤销循环10次无孤儿事件`() = runTest {
        val pending = event("英语", "pt001")
        val cancelled = event("英语", "pt001", CourseStatus.CANCELLED)
        // 初始导入
        importManager.commit(preview(listOf(pending)), null, emptySet())
        assertEquals(1, gateway.events.size)

        for (i in 1..10) {
            // 取消 → DELETE
            val rCancel = importManager.commit(preview(listOf(cancelled), mapOf("pt001" to EventState.CANCELLED)), null, emptySet())
            assertEquals(0, gateway.events.size)
            // 恢复开课 → UPDATE 重建
            val rRestore = importManager.commit(preview(listOf(pending), mapOf("pt001" to EventState.MODIFIED)), null, emptySet())
            assertEquals(1, gateway.events.size)
            // 撤销恢复开课 → 回 CANCELLED，删除新事件
            importManager.undo(rRestore.batchId)
            assertEquals(0, gateway.events.size)
            val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
            assertEquals("CANCELLED", me.status)
            // 撤销取消（重建）→ 回 ACTIVE
            importManager.undo(rCancel.batchId)
            assertEquals(1, gateway.events.size)
        }
        // 最终无孤儿：managed 与系统事件一致
        val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
        assertEquals("ACTIVE", me.status)
        assertEquals(1, gateway.events.size)
        assertEquals(me.calendarEventId, gateway.events.keys.first())
    }

    @Test
    fun `recover连续调用10次状态不漂移`() = runTest {
        val e = event("英语", "pt001")
        // seed 一个超时 APPLYING 批次，token 找回可补写
        val now = System.currentTimeMillis()
        val batch = ImportBatchEntity(
            fileHash = "fh", fileName = "t.xls", source = "PART_TIME", season = null,
            createdAt = now - 10 * 60 * 1000L, phase = BatchPhase.APPLYING,
            totalCount = 1, createdCount = 0, updatedCount = 0, unchangedCount = 0, invalidCount = 0
        )
        val batchId = db.importBatchDao().insert(batch)
        val token = "tok-stress"
        val cid = 900L
        gateway.events[cid] = e
        gateway.tokenByEvent[cid] = token
        db.batchEventActionDao().insertAll(
            listOf(
                BatchEventActionEntity(
                    batchId = batchId, managedEventId = null, identityKey = "pt001", actionType = BatchActionType.CREATE,
                    beforeSnapshot = null, afterSnapshot = EventSnapshot.toJson(e),
                    calendarEventIdBefore = null, calendarEventIdAfter = null,
                    state = BatchActionState.PLANNED, errorCode = null, operationToken = token
                )
            )
        )

        importManager.recover()
        val snapshot = listOf(gateway.events.size, db.managedEventDao().getAll().size)
        for (i in 1..10) {
            importManager.recover()
        }
        assertEquals("系统事件数量不漂移", snapshot[0], gateway.events.size)
        assertEquals("managed 数量不漂移", snapshot[1], db.managedEventDao().getAll().size)
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals(BatchActionState.DB_APPLIED, act.state)
    }
}
