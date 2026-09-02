package com.vivio.coursecalendar.domain.model

import java.time.LocalDateTime

/** 事件来源 */
enum class EventSource { UNIVERSITY, PART_TIME }

/** 课节状态（主要来自兼职课表） */
enum class CourseStatus {
    /** 待上课：默认导入并提醒 */
    PENDING,

    /** 已结课：默认不写入未来日历，仅作历史 */
    COMPLETED,

    /** 明确取消 */
    CANCELLED,

    /** 未知状态：不猜测，进入人工确认 */
    UNKNOWN
}

/** 导入结果状态 */
enum class EventState {
    /** 新事件 */
    NEW,

    /** 与已导入事件一致 */
    UNCHANGED,

    /** 内容或时间发生变化 */
    MODIFIED,

    /** 新课表明确标记取消 */
    CANCELLED,

    /** 旧事件未出现在新文件中，仅提示不删除 */
    MISSING,

    /** 与其他事件时间冲突 */
    CONFLICT,

    /** 缺少时间或核心字段，默认不导入 */
    INVALID
}

/**
 * 统一事件模型：校内课表与兼职课表解析后的共同表示。
 * 对应交接包《03-数据模型与解析规范》第一节。
 */
data class UnifiedEvent(
    val id: String = "",
    val source: EventSource,
    val sourceRecordId: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: CourseStatus = CourseStatus.PENDING,
    val reminderMinutes: Int? = null,
    val sourceFileHash: String? = null,
    val eventFingerprint: String = "",
    val calendarEventId: Long? = null,
    val rawText: String? = null,
    /** 校内：大节编号 1..5 */
    val periodIndex: Int? = null,
    /** 校内：周次区间，如 "1-16" */
    val weekRange: String? = null,
    /** 校内：交叉校验用的节次串，如 "1-0102" */
    val periodCode: String? = null,
    val semester: String? = null,
    /** 不可直接导入的原因（缺年份日期、未知状态、缺作息时间等）；非空时默认排除，用户确认后可强制导入 */
    val blocker: String? = null
) {
    /** 生成应用内部稳定 ID：源 + 指纹前缀 */
    fun withId(): UnifiedEvent =
        if (id.isBlank()) copy(id = "evt-${eventFingerprint.hashCode().toUInt().toString(16)}") else this
}
