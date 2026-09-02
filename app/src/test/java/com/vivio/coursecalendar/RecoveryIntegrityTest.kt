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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/**
 * v2 下一轮 R1-R6：崩溃边界与恢复状态机完整性。
 * 先写失败测试复现四个 P0（R1/R2/R3/R4）与 R6 汇总缺陷；
 * 修复前这些用例必须稳定失败并准确命中对应缺陷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryIntegrityTest {

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

    /** 构造超时批次（APPLYING / UNDOING），返回 batchId。 */
    private suspend fun seedBatch(phase: String, actions: List<BatchEventActionEntity>): Long {
        val now = System.currentTimeMillis()
        val old = now - 10 * 60 * 1000L
        val batch = ImportBatchEntity(
            fileHash = "fh", fileName = "t.xls", source = "PART_TIME", season = null,
            createdAt = old, phase = phase,
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

    /** 直接写入带 token 的系统事件（模拟 insert 成功但 ID 未落库），不经过 insertEvent。 */
    private fun preseedEventWithToken(token: String): Long {
        val e = event("英语", "pt001")
        val id = 900L
        gateway.events[id] = e
        gateway.tokenByEvent[id] = token
        return id
    }

    private fun preview(events: List<UnifiedEvent>, states: Map<String, EventState> = emptyMap()) =
        ImportPreview(
            source = EventSource.PART_TIME,
            season = null,
            items = events.map { PreviewItem(it, state = states[it.identityKey] ?: EventState.NEW) },
            warnings = emptyList(),
            fileHash = "f-hash",
            fileName = "t.xls"
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

    // ---------- R1：UNDOING 恢复必须走逆操作方向 ----------

    @Test
    fun `UNDOING中断后恢复继续执行逆操作并进入UNDONE`() = runTest {
        // 正向 CREATE 已完成（action DB_APPLIED、managed ACTIVE 带 cid），撤销中进程中断 → 批次 UNDOING 超时
        val e = event("英语", "pt001")
        val cid = gateway.insertEvent(EventSource.PART_TIME, e)!!
        val mid = insertManaged("pt001", cid)
        val batchId = seedBatch(BatchPhase.UNDOING, listOf(action(BatchActionType.CREATE, "pt001", BatchActionState.DB_APPLIED, after = e, cidAfter = cid, mid = mid)))

        importManager.recover()

        val batch = db.importBatchDao().getById(batchId)!!
        assertEquals("撤销方向恢复后批次应进入 UNDONE", BatchPhase.UNDONE, batch.phase)
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals("CREATE 逆操作（删除）完成后应 REVERTED", BatchActionState.REVERTED, act.state)
        assertFalse("撤销 CREATE 应删除系统事件", gateway.eventExists(cid))
    }

    // ---------- R2：operation token 幂等找回 ----------

    @Test
    fun `CREATE插入成功但ID未落库时按token找回且不重复创建`() = runTest {
        // 系统已存在带 token 的事件（模拟 insert 成功但 ID 未写回 Room）
        val cid = preseedEventWithToken("tok-1")
        val batchId = seedBatch(BatchPhase.APPLYING, listOf(action(BatchActionType.CREATE, "pt001", BatchActionState.PLANNED, after = event("英语", "pt001"), token = "tok-1")))

        importManager.recover()

        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals("应通过 token 找回并补写 DB_APPLIED", BatchActionState.DB_APPLIED, act.state)
        assertEquals(cid, act.calendarEventIdAfter)
        val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
        assertEquals("managed 应补写事件 ID", cid, me.calendarEventId)
        assertEquals("不得重复创建，系统事件仍为 1 条", 1, gateway.events.size)
        assertEquals("不得再次调用 insert", 0, gateway.insertCalls)
    }

    @Test
    fun `撤销DELETE重建成功但ID未落库时按token找回且不重复创建`() = runTest {
        // 撤销 DELETE = 重建事件；系统已存在重建后的事件（带 token），ID 未落库
        val before = event("英语", "pt001")
        val cid = preseedEventWithToken("tok-2")
        val mid = insertManaged("pt001", null, status = "CANCELLED")
        val batchId = seedBatch(
            BatchPhase.UNDOING,
            listOf(action(BatchActionType.DELETE, "pt001", BatchActionState.DB_APPLIED, before = before, cidBefore = 100L, cidAfter = null, token = "tok-2", mid = mid))
        )

        importManager.recover()

        val batch = db.importBatchDao().getById(batchId)!!
        assertEquals(BatchPhase.UNDONE, batch.phase)
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals(BatchActionState.REVERTED, act.state)
        val me = db.managedEventDao().getById(mid)!!
        assertEquals("重建后应写 ACTIVE 并记录新 ID", "ACTIVE", me.status)
        assertEquals(cid, me.calendarEventId)
        assertEquals("不得重复创建，系统事件仍为 1 条", 1, gateway.events.size)
        assertEquals(0, gateway.insertCalls)
    }

    // ---------- R3：DELETE 恢复需同步 managed ----------

    @Test
    fun `DELETE恢复后managed变为CANCELLED并清空calendarEventId`() = runTest {
        val e = event("英语", "pt001")
        val cid = gateway.insertEvent(EventSource.PART_TIME, e)!!
        val mid = insertManaged("pt001", cid)
        val batchId = seedBatch(BatchPhase.APPLYING, listOf(action(BatchActionType.DELETE, "pt001", BatchActionState.PLANNED, before = e, cidBefore = cid, mid = mid)))

        importManager.recover()

        val me = db.managedEventDao().getById(mid)!!
        assertEquals("DELETE 恢复后 managed 应为 CANCELLED", "CANCELLED", me.status)
        assertEquals("DELETE 恢复后 managed 应清空 calendarEventId", null, me.calendarEventId)
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals(BatchActionState.DB_APPLIED, act.state)
        assertFalse("系统事件应已被删除", gateway.eventExists(cid))
    }

    // ---------- R4：DELETE 失败不得记作成功 ----------

    @Test
    fun `DELETE返回失败且事件仍存在时批次进入PARTIAL`() = runTest {
        val e = event("英语", "pt001")
        val cid = gateway.insertEvent(EventSource.PART_TIME, e)!!
        val mid = insertManaged("pt001", cid)
        val batchId = seedBatch(BatchPhase.APPLYING, listOf(action(BatchActionType.DELETE, "pt001", BatchActionState.PLANNED, before = e, cidBefore = cid, mid = mid)))
        gateway.nextDeleteShouldFail = true

        importManager.recover()

        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals("删除失败应记 FAILED", BatchActionState.FAILED, act.state)
        assertEquals("CALENDAR_DELETE_NOT_EFFECTIVE", act.errorCode)
        val batch = db.importBatchDao().getById(batchId)!!
        assertEquals("存在失败动作时批次不得为 APPLIED", BatchPhase.PARTIAL, batch.phase)
        val me = db.managedEventDao().getById(mid)!!
        assertEquals("失败时应保留原映射状态", "ACTIVE", me.status)
        assertEquals(cid, me.calendarEventId)
        assertTrue("失败时系统事件应保留", gateway.eventExists(cid))
    }

    // ---------- R6：批次阶段集中汇总 ----------

    @Test
    fun `recoverCreate动作FAILED时批次不得进入APPLIED`() = runTest {
        // CREATE 动作 PLANNED + token，但 Calendar insert 失败 → recoverCreate 应标 FAILED，批次不得 APPLIED
        val batchId = seedBatch(BatchPhase.APPLYING, listOf(action(BatchActionType.CREATE, "pt001", BatchActionState.PLANNED, after = event("英语", "pt001"), token = "tok-fail")))
        gateway.nextInsertShouldFail = true

        importManager.recover()

        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals(BatchActionState.FAILED, act.state)
        val batch = db.importBatchDao().getById(batchId)!!
        assertNotEquals("存在 FAILED 动作时批次不得为 APPLIED", BatchPhase.APPLIED, batch.phase)
        assertTrue("批次应进入 PARTIAL", batch.phase == BatchPhase.PARTIAL)
    }

    // ---------- 步骤 2/3 完成标准：崩溃重启后幂等 ----------

    @Test
    fun `CREATE写入Calendar后模拟崩溃恢复后仍只有一条事件`() = runTest {
        val e = event("英语", "pt001")
        // commit 正常导入，但 insert 成功后模拟进程中断（Calendar 已写、Room 未写回）
        gateway.crashAfterNextInsert = true
        runCatching { importManager.commit(preview(listOf(e)), null, emptySet()) }

        // 中断后：系统事件 1 条；action 仍 PLANNED 且已带 token；managed 未写
        assertEquals(1, gateway.events.size)
        val stuckAction = db.batchEventActionDao().getByStates(listOf(BatchActionState.PLANNED)).first()
        assertTrue("PLANNED action 应已带 operation token", stuckAction.operationToken != null)

        // 模拟重启：把批次时间改旧，触发 recover
        val batch = db.importBatchDao().getLatestByFileHash("f-hash")!!
        db.importBatchDao().update(batch.copy(createdAt = System.currentTimeMillis() - 10 * 60 * 1000L))
        importManager.recover()

        // 不重复创建：仍 1 条；managed 补写；action DB_APPLIED
        assertEquals(1, gateway.events.size)
        assertEquals("全程只调用一次 insert", 1, gateway.insertCalls)
        val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
        assertEquals(gateway.events.keys.first(), me.calendarEventId)
        val recovered = db.batchEventActionDao().getByStates(listOf(BatchActionState.DB_APPLIED)).first()
        assertEquals(gateway.events.keys.first(), recovered.calendarEventIdAfter)
    }

    @Test
    fun `重复调用recover三次不增加事件不改变结果`() = runTest {
        val cid = preseedEventWithToken("tok-r")
        val batchId = seedBatch(BatchPhase.APPLYING, listOf(action(BatchActionType.CREATE, "pt001", BatchActionState.PLANNED, after = event("英语", "pt001"), token = "tok-r")))

        importManager.recover()
        importManager.recover()
        importManager.recover()

        assertEquals("系统事件始终只有 1 条", 1, gateway.events.size)
        assertEquals("全程未重复插入", 0, gateway.insertCalls)
        val me = db.managedEventDao().getByIdentity("PART_TIME", "pt001")!!
        assertEquals(cid, me.calendarEventId)
        val act = db.batchEventActionDao().getByBatch(batchId).first()
        assertEquals(BatchActionState.DB_APPLIED, act.state)
    }
}
