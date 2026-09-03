package com.vivio.coursecalendar

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
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
        // v2 F4：启动时执行一次故障恢复（IO 协程，不阻塞主线程）
        // U5：恢复前检查日历权限；无权限时跳过 Provider 恢复，避免 SecurityException 崩溃
        if (hasCalendarPermission()) {
            appScope.launch {
                val recovered = container.importManager.recover()
                if (recovered > 0) {
                    android.util.Log.i("VivioApp", "启动恢复：处理 $recovered 个未完成批次")
                }
            }
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}
