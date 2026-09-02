package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.data.local.dao.ManagedEventDao
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.schedule.Season

/** 差异项：单条事件及其导入状态、冲突、排除标记。 */
data class DiffItem(
    val event: UnifiedEvent,
    val state: EventState,
    /** 匹配到的 managed_event ID（MODIFIED 时用于定位） */
    val existingManagedId: Long? = null,
    /** 匹配到的系统日历事件 ID */
    val existingCalendarEventId: Long? = null,
    val conflictWith: List<String> = emptyList(),
    val excluded: Boolean = false
)

/** 新课表中消失的旧事件（仅提示，不自动删除） */
data class MissingEventInfo(
    val identityKey: String,
    val title: String,
    val startMillis: Long
)

/** 差异计划：纯内存生成，UI 预览确认后才能执行。 */
data class DiffPlan(
    val items: List<DiffItem>,
    val missing: List<MissingEventInfo>,
    val warnings: List<String>
) {
    val counts: Map<EventState, Int> = items.groupingBy { it.state }.eachCount()
}

/**
 * 差异计算（交接包《03》第五、六节）。
 *
 * | 条件 | 结果 | 操作 |
 * |---|---|---|
 * | 找不到旧 identityKey | NEW | CREATE |
 * | identityKey 相同且 contentHash 相同 | UNCHANGED | NOOP |
 * | identityKey 相同且 contentHash 不同 | MODIFIED | UPDATE |
 * | 上游明确标记取消 | CANCELLED | DELETE |
 * | 旧事件本次未出现 | MISSING | 只提示 |
 * | 时间或作息缺失 | INVALID | 默认排除 |
 */
class DiffEngine(private val managedEventDao: ManagedEventDao) {

    suspend fun compute(events: List<UnifiedEvent>, source: EventSource, season: Season?): DiffPlan {
        val warnings = mutableListOf<String>()

        val items = events.map { event ->
            val state = when {
                event.blocker != null -> EventState.INVALID
                event.identityKey.isBlank() -> EventState.INVALID
                else -> {
                    val existing = managedEventDao.getByIdentity(source.name, event.identityKey)
                    when {
                        existing == null -> EventState.NEW
                        existing.contentHash == event.contentHash -> EventState.UNCHANGED
                        else -> EventState.MODIFIED
                    }
                }
            }
            val existing = if (state == EventState.MODIFIED || state == EventState.UNCHANGED) {
                managedEventDao.getByIdentity(source.name, event.identityKey)
            } else null

            val excluded = when {
                event.blocker != null -> true
                // 已结课兼职课：默认不写入未来日历，仅作历史
                source == EventSource.PART_TIME && event.status == CourseStatus.COMPLETED -> true
                else -> false
            }

            DiffItem(
                event = event,
                state = state,
                existingManagedId = existing?.id,
                existingCalendarEventId = existing?.calendarEventId,
                excluded = excluded
            )
        }

        // 冲突检测（仅针对可导入事件）
        val conflicts = ConflictDetector.detect(
            items.filter { it.state != EventState.INVALID && !it.excluded }.map { it.event }
        )
        val withConflicts = items.map { item ->
            if (item.state == EventState.INVALID || item.excluded) item
            else item.copy(conflictWith = conflicts[item.event.identityKey]?.conflictWith ?: emptyList())
        }

        // 旧事件未出现在新文件（MISSING，仅提示）
        val newKeys = events.map { it.identityKey }.filter { it.isNotBlank() }.toSet()
        val missing = managedEventDao.getActiveBySource(source.name)
            .filter { it.identityKey !in newKeys && it.status != "CANCELLED" }
            .map { MissingEventInfo(it.identityKey, it.title, it.startMillis) }

        if (missing.isNotEmpty()) {
            warnings.add("${missing.size} 条旧事件未出现在新文件（仅提示，不自动删除）")
        }

        return DiffPlan(withConflicts, missing, warnings)
    }
}
