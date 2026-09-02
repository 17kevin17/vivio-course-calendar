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

/** 导入结果状态（交接包《03》第五节差异结果） */
enum class EventState {
    /** 找不到旧 identityKey */
    NEW,

    /** identityKey 相同且 contentHash 相同 */
    UNCHANGED,

    /** identityKey 相同且 contentHash 不同 */
    MODIFIED,

    /** 上游明确标记取消 */
    CANCELLED,

    /** 旧事件本次未出现，只提示不自动删除 */
    MISSING,

    /** 多个旧事件都可能匹配，用户确认 */
    AMBIGUOUS,

    /** 与其他事件时间冲突 */
    CONFLICT,

    /** 时间或作息缺失，默认排除 */
    INVALID
}

/**
 * 统一事件模型：校内课表与兼职课表解析后的共同表示。
 *
 * 身份与内容分离（交接包《03》第一节）：
 * - identityKey：跨批次稳定身份，回答「是不是同一个逻辑事件」；
 * - contentHash：当前可见内容哈希，回答「内容是否变化」。
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
    /** 跨批次稳定身份 */
    val identityKey: String = "",
    /** 内容哈希 */
    val contentHash: String = "",
    val calendarEventId: Long? = null,
    val rawText: String? = null,
    /** 校内：大节编号 1..5 */
    val periodIndex: Int? = null,
    /** 校内：周次区间，如 "1-16" */
    val weekRange: String? = null,
    /** 校内：交叉校验用的节次串，如 "1-0102" */
    val periodCode: String? = null,
    /** 校内：节次码中的周次（periodCode 前缀），如 1 */
    val weekNo: Int? = null,
    val semester: String? = null,
    /** 不可直接导入的原因（缺年份日期、未知状态、缺作息时间等）；非空时默认排除 */
    val blocker: String? = null
) {
    /** 生成应用内部稳定 ID：源 + 身份前缀 */
    fun withId(): UnifiedEvent =
        if (id.isBlank()) copy(id = "evt-${identityKey.hashCode().toUInt().toString(16)}") else this

    /**
     * v2 F7：应用用户确认的最终提醒并重新计算 contentHash。
     * 提醒纳入最终哈希：改提醒 → 哈希变化 → MODIFIED；撤销可恢复旧提醒。
     *
     * 提醒三态语义（v2 R 步骤6）：
     * - 当前产品流程每次导入均由 UI 显式指定最终提醒，故参数即最终值；
     * - `null` = 显式关闭提醒（清除已有提醒），`10/20/30` = 具体分钟值；
     * - 解析器不携带默认提醒，managed_event 持久化最终 reminderMinutes；
     * - 如需"继承（不改变）"语义，需在 UI/调用方引入显式三态枚举后再扩展。
     * teacher/student 由解析器写入，用于按来源重建内容哈希。
     */
    fun withFinalReminder(reminderMinutes: Int?): UnifiedEvent {
        val effective = reminderMinutes
        val hash = when (source) {
            EventSource.UNIVERSITY -> com.vivio.coursecalendar.domain.identity.EventIdentity.universityContentHash(
                title = title,
                teacher = teacher,
                location = location,
                start = startTime,
                end = endTime,
                reminderMinutes = effective,
                status = status.name
            )
            EventSource.PART_TIME -> com.vivio.coursecalendar.domain.identity.EventIdentity.partTimeContentHash(
                title = title,
                student = student,
                status = status.name,
                start = startTime,
                end = endTime,
                location = location,
                reminderMinutes = effective
            )
        }
        return copy(reminderMinutes = effective, contentHash = hash)
    }
}

/** 解析器写入的展示用字段（v2 F7 提醒哈希重建需要原始输入）。 */
val UnifiedEvent.teacher: String?
    get() = description?.lineSequence()?.firstOrNull { it.startsWith("教师：") }?.removePrefix("教师：")
        ?: rawText?.lineSequence()?.firstOrNull { it.startsWith("教师：") }?.removePrefix("教师：")

val UnifiedEvent.student: String?
    get() = description?.lineSequence()?.firstOrNull { it.startsWith("学员：") }?.removePrefix("学员：")
        ?: rawText?.lineSequence()?.firstOrNull { it.startsWith("学员：") }?.removePrefix("学员：")
