package com.vivio.coursecalendar.domain.parser

import com.vivio.coursecalendar.domain.identity.EventIdentity
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
 * 校内课表解析器（交接包《03》第三节）。
 *
 * 已观察结构：老式二进制 .xls，主工作表为按周展开的网格，每个区块包含
 * 周次、日期、星期和第一至第五大节；有课单元格为多行文本
 * （课程名、教师、周次和节次、教室）。
 *
 * 布局假设（依据文档解析步骤）：日期在列方向（区块列头），大节在行标题，
 * 区块按周纵向排列。解析对布局做自适应：按日期行定位列，按行标题匹配大节。
 */
class UniversityScheduleParser : ScheduleParser {

    override val source = EventSource.UNIVERSITY

    private data class DateCell(val date: LocalDate, val hasYear: Boolean)

    override fun parse(workbook: Workbook, context: ParseContext): ParseResult {
        val sheet = if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
            ?: return ParseResult.Failure("工作簿为空")

        val merged = buildMergedValueMap(sheet)
        val warnings = mutableListOf<String>()

        // 学年推断：从标题（如「陈志杰2026-2027-1课表」）提取学年。
        // 日期缺少年份时：月份 >=9 取第一年，否则取第二年；无学年信息则不猜测。
        val yearPair = findSemesterYears(sheet, merged)
        val semester = yearPair?.let { "${it.first}-${it.second}" }

        // 1. 定位日期行（区块列头）：一行中 >=2 个可解析的日期
        val dateRows = mutableListOf<Pair<Int, Map<Int, DateCell>>>()
        for (r in 0..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val dates = mutableMapOf<Int, DateCell>()
            for (c in 0 until row.lastCellNum.toInt()) {
                val d = parseDateCell(sheet, r, c, merged, yearPair) ?: continue
                dates[c] = d
            }
            if (dates.size >= 2) dateRows.add(r to dates)
        }
        if (dateRows.isEmpty()) {
            return ParseResult.Failure("未找到带日期的课表网格，无法识别为校内课表")
        }

        // 2. 定位大节行：行首匹配「第X大节 / 第X节 / 1-7」
        val periodRows = mutableListOf<Pair<Int, Int>>()
        for (r in 0..sheet.lastRowNum) {
            val n = periodNumberAt(sheet, r, merged) ?: continue
            periodRows.add(r to n)
        }
        if (periodRows.isEmpty()) {
            return ParseResult.Failure("未找到大节行，无法识别为校内课表")
        }

        val events = mutableListOf<UnifiedEvent>()
        var skippedNoSchedule = 0

        // 3. 每个大节行归属其上方最近的日期行
        for ((periodRow, periodNo) in periodRows) {
            val nearest = dateRows.lastOrNull { it.first < periodRow } ?: continue
            for ((col, dateCell) in nearest.second) {
                val text = cellTextAt(sheet, periodRow, col, merged)
                if (text.isBlank()) continue
                val parsed = parseCourseCell(text)
                    ?: run {
                        warnings.add("第${periodRow + 1}行第${col + 1}列：无法解析的课程文本")
                        null
                    }
                if (parsed == null) continue

                val period = context.schedule?.period(periodNo)
                if (period == null) {
                    skippedNoSchedule++
                    warnings.add("「${parsed.title}」缺少第${periodNo}大节作息时间，未生成事件（请先配置作息）")
                    continue
                }

                val start = LocalDateTime.of(dateCell.date, period.start)
                val end = LocalDateTime.of(dateCell.date, period.end)
                // 身份：学期 + 课程名 + 实际日期 + 节次码（v2 F1 事件发生实例 key）。
                // 同一课程同周次同节次但不同日期 → 不同身份，避免 distinctBy 静默丢事件。
                val sectionCode = parsed.periodCode?.substringAfter('-')
                val weekNo = parsed.periodCode?.substringBefore('-')?.toIntOrNull()
                val identityKey = if (parsed.periodCode != null) {
                    EventIdentity.universityOccurrenceKey(semester, parsed.title, dateCell.date, sectionCode)
                } else {
                    // 无节次码（少见）：退化身份用日期+大节兜底，保证唯一
                    EventIdentity.universityOccurrenceKey(
                        semester, parsed.title, dateCell.date, "d$periodNo"
                    )
                }
                val contentHash = EventIdentity.universityContentHash(
                    title = parsed.title,
                    teacher = parsed.teacher,
                    location = parsed.location,
                    start = start,
                    end = end,
                    reminderMinutes = null,
                    status = CourseStatus.PENDING.name
                )
                events += UnifiedEvent(
                    source = EventSource.UNIVERSITY,
                    title = parsed.title,
                    description = buildString {
                        parsed.teacher?.let { append("教师：").append(it).append('\n') }
                        parsed.weekRange?.let { append("周次：").append(it).append('\n') }
                        parsed.periodCode?.let { append("节次：").append(it).append('\n') }
                    }.ifBlank { null },
                    location = parsed.location,
                    startTime = start,
                    endTime = end,
                    status = CourseStatus.PENDING,
                    sourceFileHash = context.sourceFileHash,
                    identityKey = identityKey,
                    contentHash = contentHash,
                    rawText = text,
                    periodIndex = periodNo,
                    weekRange = parsed.weekRange,
                    periodCode = parsed.periodCode,
                    weekNo = weekNo,
                    semester = semester,
                    blocker = if (dateCell.hasYear) null else "日期缺少年份，请确认（原始：${rawDateText(sheet, nearest.first, col, merged)}）"
                )
            }
        }

        if (events.isEmpty()) {
            return ParseResult.Failure("未解析到任何课程事件${if (skippedNoSchedule > 0) "（$skippedNoSchedule 条因作息未配置被跳过）" else ""}")
        }
        return ParseResult.Success(events, warnings)
    }

    // ---- 单元格文本 ----

    private fun cellTextAt(sheet: Sheet, r: Int, c: Int, merged: Map<Pair<Int, Int>, String>): String {
        merged[Pair(r, c)]?.let { return it }
        val row = sheet.getRow(r) ?: return ""
        val cell = row.getCell(c) ?: return ""
        return cellText(cell)
    }

    private fun rawDateText(sheet: Sheet, r: Int, c: Int, merged: Map<Pair<Int, Int>, String>): String {
        merged[Pair(r, c)]?.let { return it }
        val row = sheet.getRow(r) ?: return ""
        val cell = row.getCell(c) ?: return ""
        return cell.toString().trim()
    }

    private fun cellText(cell: Cell): String = when {
        cell.cellType == CellType.STRING -> cell.stringCellValue
        cell.cellType == CellType.NUMERIC -> {
            if (DateUtil.isCellDateFormatted(cell)) {
                cell.localDateTimeCellValue.format(DateTimeFormatter.ofPattern("yyyy/M/d"))
            } else cell.numericCellValue.toLong().toString()
        }
        cell.cellType == CellType.FORMULA -> cell.cachedFormulaResultType.let {
            if (it == CellType.STRING) cell.stringCellValue else ""
        }
        else -> ""
    }

    /** 构建合并单元格值映射：区域内的所有单元格取左上角值 */
    private fun buildMergedValueMap(sheet: Sheet): Map<Pair<Int, Int>, String> {
        val map = mutableMapOf<Pair<Int, Int>, String>()
        for (i in 0 until sheet.numMergedRegions) {
            val region = sheet.getMergedRegion(i)
            if (region.numberOfCells > 2000) continue // 防御异常大区域
            val value = cellTextAtNoMerge(sheet, region.firstRow, region.firstColumn) ?: continue
            for (r in region.firstRow..region.lastRow) {
                for (c in region.firstColumn..region.lastColumn) {
                    map[Pair(r, c)] = value
                }
            }
        }
        return map
    }

    private fun cellTextAtNoMerge(sheet: Sheet, r: Int, c: Int): String? {
        val row = sheet.getRow(r) ?: return null
        val cell = row.getCell(c) ?: return null
        return cellText(cell)
    }

    // ---- 日期解析 ----

    private val dateWithYear = Regex("(\\d{4})\\s*[年/\\\\-]\\s*(\\d{1,2})\\s*[月/\\\\-]\\s*(\\d{1,2})\\s*日?")
    private val dateWithoutYear = Regex("(\\d{1,2})\\s*[月/\\\\-]\\s*(\\d{1,2})\\s*日?")
    private val DATE_ONLY = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 从标题（如「陈志杰2026-2027-1课表」）提取学年起止年份。 */
    private val semesterYearsPattern = Regex("(\\d{4})\\s*[-—]\\s*(\\d{4})")

    private fun findSemesterYears(sheet: Sheet, merged: Map<Pair<Int, Int>, String>): Pair<Int, Int>? {
        for (r in 0..minOf(2, sheet.lastRowNum)) {
            val row = sheet.getRow(r) ?: continue
            for (c in 0 until row.lastCellNum.toInt()) {
                val text = merged[Pair(r, c)] ?: cellText(row.getCell(c)) ?: continue
                semesterYearsPattern.find(text)?.let {
                    val y1 = it.groupValues[1].toIntOrNull()
                    val y2 = it.groupValues[2].toIntOrNull()
                    if (y1 != null && y2 != null && y2 == y1 + 1) return y1 to y2
                }
            }
        }
        return null
    }

    private fun parseDateCell(
        sheet: Sheet,
        r: Int,
        c: Int,
        merged: Map<Pair<Int, Int>, String>,
        yearPair: Pair<Int, Int>?
    ): DateCell? {
        // 优先真实日期单元格
        val row = sheet.getRow(r)
        val cell = row?.getCell(c)
        if (cell != null && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            val local = cell.localDateTimeCellValue
            return DateCell(local.toLocalDate(), true)
        }
        val text = merged[Pair(r, c)] ?: (cell?.toString()?.trim() ?: return null)
        if (text.isBlank()) return null
        // 课程单元格为多行文本，含换行时不应被当作日期
        if (text.contains('\n')) return null

        dateWithYear.find(text)?.let {
            val y = it.groupValues[1].toInt()
            val m = it.groupValues[2].toInt()
            val d = it.groupValues[3].toInt()
            return runCatching { DateCell(LocalDate.of(y, m, d), true) }.getOrNull()
        }
        dateWithoutYear.find(text)?.let {
            val m = it.groupValues[1].toInt()
            val d = it.groupValues[2].toInt()
            // 有学年信息时按学年推断年份；否则不猜测（占位年 2000 + blocker）
            val year = yearPair?.let { (y1, y2) -> if (m >= 9) y1 else y2 }
            if (year != null) {
                return runCatching { DateCell(LocalDate.of(year, m, d), true) }.getOrNull()
            }
            return runCatching { DateCell(LocalDate.of(2000, m, d), false) }.getOrNull()
        }
        return null
    }

    // ---- 大节行识别 ----

    private val periodHeader = Regex("第\\s*[一二三四五六七1-7]\\s*[大节]?\\s*节?")
    private val bareNumber = Regex("^\\s*([1-7])\\s*$")

    private fun periodNumberAt(sheet: Sheet, r: Int, merged: Map<Pair<Int, Int>, String>): Int? {
        val text = (merged[Pair(r, 0)] ?: merged[Pair(r, 1)] ?: run {
            val row = sheet.getRow(r) ?: return null
            val c0 = row.getCell(0)
            val c1 = row.getCell(1)
            when {
                c0 != null -> cellText(c0)
                c1 != null -> cellText(c1)
                else -> return null
            }
        }).trim()

        periodHeader.find(text)?.let { m ->
            val raw = m.value
            return chineseNumberToInt(raw)
        }
        // 裸数字：仅当相邻行也存在连续大节数字时接受，避免误判
        bareNumber.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val neighbors = (maxOf(0, r - 3)..minOf(sheet.lastRowNum, r + 3))
                .mapNotNull { neighborRow ->
                    val t = cellTextAt(sheet, neighborRow, 0, merged).ifBlank { cellTextAt(sheet, neighborRow, 1, merged) }
                    bareNumber.find(t)?.groupValues?.get(1)?.toInt()
                }.toSet()
            if (n == 1) return n
            return if ((n - 1) in neighbors || (n + 1) in neighbors) n else null
        }
        return null
    }

    private fun chineseNumberToInt(s: String): Int? {
        val num = s.replace(Regex("[^一二三四五六七1-7]"), "")
        if (num.isEmpty()) return null
        return when (num) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "七", "7" -> 7
            else -> null
        }
    }

    // ---- 课程单元格解析 ----

    private data class CourseCell(
        val title: String,
        val teacher: String?,
        val location: String?,
        val weekRange: String?,
        val periodCode: String?
    )

    private val weekRangePattern = Regex("(\\d+)\\s*[-~—至]\\s*(\\d+)\\s*周")
    private val periodCodePattern = Regex("^(\\d+)-(\\d{4})$")
    // 教室：字母/中文名 + 楼/数字（如 A101、求是西楼102、西校区新联楼0103、教1-101、7-101）
    private val locationPattern =
        Regex("[A-Za-z]+\\s*-?\\s*\\d+|[\\u4e00-\\u9fa5]*楼\\s*\\d+|[\\u4e00-\\u9fa5]+\\s*\\d{3,}|教\\s*\\d+|\\d+\\s*[-楼]\\s*\\d*")

    private fun parseCourseCell(raw: String): CourseCell? {
        val lines = raw.replace("\r", "").split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val title = lines.first().trim(' ', '　', '·', '—')
        if (title.isBlank()) return null

        var teacher: String? = null
        var location: String? = null
        var weekRange: String? = null
        var periodCode: String? = null
        val extra = mutableListOf<String>()

        for (line in lines.drop(1)) {
            when {
                // 节次码（如 1-0102）优先于教室判断
                periodCode == null && periodCodePattern.matches(line.trim()) -> periodCode = line.trim()
                location == null && locationPattern.containsMatchIn(line) -> location = line
                weekRange == null && weekRangePattern.containsMatchIn(line) ->
                    weekRange = weekRangePattern.find(line)?.let { "${it.groupValues[1]}-${it.groupValues[2]}周" }
                else -> {
                    if (line.isNotEmpty()) extra.add(line)
                }
            }
        }
        if (teacher == null && extra.isNotEmpty()) teacher = extra.joinToString("；")

        return CourseCell(title, teacher, location, weekRange, periodCode)
    }
}
