package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.schedule.Season

/** 预览项：单条事件及其导入状态、冲突、用户排除标记。 */
data class PreviewItem(
    val event: UnifiedEvent,
    var state: EventState = EventState.NEW,
    val conflictWith: List<String> = emptyList(),
    /** 用户在校对阶段排除该事件 */
    var excluded: Boolean = false,
    /** 撤销导入/更新时用于定位旧映射 */
    val oldMappingId: Long? = null
)

/** 导入预览结果：解析 + 去重 + 冲突检测之后，等待用户确认。 */
data class ImportPreview(
    val source: EventSource,
    val season: Season?,
    val items: List<PreviewItem>,
    val warnings: List<String>,
    val fileHash: String,
    val fileName: String,
    /** 旧事件未出现在新文件中（仅提示，不直接删除） */
    val missing: List<MissingEvent> = emptyList()
) {
    val counts: Map<EventState, Int> = items.groupingBy { it.state }.eachCount()
}

/** 新课表中消失的旧事件 */
data class MissingEvent(
    val identityKey: String,
    val title: String,
    val startMillis: Long
)

/** 导入（或更新、撤销）执行后的汇总。 */
data class CommitResult(
    val batchId: Long,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val deleted: Int,
    val invalid: Int,
    val failed: Int
)
