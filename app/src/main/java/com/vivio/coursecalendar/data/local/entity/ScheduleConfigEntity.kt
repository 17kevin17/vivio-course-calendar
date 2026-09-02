package com.vivio.coursecalendar.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vivio.coursecalendar.domain.schedule.Season

/**
 * 作息配置（春/夏 各五大节）。用户可以修改每个大节的起止时间。
 * 配置带版本号：修改后提示是否更新已经导入的课程。
 */
@Entity(tableName = "schedule_config")
data class ScheduleConfigEntity(
    @PrimaryKey val season: String,
    val periodNumber: Int,
    /** 自午夜起的分钟数 */
    val startMinute: Int,
    /** 自午夜起的分钟数 */
    val endMinute: Int,
    val configVersion: Int
) {
    val seasonEnum: Season get() = Season.valueOf(season)
}
