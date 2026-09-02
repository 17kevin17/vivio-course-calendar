package com.vivio.coursecalendar.domain.import

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 事件内容去重指纹（交接包《03》第二节）。
 *
 * - 校内课程：source + 日期 + 大节编号 + 规范化课程名 + 规范化教室。
 *   教师变化视为事件修改，不应导致重复事件。
 * - 兼职课程：优先使用表格课节 ID；无 ID 时退化为
 *   source + 开始时间 + 结束时间 + 学员 + 课节名称。
 */
object EventFingerprint {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 规范化：全角转半角、去空白、转大写，用于稳定比较 */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            when {
                // 全角字母数字 -> 半角
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                code == 0x3000 -> sb.append(' ') // 全角空格
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), "").uppercase()
    }

    fun university(
        date: LocalDateTime,
        periodIndex: Int,
        courseName: String,
        location: String?
    ): String = listOf(
        "U",
        date.format(DATE_FMT),
        periodIndex.toString(),
        normalize(courseName),
        normalize(location)
    ).joinToString("|")

    fun partTimeWithId(sourceRecordId: String): String =
        "P|ID|" + normalize(sourceRecordId)

    fun partTimeFallback(
        start: LocalDateTime,
        end: LocalDateTime,
        student: String?,
        title: String
    ): String = listOf(
        "P",
        "RAW",
        start.format(DATE_FMT),
        end.format(DATE_FMT),
        normalize(student),
        normalize(title)
    ).joinToString("|")
}
