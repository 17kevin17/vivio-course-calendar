package com.vivio.coursecalendar

import android.app.Application
import com.vivio.coursecalendar.data.local.AppDatabase
import com.vivio.coursecalendar.data.repository.ScheduleRepository
import com.vivio.coursecalendar.domain.calendar.CalendarWriter
import com.vivio.coursecalendar.domain.import.ImportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 应用级依赖容器（轻量手动 DI）。 */
class AppContainer(context: android.content.Context) {
    val database: AppDatabase = AppDatabase.get(context)
    val scheduleRepository = ScheduleRepository(database)
    val calendarWriter = CalendarWriter(context)
    val importManager = ImportManager(database, scheduleRepository, calendarWriter)
}

class VivioApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首次安装写入内置作息配置（春/夏）
        appScope.launch { container.scheduleRepository.seedDefaultsIfEmpty() }
    }
}
