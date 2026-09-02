package com.vivio.coursecalendar.domain.import

import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent

/**
 * 冲突检测：校内与兼职课程、同一天同一大节多课程描述，
 * 时间重叠时标记 CONFLICT，仅警告、允许用户决定。
 */
object ConflictDetector {

    data class ConflictInfo(val conflictWith: List<String>)

    fun detect(events: List<UnifiedEvent>): Map<String, ConflictInfo> {
        val map = mutableMapOf<String, ConflictInfo>()
        val candidates = events
            .filter { it.blocker == null && it.startTime.isBefore(it.endTime) }
            .sortedBy { it.startTime }

        for (i in candidates.indices) {
            val a = candidates[i]
            for (j in i + 1 until candidates.size) {
                val b = candidates[j]
                if (!a.startTime.isBefore(b.endTime) || !b.startTime.isBefore(a.endTime)) continue
                val keyA = a.identityKey
                val keyB = b.identityKey
                map.mergeConflict(keyA, b.displayTitle())
                map.mergeConflict(keyB, a.displayTitle())
            }
        }
        return map
    }

    private fun MutableMap<String, ConflictInfo>.mergeConflict(key: String, otherTitle: String) {
        if (key.isBlank()) return
        val cur = getOrPut(key) { ConflictInfo(emptyList()) }
        put(key, ConflictInfo(cur.conflictWith + otherTitle))
    }

    private fun UnifiedEvent.displayTitle(): String =
        if (source == EventSource.PART_TIME) "[兼职] $title" else "[校内] $title"
}
