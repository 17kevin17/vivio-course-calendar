package com.vivio.coursecalendar.data.repository

import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.local.entity.ScheduleConfigEntity
import com.vivio.coursecalendar.domain.parser.ScheduleTable
import com.vivio.coursecalendar.domain.schedule.DefaultSchedule
import com.vivio.coursecalendar.domain.schedule.SchedulePeriod
import com.vivio.coursecalendar.domain.schedule.Season
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 作息配置仓库：读写春/夏五大节，内置默认值。 */
class ScheduleRepository(private val db: AppDatabase) {

    private val dao = db.scheduleConfigDao()

    /** 初始化内置默认配置（幂等，仅在首次安装时写入）。 */
    suspend fun seedDefaultsIfEmpty() {
        if (dao.getBySeason(Season.SUMMER.name).isNotEmpty()) return
        val entities = mutableListOf<ScheduleConfigEntity>()
        DefaultSchedule.summer.forEach {
            entities += toEntity(Season.SUMMER, it, version = 1)
        }
        DefaultSchedule.spring.forEach {
            if (it != null) entities += toEntity(Season.SPRING, it, version = 1)
        }
        dao.upsertAll(entities)
    }

    fun observe(season: Season): Flow<ScheduleTable> =
        dao.observeBySeason(season.name).map { it.toTable(season) }

    suspend fun get(season: Season): ScheduleTable = dao.getBySeason(season.name).toTable(season)

    /** 当前配置版本号（用于提示是否更新已导入课程）。 */
    suspend fun getVersion(season: Season): Int =
        dao.getBySeason(season.name).maxOfOrNull { it.configVersion } ?: 1

    /** 保存一套完整大节（覆盖该季全部已配置项）。 */
    suspend fun save(season: Season, periods: List<SchedulePeriod>, version: Int) {
        dao.upsertAll(periods.map { toEntity(season, it, version) })
    }

    /** 大节时间缺失（如春季下午）时用于提示。 */
    fun missingPeriods(season: Season, table: ScheduleTable): List<Int> =
        (1..5).filter { table.period(it) == null }

    private fun toEntity(season: Season, p: SchedulePeriod, version: Int) = ScheduleConfigEntity(
        season = season.name,
        periodNumber = p.number,
        startMinute = p.start.hour * 60 + p.start.minute,
        endMinute = p.end.hour * 60 + p.end.minute,
        configVersion = version
    )

    private fun List<ScheduleConfigEntity>.toTable(season: Season): ScheduleTable =
        ScheduleTable(
            season = season,
            periods = associate { it.periodNumber to SchedulePeriod(it.periodNumber, minuteToTime(it.startMinute), minuteToTime(it.endMinute)) }
        )

    private fun minuteToTime(minute: Int): LocalTime =
        LocalTime.of(minute / 60, minute % 60)
}
