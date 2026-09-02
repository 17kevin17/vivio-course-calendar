package com.vivio.coursecalendar.domain.parser

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/** Excel 真实格式：不信任扩展名，按文件头识别。 */
enum class ExcelFormat { HSSF_BINARY, OOXML }

/**
 * 读取 Excel 工作簿。兼职课表扩展名为 .xls 但实际是 OOXML 内容，
 * 因此必须根据文件头 magic bytes 选择解析器。
 */
object ExcelIO {

    /** OOXML (zip) 文件头：PK\x03\x04 */
    fun isOoxml(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** OLE2 复合文档文件头：D0 CF 11 E0 */
    fun isOle2(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    fun detect(bytes: ByteArray): ExcelFormat = when {
        isOoxml(bytes) -> ExcelFormat.OOXML
        isOle2(bytes) -> ExcelFormat.HSSF_BINARY
        else -> throw IllegalArgumentException("无法识别的文件格式，仅支持 .xls / .xlsx")
    }

    /** 打开工作簿；解析失败时返回 null 而非抛出（交接包要求损坏文件不崩溃）。 */
    fun openSafely(stream: InputStream, bytes: ByteArray): Workbook? = try {
        when (detect(bytes)) {
            ExcelFormat.HSSF_BINARY -> HSSFWorkbook(stream)
            ExcelFormat.OOXML -> XSSFWorkbook(stream)
        }
    } catch (_: Exception) {
        null
    }
}
