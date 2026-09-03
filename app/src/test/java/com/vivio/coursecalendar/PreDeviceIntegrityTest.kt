package com.vivio.coursecalendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.BatchActionState
import com.vivio.coursecalendar.data.local.entity.BatchActionType
import com.vivio.coursecalendar.data.local.entity.BatchEventActionEntity
import com.vivio.coursecalendar.data.local.entity.BatchPhase
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.repository.ScheduleRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/**
 * 投入使用前阶段 A（U1-U6）：真机前代码收尾失败测试。
 * 先写失败测试复现，再修复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreDeviceIntegrityTest {

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

    private fun event(title: String, key: String) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = CourseStatus.PENDING,
        identityKey = key,
        contentHash = "h-$key"
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

    private suspend fun seedBatch(phase: String, actions: List<BatchEventActionEntity>): Long {
        val now = System.currentTimeMillis()
        val batch = ImportBatchEntity(
            fileHash = "fh", fileName = "t.xls", source = "PART_TIME", season = null,
            createdAt = now - 10 * 60 * 1000L, phase = phase,
            totalCount = actions.size, createdCount = 0, updatedCount = 0, unchangedCount = 0, invalidCount = 0
        )
        val batchId = db.importBatchDao().insert(batch)
        db.batchEventActionDao().insertAll(actions.map { it.copy(batchId = batchId) })
        return batchId
    }

    private fun action(
        type: String, key: String, state: String,
        before: UnifiedEvent? = null, after: UnifiedEvent? = null,
        cidBefore: Long? = null, cidAfter: Long? = null,
        token: String? = null, mid: Long? = null
    ) = BatchEventActionEntity(
        batchId = 0, managedEventId = mid, identityKey = key, actionType = type,
        beforeSnapshot = before?.let { EventSnapshot.toJson(it) },
        afterSnapshot = after?.let { EventSnapshot.toJson(it) },
        calendarEventIdBefore = cidBefore, calendarEventIdAfter = cidAfter,
        state = state, errorCode = null, operationToken = token
    )

    private suspend fun insertManaged(key: String, cid: Long?, status: String = "ACTIVE"): Long {
        val now = System.currentTimeMillis()
        return db.managedEventDao().insert(
            ManagedEventEntity(
                source = "PART_TIME", identityKey = key, contentHash = "h-$key",
                sourceRecordId = null, calendarEventId = cid,
                title = "英语", location = null, description = null,
                startMillis = CourseTime.toMillis(LocalDateTime.of(2026, 9, 1, 20, 0)),
                endMillis = CourseTime.toMillis(LocalDateTime.of(2026, 9, 1, 20, 30)),
                reminderMinutes = null, status = status, lastSeenBatchId = null,
                createdAt = now, updatedAt = now
            )
        )
    }

    // ---------- U1（P0）：撤销“恢复开课”删除结果未核验 ----------

    @Test
    fun `U1撤销恢复开课删除失败REVERT_FAILED且managed保留ACTIVE`() = runTest {
        val e = event("英语", "pt001")
        // 导入 → 取消 → 恢复开课（重建新事件）
        importManager.commit(preview(listOf(e)), null, emptySet())
        val cancelled = e.copy(status = CourseStatus.CANCELLED)
        importManager.commit(preview(listOf(cancelled), mapOf("pt001" to EventState.CANCELLED)), null, emptySet())
        val r3 = importManager.commit(preview(listOf(e), mapOf("pt001" to EventState.MODIFIED)), null, emptySet())
        assertEquals(1, gateway.events.size)
        val afterCid = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!.calendarEventId

        // 撤销恢复开课：删除新事件失败
        gateway.nextDeleteShouldFail = true
        importManager.undo(r3.batchId)

        val act = db.batchEventActionDao().getByBatch(r3.batchId).first()
        assertEquals("删除失败不得标 REVERTED", BatchActionState.REVERT_FAILED, act.state)
        val batch = db.importBatchDao().getById(r3.batchId)!!
        assertEquals(BatchPhase.PARTIAL, batch.phase)
        // managed 必须保持 ACTIVE + after ID，系统事件保留，不得互相矛盾
        val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
        assertEquals("ACTIVE", me.status)
        assertEquals(afterCid, me.calendarEventId)
        assertEquals("系统事件应保留", 1, gateway.events.size)
        assertTrue("managed 与系统日历不得矛盾", me.calendarEventId != null && gateway.eventExists(me.calendarEventId!!))
    }

    // ---------- U2（P1）：撤销 DELETE 复用已保存 ID 时未核验事件存在 ----------

    @Test
    fun `U2撤销DELETE复用ID但事件已删除时按token重建`() = runTest {
        val e = event("英语", "pt001")
        val mid = insertManaged("pt001", null, status = "CANCELLED")
        // 重建后事件曾存在（cid），随后被外部删除
        val cid = 900L
        gateway.events[cid] = e
        gateway.tokenByEvent[cid] = "tok-u2"
        gateway.events.remove(cid)
        gateway.tokenByEvent.remove(cid)
        val batchId = seedBatch(
            BatchPhase.UNDOING,
            listOf(action(BatchActionType.DELETE, "pt001", BatchActionState.DB_APPLIED, before = e, cidBefore = 100L, cidAfter = cid, token = "tok-u2", mid = mid))
        )

        importManager.undo(batchId)

        val me = db.managedEventDao().getById(mid)!!
        assertEquals("ACTIVE", me.status)
        assertNotNull("重建后 managed 必须指向真实存在的事件", me.calendarEventId)
        assertTrue("managed 不得指向不存在的系统事件", gateway.eventExists(me.calendarEventId!!))
        assertEquals(1, gateway.events.size)
    }

    // ---------- U3（P1）：CREATE 的提醒没有回读验证 ----------

    @Test
    fun `U3CREATE提醒写入失败不得假成功`() = runTest {
        val e = event("英语", "pt001")
        gateway.dropReminderAfterNextInsert = true
        val r = importManager.commit(preview(listOf(e)), 10, emptySet())
        assertEquals("提醒未同步应计入失败", 1, r.failed)
        // 动作应为 FAILED 且带错误码，不显示为成功
        val act = db.batchEventActionDao().getByStates(listOf(BatchActionState.FAILED)).first()
        assertNotNull(act.errorCode)
    }

    // ---------- U4（P1）：损坏快照路径必须持久化 action 状态 ----------

    @Test
    fun `U4空快照恢复必须持久化FAILED`() = runTest {
        // afterSnapshot=null（损坏），不得裸 fail 不落库
        val batchId = seedBatch(
            BatchPhase.APPLYING,
            listOf(action(BatchActionType.CREATE, "pt001", BatchActionState.PLANNED, after = null))
        )
        importManager.recover()
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals("空快照应持久化 FAILED", BatchActionState.FAILED, act.state)
        assertNotNull(act.errorCode)
        val batch = db.importBatchDao().getById(batchId)!!
        assertEquals(BatchPhase.PARTIAL, batch.phase)
    }

    // ---------- U5（P1）：权限撤销 / Provider 异常兜底 ----------

    @Test
    fun `U5Provider权限异常不崩溃且动作失败落库`() = runTest {
        val e = event("英语", "pt001")
        gateway.throwSecurityOnNextInsert = true
        // commit 必须捕获 SecurityException，不崩溃
        val r = importManager.commit(preview(listOf(e)), null, emptySet())
        assertEquals(1, r.failed)
        val act = db.batchEventActionDao().getByStates(listOf(BatchActionState.FAILED)).first()
        assertNotNull(act.errorCode)
    }
}
