package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.local.entity.ManagedStatus
import com.vivio.coursecalendar.domain.import.DiffEngine
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

/** 身份差异计算（交接包《05》P0 身份与差异用例）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var engine: DiffEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
}
