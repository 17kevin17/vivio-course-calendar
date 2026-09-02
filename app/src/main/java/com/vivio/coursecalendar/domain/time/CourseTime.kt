package com.vivio.coursecalendar.domain.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课程时间统一组件（交接包《04》第八节）。
 * 校内和当前兼职课默认按 Asia/Shanghai 解释；时间转换集中在此，
 * 禁止在解析器、数据库和 CalendarWriter 各自转换。
 */
object CourseTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun toMillis(t: LocalDateTime): Long = t.atZone(ZONE).toInstant().toEpochMilli()

    fun fromMillis(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZONE)
}
