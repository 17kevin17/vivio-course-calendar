package com.vivio.coursecalendar.domain.identity

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 事件身份与内容哈希（交接包《03》第一、三节）。
 *
 * 两个键必须分离：
 * - identityKey：回答「新旧两条记录是不是同一个逻辑事件」；
 * - contentHash：回答「同一逻辑事件的可见内容是否发生改变」。
 */
object EventIdentity {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    // ---- 兼职课程 ----

    /** 首选稳定身份：PART_TIME + 原始课节 ID（必须无精度损失地读取为字符串）。 */
    fun partTimeIdentityKey(sourceRecordId: String): String =
        "PART_TIME|" + Normalizer.compact(sourceRecordId)

    /** 无课节 ID 时的退化身份（低置信度匹配）：PART_TIME + 学生 + 课节名 + 原始开始日期。 */
    fun partTimeFallbackIdentityKey(student: String?, courseName: String, startDate: LocalDate): String =
        "PART_TIME|FALLBACK|" + listOf(
            Normalizer.compact(student),
            Normalizer.compact(courseName),
            startDate.format(DATE_FMT)
        ).joinToString("|")

    // ---- 校内课程 ----

    /**
     * 事件发生实例 key（v2 交接包 F1）：UNIVERSITY + 学期 + 规范化课程名 + 实际日期 + 节次码。
     * 同一课程在同一周、同一节次、但不同日期出现时身份不同，避免 distinctBy 静默丢事件。
     * 教师/教室变化不进入身份（内容变 → MODIFIED）。
     */
    fun universityOccurrenceKey(
        semester: String?,
        courseName: String,
        date: LocalDate,
        sectionCode: String?
    ): String = listOf(
        "UNIVERSITY",
        Normalizer.compact(semester),
        Normalizer.compact(courseName),
        date.format(DATE_FMT),
        Normalizer.compact(sectionCode)
    ).joinToString("|")

    /**
     * 校内事件候选匹配 key（调课候选，仅用于 MISSING 范围匹配提示）：
     * 学期 + 课程名 + 周次。多个候选时由用户确认，不自动合并。
     */
    fun universityCandidateKey(
        semester: String?,
        courseName: String,
        weekNo: Int?
    ): String = listOf(
        "UNIVERSITY",
        Normalizer.compact(semester),
        Normalizer.compact(courseName),
        weekNo?.toString()
    ).joinToString("|")

    /**
     * 校内一级确定性身份（保留）：UNIVERSITY + 学期 + 规范化课程名 + 原始周次 + 原始节次码。
     * 用于候选匹配/周次维度识别；不再用于事件实例唯一身份。
     */
    fun universityIdentityKey(
        semester: String?,
        courseName: String,
        weekNo: Int?,
        sectionCode: String?
    ): String = listOf(
        "UNIVERSITY",
        Normalizer.compact(semester),
        Normalizer.compact(courseName),
        weekNo?.toString(),
        Normalizer.compact(sectionCode)
    ).joinToString("|")

    // ---- 内容哈希 ----

    /** 内容哈希：覆盖所有需要同步到系统日历的字段。 */
    fun contentHash(vararg parts: String?): String {
        val joined = parts.map { Normalizer.compact(it) }.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 校内内容哈希：课程标题、教师、教室、日期、起止时间、提醒、状态。 */
    fun universityContentHash(
        title: String,
        teacher: String?,
        location: String?,
        start: LocalDateTime,
        end: LocalDateTime,
        reminderMinutes: Int?,
        status: String
    ): String = contentHash(
        title,
        teacher,
        location,
        start.format(TIME_FMT),
        end.format(TIME_FMT),
        reminderMinutes?.toString(),
        status
    )

    /** 兼职内容哈希：标题、学生展示名、状态、起止时间、地点、提醒。 */
    fun partTimeContentHash(
        title: String,
        student: String?,
        status: String,
        start: LocalDateTime,
        end: LocalDateTime,
        location: String?,
        reminderMinutes: Int?
    ): String = contentHash(
        title,
        student,
        status,
        start.format(TIME_FMT),
        end.format(TIME_FMT),
        location,
        reminderMinutes?.toString()
    )
}
