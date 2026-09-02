package com.vivio.coursecalendar.domain.parser

import com.vivio.coursecalendar.domain.import.EventFingerprint
import com.vivio.coursecalendar.domain.model.CourseStatus
import com.vivio.coursecalendar.domain.model.EventSource
import com.vivio.coursecalendar.domain.model.UnifiedEvent
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 兼职课表解析器（交接包《03》第四节）。
 *
 * 已观察结构：文件扩展名 .xls、实际内容是 OOXML 工作簿；主要数据位于
 * 「学员排课」工作表；包含主讲、学员、课节名称、课节 ID、状态、精确上课时间等。
 *
 * 状态规则：
 * - 待上课：默认导入并提醒；
 * - 已结课：默认不写入未来日历（作为历史）；
 * - 取消：按取消处理；
 * - 未知状态：不猜测，进入预览要求用户确认。
 */
class PartTimeScheduleParser : ScheduleParser {

    override val source = EventSource.PART_TIME

    private data class HeaderMap(
        val id: Int? = null,
        val title: Int? = null,
        val student: Int? = null,
        val teacher: Int? = null,
        val start: Int? = null,
        val end: Int? = null,
        val status: Int? = null,
        val type: Int? = null
    )

    override fun parse(workbook: Workbook, context: ParseContext): ParseResult {
        val sheet = findPartTimeSheet(workbook) ?: return ParseResult.Failure("未找到「学员排课」工作表")
        val (headerRow, header) = findHeader(sheet) ?: return ParseResult.Failure("未找到表头行")

        val events = mutableListOf<UnifiedEvent>()
        val warnings = mutableListOf<String>()
        var unknownStatusCount = 0

        for (r in headerRow + 1..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            if (rowHasAnyData(row).not()) continue

            val recordId = header.id?.let { cellText(row.getCell(it)) }?.takeIf { it.isNotBlank() }
            val courseName = header.title?.let { cellText(row.getCell(it)) }?.trim().orEmpty()
            val student = header.student?.let { cellText(row.getCell(it)) }?.trim()
            val teacher = header.teacher?.let { cellText(row.getCell(it)) }?.trim()
            val statusRaw = header.status?.let { cellText(row.getCell(it)) }?.trim()
            val type = header.type?.let { cellText(row.getCell(it)) }?.trim()

            if (courseName.isBlank() && student.isNullOrBlank() && recordId.isNullOrBlank()) continue

            // 起止时间：优先「上课时间」列中的范围拆分（如 2026-08-31 08:40:00-09:10:00），
            // 其次独立结束时间列
            val timeRange = header.start?.let { parseTimeRange(sheet, r, it) }
            val start = timeRange?.first ?: header.start?.let { parseDateTime(sheet, r, it) }
            var end = timeRange?.second ?: header.end?.let { parseDateTime(sheet, r, it) }

            // 状态映射
            val status = mapStatus(statusRaw)
            if (status == CourseStatus.UNKNOWN) unknownStatusCount++

            // 指纹：优先课节 ID
            val fp = recordId?.let { EventFingerprint.partTimeWithId(it) }
                ?: start?.let { s ->
                    EventFingerprint.partTimeFallback(s, end ?: s.plusMinutes(45), student, courseName)
                } ?: continue

            var blocker: String? = null
            var effectiveStart = start
            var effectiveEnd = end

            if (start == null) {
                blocker = "缺少上课开始时间"
            } else {
                if (end == null) {
                    val minutes = parseMinutes(courseName, type) ?: 45
                    effectiveEnd = start.plusMinutes(minutes.toLong())
                    warnings.add("「$courseName」无结束时间，默认时长 ${minutes} 分钟")
                }
            }
            if (status == CourseStatus.UNKNOWN) {
                blocker = "课节状态未知（$statusRaw），请确认"
            }

            val description = buildString {
                student?.let { append("学员：").append(it).append('\n') }
                teacher?.let { append("主讲：").append(it).append('\n') }
                type?.let { append("类型：").append(it).append('\n') }
                statusRaw?.let { append("状态：").append(it) }
            }.ifBlank { null }

            events += UnifiedEvent(
                source = EventSource.PART_TIME,
                sourceRecordId = recordId,
                title = courseName.ifBlank { student ?: recordId ?: "未命名课节" },
                description = description,
                startTime = effectiveStart ?: LocalDateTime.of(2000, 1, 1, 0, 0),
                endTime = effectiveEnd ?: LocalDateTime.of(2000, 1, 1, 1, 0),
                status = status,
                sourceFileHash = context.sourceFileHash,
                eventFingerprint = fp,
                rawText = buildString {
                    listOf(courseName, student, teacher, statusRaw).filter { !it.isNullOrBlank() }
                        .forEach { append(it).append('\n') }
                }.trim(),
                blocker = blocker
            )
        }

        if (events.isEmpty()) {
            return ParseResult.Failure("未解析到任何兼职课节记录")
        }
        if (unknownStatusCount > 0) {
            warnings.add("$unknownStatusCount 条课节状态未知，已在预览中标记待确认")
        }
        return ParseResult.Success(events, warnings)
    }

    private fun findPartTimeSheet(workbook: Workbook): Sheet? {
        for (i in 0 until workbook.numberOfSheets) {
            val name = workbook.getSheetName(i) ?: ""
            if (name.contains("学员排课") || name.contains("排课")) {
                return workbook.getSheetAt(i)
            }
        }
        return workbook.getSheetAt(0)
    }

    private fun findHeader(sheet: Sheet): Pair<Int, HeaderMap>? {
        for (r in 0..minOf(10, sheet.lastRowNum)) {
            val row = sheet.getRow(r) ?: continue
            val map = detectHeaderColumns(row)
            // 至少识别出 2 个关键列才认定为表头
            val keyCols = listOf(map.id, map.title, map.start, map.status).filterNotNull().size
            if (keyCols >= 2) return r to map
        }
        return null
    }

    private fun detectHeaderColumns(row: org.apache.poi.ss.usermodel.Row): HeaderMap {
        var map = HeaderMap()
        for (c in 0 until row.lastCellNum.toInt()) {
            val text = cellText(row.getCell(c)).replace(" ", "").lowercase()
            if (text.isBlank()) continue

            // 内部 ID 列（学员ID/主讲ID/班级ID/课程ID 等）：跳过，
            // 既避免误映射，也符合隐私规则（UI 与日志不出现内部 ID）。
            val isLessonId = (text.contains("课节") || text.contains("记录") || text.contains("编号")) &&
                (text.contains("id") || text.contains("number"))
            val isOtherId = (text.contains("id") || text.contains("number")) && !isLessonId
            if (isOtherId) continue

            map = when {
                map.id == null && isLessonId -> map.copy(id = c)
                map.title == null && (text.contains("课节名称") || text.contains("课程名称")) ->
                    map.copy(title = c)
                map.student == null && (text.contains("学员姓名") || text.contains("学生姓名") || text.contains("学员")) ->
                    map.copy(student = c)
                map.teacher == null && (text.contains("主讲") || text.contains("教师") || text.contains("老师")) ->
                    map.copy(teacher = c)
                map.status == null && text.contains("状态") -> map.copy(status = c)
                map.type == null && text.contains("类型") -> map.copy(type = c)
                map.start == null && (text.contains("上课时间") || text.contains("开始")) ->
                    map.copy(start = c)
                map.end == null && (text.contains("结束") || text.contains("下课")) -> map.copy(end = c)
                else -> map
            }
        }
        return map
    }

    private fun rowHasAnyData(row: org.apache.poi.ss.usermodel.Row): Boolean {
        for (c in 0 until row.lastCellNum.toInt()) {
            if (cellText(row.getCell(c)).isNotBlank()) return true
        }
        return false
    }

    private fun cellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.localDateTimeCellValue.format(DateTimeFormatter.ofPattern("yyyy/M/d H:mm"))
                } else cell.numericCellValue.toLong().toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> cell.cachedFormulaResultType.let {
                if (it == CellType.STRING) cell.stringCellValue else ""
            }
            else -> ""
        }
    }

    // ---- 时间解析 ----

    private val datetimePatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy-M-d")
    )

    private val timeOnlyPatterns = listOf(
        DateTimeFormatter.ofPattern("H:mm:ss"),
        DateTimeFormatter.ofPattern("H:mm")
    )

    /** 「上课时间」列的复合范围：2026-08-31 08:40:00-09:10:00 */
    private val timeRangePattern = Regex(
        "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*[-~至]\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)"
    )

    private fun parseTimeRange(sheet: Sheet, rowIdx: Int, col: Int): Pair<LocalDateTime, LocalDateTime>? {
        val cell = sheet.getRow(rowIdx)?.getCell(col) ?: return null
        if (cell.cellType != CellType.STRING) return null
        val text = cellText(cell).trim()
        if (text.isBlank()) return null
        val m = timeRangePattern.find(text) ?: return null
        val start = parseDateTimeText(m.groupValues[1]) ?: return null
        val endTime = parseTimeOnly(m.groupValues[2]) ?: return null
        val end = LocalDateTime.of(start.toLocalDate(), endTime)
        if (!end.isAfter(start)) return null
        return start to end
    }

    private fun parseDateTime(sheet: Sheet, rowIdx: Int, col: Int): LocalDateTime? {
        val cell = sheet.getRow(rowIdx)?.getCell(col) ?: return null
        if (cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.localDateTimeCellValue
        }
        return parseDateTimeText(cellText(cell))
    }

    private fun parseDateTimeText(text: String): LocalDateTime? {
        val cleaned = text.replace(Regex("[（(]周[一二三四五六日][)）]"), "").trim()
        if (cleaned.isBlank()) return null
        for (fmt in datetimePatterns) {
            runCatching { return LocalDateTime.parse(cleaned, fmt) }
        }
        return null
    }

    private fun parseTimeOnly(text: String): LocalTime? {
        for (fmt in timeOnlyPatterns) {
            runCatching { return LocalTime.parse(text.trim(), fmt) }
        }
        return null
    }

    private fun mapStatus(raw: String?): CourseStatus {
        if (raw.isNullOrBlank()) return CourseStatus.UNKNOWN
        return when {
            raw.contains("待上课") -> CourseStatus.PENDING
            raw.contains("已结课") || raw.contains("结课") || raw.contains("已完成") -> CourseStatus.COMPLETED
            raw.contains("取消") || raw.contains("停课") -> CourseStatus.CANCELLED
            else -> CourseStatus.UNKNOWN
        }
    }

    private fun parseMinutes(vararg sources: String?): Int? {
        val regex = Regex("(\\d+)\\s*分钟")
        for (s in sources) {
            if (s == null) continue
            regex.find(s)?.let { return it.groupValues[1].toInt() }
        }
        return null
    }
}
