package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 导入批次：每次批量写入系统日历都会生成一个批次，用于更新与撤销。 */
@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileHash: String,
    val fileName: String,
    val source: String,
    val season: String?,
    val createdAt: Long,
    val totalCount: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val invalidCount: Int,
    /** COMPLETED / PARTIAL / FAILED / UNDONE */
    val status: String
)
