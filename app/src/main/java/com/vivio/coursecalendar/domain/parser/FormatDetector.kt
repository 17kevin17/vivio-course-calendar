package com.vivio.coursecalendar.domain.parser

import com.vivio.coursecalendar.domain.model.EventSource
import org.apache.poi.ss.usermodel.Workbook

/** 识别结果 */
data class DetectedFormat(
    val source: EventSource,
    /** 识别可信度；低可信度时 UI 应允许用户手动选择 */
    val confidence: Float
)

/**
 * 格式识别：根据工作表、表头和单元格结构自动判断校内/兼职课表
 * （交接包《02》识别格式阶段；正常样表无需用户选择模板）。
 */
object FormatDetector {

    // 兼职课表标志词
    private val PART_TIME_SHEET_KEYWORDS = listOf("学员排课", "排课", "兼职")
    private val PART_TIME_HEADER_KEYWORDS = listOf(
        "课节id", "课节id", "课节名称", "学员", "主讲", "上课时间", "状态", "课节状态"
    )

    // 校内课表标志词
    private val UNIVERSITY_SHEET_KEYWORDS = listOf("课表", "校历")
    private val UNIVERSITY_HEADER_KEYWORDS = listOf("周次", "星期", "周一", "日期", "大节", "节次")

    fun detect(workbook: Workbook): DetectedFormat? {
        var partTimeScore = 0f
        var universityScore = 0f

        for (i in 0 until minOf(workbook.numberOfSheets, 3)) {
            val sheet = workbook.getSheetAt(i) ?: continue
            val sheetName = sheet.sheetName ?: ""
            val nameNorm = sheetName.trim().lowercase()

            if (PART_TIME_SHEET_KEYWORDS.any { nameNorm.contains(it.lowercase()) }) {
                partTimeScore += 2f
            }
            if (UNIVERSITY_SHEET_KEYWORDS.any { nameNorm.contains(it.lowercase()) }) {
                universityScore += 1.5f
            }

            // 扫描前 12 行找表头/结构标志
            val headerCells = mutableListOf<String>()
            scan@ for (r in 0 until minOf(12, sheet.lastRowNum + 1)) {
                val row = sheet.getRow(r) ?: continue
                for (c in 0 until minOf(row.lastCellNum.toInt(), 24)) {
                    val cell = row.getCell(c) ?: continue
                    val text = cellText(cell).lowercase()
                    if (text.isBlank()) continue
                    if (text.length > 40) continue
                    headerCells.add(text)
                    if (headerCells.size >= 60) break@scan
                }
            }

            val joined = headerCells.joinToString("|")
            val ptHits = PART_TIME_HEADER_KEYWORDS.count { joined.contains(it) }
            val univHits = UNIVERSITY_HEADER_KEYWORDS.count { joined.contains(it) }
            partTimeScore += ptHits * 1.5f
            universityScore += univHits * 1.0f
        }

        val max = maxOf(partTimeScore, universityScore)
        if (max <= 0f) return null
        return if (partTimeScore >= universityScore) {
            DetectedFormat(EventSource.PART_TIME, confidence = partTimeScore / max)
        } else {
            DetectedFormat(EventSource.UNIVERSITY, confidence = universityScore / max)
        }
    }

    private fun cellText(cell: org.apache.poi.ss.usermodel.Cell): String = when {
        cell.cellType == org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
        cell.cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
            if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                cell.localDateTimeCellValue.toString()
            } else {
                cell.numericCellValue.toLong().toString()
            }
        }
        else -> ""
    }
}
