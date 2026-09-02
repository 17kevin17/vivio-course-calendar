package com.vivio.coursecalendar.domain.parser

import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import com.vivio.coursecalendar.domain.schedule.SchedulePeriod
import com.vivio.coursecalendar.domain.schedule.Season
import org.apache.poi.ss.usermodel.Workbook

/** 解析上下文：文件指纹与作息表（兼职课表不使用作息表）。 */
data class ParseContext(
    val sourceFileHash: String,
    val season: Season? = null,
    val schedule: ScheduleTable? = null
)

/**
 * 作息表：大节编号 → 起止时间。某个大节未配置时返回 null，
 * 解析器需将该事件标记为待确认（不可直接导入）。
 */
data class ScheduleTable(
    val season: Season,
    val periods: Map<Int, SchedulePeriod>
) {
    fun period(no: Int): SchedulePeriod? = periods[no]
}

/** 解析结果 */
sealed interface ParseResult {
    data class Success(
        val events: List<UnifiedEvent>,
        val warnings: List<String> = emptyList()
    ) : ParseResult

    data class Failure(val message: String) : ParseResult
}

/** 课表解析器统一接口：校内网格与兼职明细各自独立实现。 */
interface ScheduleParser {
    val source: EventSource
    fun parse(workbook: Workbook, context: ParseContext): ParseResult
}
