package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import com.vivio.coursecalendar.domain.schedule.Season

/**
 * 作息配置（春/夏 各五大节）。同一季节需要保存五个大节，因此使用复合主键。
 * 用户可以修改每个大节的起止时间；配置带版本号。
 *
 * 约束（在 Repository 层校验）：
 * - periodNumber 只能为 1–5；
 * - 已配置时 startMinute < endMinute；
 * - 同一季节相邻大节不得重叠；
 * - 春季未确认的下午时段允许为 null（对应课程必须产生 blocker）。
 */
@Entity(
    tableName = "schedule_config",
    primaryKeys = ["season", "periodNumber"]
)
data class ScheduleConfigEntity(
    val season: String,
    val periodNumber: Int,
    /** 自午夜起的分钟数；未配置时为 null */
    val startMinute: Int?,
    /** 自午夜起的分钟数；未配置时为 null */
    val endMinute: Int?,
    val configVersion: Int,
    val updatedAt: Long
) {
    val seasonEnum: Season get() = Season.valueOf(season)
}
