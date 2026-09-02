package com.vivio.coursecalendar.domain.schedule

import java.time.LocalTime

/** 季节，仅区分春季与夏季 */
enum class Season(val label: String) {
    SPRING("春季"),
    SUMMER("夏季")
}

/** 一个大节：45 分钟上课 + 10 分钟休息 + 45 分钟上课 = 100 分钟日历事件 */
data class SchedulePeriod(
    val number: Int,
    val start: LocalTime,
    val end: LocalTime
) {
    /** 持续时间应为 100 分钟（一大节） */
    val durationMinutes: Long
        get() = java.time.Duration.between(start, end).toMinutes()
}

/**
 * 作息配置：内置春季、夏季两套，用户可以修改。
 *
 * 已确认（交接包 README / 04 文档）：
 * - 上午第一大节固定 08:00 开始；
 * - 大节之间通常间隔 30 分钟；
 * - 夏季下午第一大节 15:00 开始；
 * - 晚间第五大节 20:00 开始。
 * - 春季下午第一大节开始时间待用户确认，默认不填（配置为 null），
 *   导入春季下午课程前必须引导用户补齐。
 */
object DefaultSchedule {

    /** 夏季默认五大节（已确认） */
    val summer: List<SchedulePeriod> = listOf(
        SchedulePeriod(1, LocalTime.of(8, 0), LocalTime.of(9, 40)),
        SchedulePeriod(2, LocalTime.of(10, 10), LocalTime.of(11, 50)),
        SchedulePeriod(3, LocalTime.of(15, 0), LocalTime.of(16, 40)),
        SchedulePeriod(4, LocalTime.of(17, 10), LocalTime.of(18, 50)),
        SchedulePeriod(5, LocalTime.of(20, 0), LocalTime.of(21, 40))
    )

    /**
     * 春季默认五大节。下午时段待用户确认，故返回 null。
     * 上午第一大节 08:00、第二大节沿用上午固定时间、晚间第五大节 20:00。
     */
    val spring: List<SchedulePeriod?> = listOf(
        SchedulePeriod(1, LocalTime.of(8, 0), LocalTime.of(9, 40)),
        SchedulePeriod(2, LocalTime.of(10, 10), LocalTime.of(11, 50)),
        null,
        null,
        SchedulePeriod(5, LocalTime.of(20, 0), LocalTime.of(21, 40))
    )
}
