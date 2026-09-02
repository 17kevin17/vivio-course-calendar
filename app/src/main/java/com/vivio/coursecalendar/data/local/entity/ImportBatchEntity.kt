package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 导入批次状态机（交接包《04》第一节） */
object BatchPhase {
    const val PREPARED = "PREPARED"
    const val APPLYING = "APPLYING"
    const val APPLIED = "APPLIED"
    const val PARTIAL = "PARTIAL"
    const val UNDOING = "UNDOING"
    const val UNDONE = "UNDONE"
    const val FAILED = "FAILED"
}

/**
 * 导入批次：每次批量写入系统日历都会生成一个批次，用于更新与撤销。
 * phase 表示状态机当前阶段。
 */
@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileHash: String,
    val fileName: String,
    val source: String,
    val season: String?,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** PREPARED / APPLYING / APPLIED / PARTIAL / UNDOING / UNDONE / FAILED */
    val phase: String,
    val totalCount: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val invalidCount: Int,
    /** 非敏感错误摘要 */
    val errorSummary: String? = null
)
