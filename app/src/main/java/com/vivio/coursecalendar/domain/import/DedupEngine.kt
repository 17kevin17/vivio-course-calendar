package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.domain.model.EventState
import com.vivio.coursecalendar.domain.model.UnifiedEvent

/** 已导入事件的精简视图（用于去重比较）。 */
data class ExistingMapping(
    val title: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long
)

/**
 * 去重与差异比较（交接包《02》再次导入阶段）。
 *
 * - 同一文件重复导入：按事件指纹查已有映射，一致 → UNCHANGED（跳过），
 *   内容或时间变化 → MODIFIED（更新原事件）。
 * - 指纹相同但内容变化视为修改而非新建。
 */
class DedupEngine(
    private val findExisting: suspend (String) -> List<ExistingMapping>
) {

    /** 返回 指纹 → 状态 映射 */
    suspend fun evaluate(events: List<UnifiedEvent>): Map<String, EventState> {
        val result = mutableMapOf<String, EventState>()
        for (event in events) {
            val fp = event.eventFingerprint
            if (fp.isBlank()) {
                result[fp] = EventState.INVALID
                continue
            }
            val existing = findExisting(fp).firstOrNull()
            if (existing == null) {
                result[fp] = EventState.NEW
                continue
            }
            result[fp] = if (sameContent(existing, event)) EventState.UNCHANGED else EventState.MODIFIED
        }
        return result
    }

    private fun sameContent(existing: ExistingMapping, event: UnifiedEvent): Boolean {
        val start = event.startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = event.endTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return existing.title == event.title &&
            existing.location == event.location &&
            existing.startMillis == start &&
            existing.endMillis == end
    }
}
