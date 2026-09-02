package com.vivio.coursecalendar

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T3 前置检查：样表「课节 number」列（18 位课节 ID）的单元格类型与读取精度。
 * 验证 POI 实际读取路径，确认 ID 无精度损失。
 */
class LessonIdPrecisionCheckTest {

    @Test
    fun `样表课节ID逐字符一致且无精度损失`() {
        val file = File("e:\\vivio\\download (6).xls")
        val wb = XSSFWorkbook(file.inputStream())
        val sheet = wb.getSheet("学员排课")

        // 定位「课节 number」列
        val header = sheet.getRow(0)
        var idCol = -1
        for (c in 0 until header.lastCellNum.toInt()) {
            val t = header.getCell(c)?.stringCellValue?.replace(" ", "")?.lowercase() ?: ""
            if (t.contains("课节") && (t.contains("number") || t.contains("id") || t.contains("编号"))) {
                idCol = c
            }
        }
        println("课节ID列: $idCol")
        assertEquals("未找到课节 ID 列", true, idCol >= 0)

        var nonString = 0
        var mismatched = 0
        for (r in 1..sheet.lastRowNum) {
            val cell = sheet.getRow(r)?.getCell(idCol) ?: continue
            val text = cell.toString().trim()
            if (text.isBlank()) continue
            // 期望 18 位纯数字
            if (!text.matches(Regex("\\d{18}"))) {
                println("R$r 非18位数字: type=${cell.cellType} value=$text")
            }
            when (cell.cellType) {
                CellType.STRING -> { /* 字符串直接读取，无精度问题 */ }
                CellType.NUMERIC -> {
                    nonString++
                    // 数值单元格：Double 路径可能丢精度，逐字符对比
                    val numeric = cell.numericCellValue
                    val asLong = numeric.toLong()
                    if (asLong.toString() != text) {
                        mismatched++
                        println("R$r 精度丢失! 原值=$text Double转Long=$asLong")
                    }
                }
                else -> println("R$r 其它类型: ${cell.cellType}")
            }
        }
        println("数值类型单元格数: $nonString, 精度丢失数: $mismatched")
        wb.close()

        // 关键断言：样表中不应出现精度丢失（若出现需修复为字符串读取）
        assertEquals("存在精度丢失的课节 ID，需修复读取路径", 0, mismatched)
    }
}
