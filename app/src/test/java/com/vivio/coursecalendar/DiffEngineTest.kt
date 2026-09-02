package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.local.entity.ManagedStatus
import com.vivio.coursecalendar.domain.import.DiffEngine
import com.vivio.coursecalendar.domain.import.ImportScope
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.time.CourseTime
import java.time.LocalDate
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

/** 身份差异计算（交接包《05》P0 身份与差异用例）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var engine: DiffEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDb.inMemory(context)
        engine = DiffEngine(db.managedEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(key: String, hash: String, title: String = "课", blocker: String? = null) = UnifiedEvent(
        source = EventSource.PART_TIME,
        title = title,
        startTime = LocalDateTime.of(2026, 9, 1, 20, 0),
        endTime = LocalDateTime.of(2026, 9, 1, 20, 30),
        status = CourseStatus.PENDING,
        identityKey = key,
        contentHash = hash,
        blocker = blocker
    )

    private suspend fun seedManaged(key: String, hash: String, status: String = ManagedStatus.ACTIVE) {
        val now = System.currentTimeMillis()
        db.managedEventDao().insert(
            ManagedEventEntity(
                source = "PART_TIME",
                identityKey = key,
                contentHash = hash,
                sourceRecordId = null,
                calendarEventId = 100L,
                title = "课",
                location = null,
                description = null,
                startMillis = now,
                endMillis = now + 1800_000,
                status = status,
                lastSeenBatchId = 1,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    @Test
    fun `同身份同内容标记无变化`() = runTest {
        seedManaged("pt001", "hash-a")
        val plan = engine.compute(listOf(event("pt001", "hash-a")), EventSource.PART_TIME, null)
        assertEquals(EventState.UNCHANGED, plan.items[0].state)
    }

    @Test
    fun `兼职时间变化课节ID相同标记修改`() = runTest {
        seedManaged("pt001", "hash-a")
        val plan = engine.compute(listOf(event("pt001", "hash-b")), EventSource.PART_TIME, null)
        assertEquals(EventState.MODIFIED, plan.items[0].state)
        assertTrue(plan.items[0].existingManagedId != null)
    }

    @Test
    fun `新逻辑事件标记新增`() = runTest {
        seedManaged("pt001", "hash-a")
        val plan = engine.compute(listOf(event("pt999", "hash-x")), EventSource.PART_TIME, null)
        assertEquals(EventState.NEW, plan.items[0].state)
    }

    @Test
    fun `blocker事件标记异常并默认排除`() = runTest {
        val plan = engine.compute(listOf(event("pt001", "hash-a", blocker = "缺时间")), EventSource.PART_TIME, null)
        assertEquals(EventState.INVALID, plan.items[0].state)
        assertTrue(plan.items[0].excluded)
    }

    @Test
    fun `旧事件未出现标记缺失不删除`() = runTest {
        seedManaged("pt001", "hash-a")
        val plan = engine.compute(listOf(event("pt002", "hash-b")), EventSource.PART_TIME, null)
        assertEquals(1, plan.missing.size)
        assertEquals("pt001", plan.missing[0].identityKey)
    }

    @Test
    fun `已结课兼职默认排除`() = runTest {
        val e = event("pt001", "hash-a").copy(status = CourseStatus.COMPLETED)
        val plan = engine.compute(listOf(e), EventSource.PART_TIME, null)
        assertTrue(plan.items[0].excluded)
    }

    @Test
    fun `已存在事件收到取消标记为CANCELLED`() = runTest {
        seedManaged("pt001", "hash-a")
        val e = event("pt001", "hash-a").copy(status = CourseStatus.CANCELLED)
        val plan = engine.compute(listOf(e), EventSource.PART_TIME, null)
        assertEquals(EventState.CANCELLED, plan.items[0].state)
    }

    @Test
    fun `不存在事件收到取消不创建系统事件`() = runTest {
        val e = event("pt999", "hash-x").copy(status = CourseStatus.CANCELLED)
        val plan = engine.compute(listOf(e), EventSource.PART_TIME, null)
        // 本地不存在取消课 → 无操作且默认排除（不创建）
        assertEquals(EventState.UNCHANGED, plan.items[0].state)
        assertTrue(plan.items[0].excluded)
    }

    // ---- v2 F6 导入范围 ----

    private suspend fun seedManagedAt(
        key: String,
        hash: String,
        startMillis: Long,
        status: String = ManagedStatus.ACTIVE
    ) {
        db.managedEventDao().insert(
            ManagedEventEntity(
                source = if (key.startsWith("UNIVERSITY")) "UNIVERSITY" else "PART_TIME",
                identityKey = key,
                contentHash = hash,
                sourceRecordId = null,
                calendarEventId = 100L,
                title = "课",
                location = null,
                description = null,
                startMillis = startMillis,
                endMillis = startMillis + 1800_000,
                status = status,
                lastSeenBatchId = 1,
                createdAt = startMillis,
                updatedAt = startMillis
            )
        )
    }

    @Test
    fun `兼职局部日期范围不影响范围外事件`() = runTest {
        // 旧事件在 8 月（范围外）
        seedManagedAt("ptOld", "h-a", CourseTime.toMillis(LocalDateTime.of(2026, 8, 1, 20, 0)))
        val e = event("ptNew", "h-b")
        val scope = ImportScope(dateFrom = LocalDate.of(2026, 9, 1), dateTo = LocalDate.of(2026, 9, 1))
        val plan = engine.compute(listOf(e), EventSource.PART_TIME, null, scope)
        assertTrue("范围外旧事件不应标 MISSING", plan.missing.isEmpty())
    }

    @Test
    fun `兼职范围内缺失事件仍提示不自动删除`() = runTest {
        seedManagedAt("ptOld", "h-a", CourseTime.toMillis(LocalDateTime.of(2026, 9, 1, 20, 0)))
        val e = event("ptNew", "h-b")
        val scope = ImportScope(dateFrom = LocalDate.of(2026, 9, 1), dateTo = LocalDate.of(2026, 9, 5))
        val plan = engine.compute(listOf(e), EventSource.PART_TIME, null, scope)
        assertEquals(1, plan.missing.size)
        assertEquals("ptOld", plan.missing[0].identityKey)
        // 仅提示，不改变 managed status
        assertEquals(ManagedStatus.ACTIVE, db.managedEventDao().getByIdentity("PART_TIME", "ptOld")!!.status)
    }

    @Test
    fun `导入新学期不把旧学期标成MISSING`() = runTest {
        // 旧学期校内事件（身份含 2025-2026）
        val oldKey = "UNIVERSITY|2025-2026|课程|20260831|0102"
        seedManagedAt(oldKey, "h-a", CourseTime.toMillis(LocalDateTime.of(2025, 8, 31, 8, 0)))
        // 新学期事件
        val newKey = "UNIVERSITY|2026-2027|课程|20260901|0102"
        val e = UnifiedEvent(
            source = EventSource.UNIVERSITY,
            title = "课程",
            startTime = LocalDateTime.of(2026, 9, 1, 8, 0),
            endTime = LocalDateTime.of(2026, 9, 1, 8, 45),
            status = CourseStatus.PENDING,
            identityKey = newKey,
            contentHash = "h-b",
            semester = "2026-2027"
        )
        val scope = ImportScope(
            semester = "2026-2027",
            dateFrom = LocalDate.of(2026, 9, 1),
            dateTo = LocalDate.of(2026, 9, 1)
        )
        val plan = engine.compute(listOf(e), EventSource.UNIVERSITY, null, scope)
        assertTrue("旧学期事件不应标 MISSING", plan.missing.isEmpty())
    }
}
