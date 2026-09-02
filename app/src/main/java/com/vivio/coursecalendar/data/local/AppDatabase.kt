package com.vivio.coursecalendar.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vivio.coursecalendar.data.local.dao.BatchEventActionDao
import com.vivio.coursecalendar.data.local.dao.ImportBatchDao
import com.vivio.coursecalendar.data.local.dao.ManagedEventDao
import com.vivio.coursecalendar.data.local.dao.ScheduleConfigDao
import com.vivio.coursecalendar.data.local.entity.BatchEventActionEntity
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.local.entity.ManagedEventEntity
import com.vivio.coursecalendar.data.local.entity.ScheduleConfigEntity

@Database(
    entities = [
        ScheduleConfigEntity::class,
        ImportBatchEntity::class,
        ManagedEventEntity::class,
        BatchEventActionEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleConfigDao(): ScheduleConfigDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun managedEventDao(): ManagedEventDao
    abstract fun batchEventActionDao(): BatchEventActionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vivio_calendar.db"
                )
                    // 未发布版本：作息主键与长期映射重构，直接清库重建（交接包《02》第五节策略）
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
