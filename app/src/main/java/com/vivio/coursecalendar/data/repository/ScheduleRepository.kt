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

/** 作息配置校验失败 */
class ScheduleValidationException(message: String) : IllegalArgumentException(message)

/**
 * 作息配置仓库：读写春/夏五大节，内置默认值。
 * 同一季节可保存五个大节（复合主键 season + periodNumber）。
 */
class ScheduleRepository(private val db: AppDatabase) {

    private val dao = db.scheduleConfigDao()

    /** 初始化内置默认配置（幂等，仅在首次安装时写入）。 */
    suspend fun seedDefaultsIfEmpty() {
        if (dao.getBySeason(Season.SUMMER.name).isNotEmpty()) return
        val now = System.currentTimeMillis()
        val entities = mutableListOf<ScheduleConfigEntity>()
        DefaultSchedule.summer.forEach {
            entities += toEntity(Season.SUMMER, it, version = 1, now)
        }
        DefaultSchedule.spring.forEach {
            if (it != null) entities += toEntity(Season.SPRING, it, version = 1, now)
        }
        dao.upsertAll(entities)
    }

    fun observe(season: Season): Flow<ScheduleTable> =
        dao.observeBySeason(season.name).map { it.toTable(season) }

    suspend fun get(season: Season): ScheduleTable = dao.getBySeason(season.name).toTable(season)

    /** 当前配置版本号（用于提示是否更新已导入课程）。 */
    suspend fun getVersion(season: Season): Int =
        dao.getBySeason(season.name).maxOfOrNull { it.configVersion } ?: 1

    /**
     * 校验：
     * - periodNumber 只能为 1–5；
     * - 已配置时 startMinute < endMinute；
     * - 同一季节相邻大节不得重叠。
     */
    suspend fun save(season: Season, periods: List<SchedulePeriod>, version: Int) {
        validatePeriods(season, periods)
        val now = System.currentTimeMillis()
        dao.upsertAll(periods.map { toEntity(season, it, version, now) })
    }

    /** 大节时间缺失（如春季下午）时用于提示。 */
    fun missingPeriods(season: Season, table: ScheduleTable): List<Int> =
        (1..5).filter { table.period(it) == null }

    companion object {
        /** 校验：period 1-5、start<end、相邻不重叠；不合法抛出 [ScheduleValidationException]。 */
        fun validatePeriods(season: Season, periods: List<SchedulePeriod>) {
            val byNo = periods.associateBy { it.number }
            for (no in 1..5) {
                val p = byNo[no] ?: throw ScheduleValidationException("缺少第$no 大节")
                if (p.start.isAfter(p.end) || p.start == p.end) {
                    throw ScheduleValidationException("第$no 大节开始时间必须早于结束时间")
                }
            }
            val sorted = (1..5).mapNotNull { byNo[it] }.sortedBy { it.start }
            for (i in 0 until sorted.size - 1) {
                val cur = sorted[i]
                val next = sorted[i + 1]
                if (cur.end.isAfter(next.start)) {
                    throw ScheduleValidationException("第${cur.number}大节与第${next.number}大节时间重叠")
                }
            }
        }

        /** 供测试调用的静态入口。 */
        fun validatePeriodsStatic(season: Season, periods: List<SchedulePeriod>) =
            validatePeriods(season, periods)
    }

    private fun toEntity(season: Season, p: SchedulePeriod, version: Int, now: Long) = ScheduleConfigEntity(
        season = season.name,
        periodNumber = p.number,
        startMinute = p.start.hour * 60 + p.start.minute,
        endMinute = p.end.hour * 60 + p.end.minute,
        configVersion = version,
        updatedAt = now
    )

    private fun List<ScheduleConfigEntity>.toTable(season: Season): ScheduleTable =
        ScheduleTable(
            season = season,
            periods = associate {
                it.periodNumber to SchedulePeriod(
                    it.periodNumber,
                    minuteToTime(it.startMinute),
                    minuteToTime(it.endMinute)
                )
            }
        )

    private fun minuteToTime(minute: Int?): LocalTime {
        requireNotNull(minute) { "作息时间未配置" }
        return LocalTime.of(minute / 60, minute % 60)
    }
}
