package com.vivio.coursecalendar.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vivio.coursecalendar.data.local.dao.EventMappingDao
import com.vivio.coursecalendar.data.local.dao.ImportBatchDao
import com.vivio.coursecalendar.data.local.dao.ScheduleConfigDao
import com.vivio.coursecalendar.data.local.entity.EventMappingEntity
import com.vivio.coursecalendar.data.local.entity.ImportBatchEntity
import com.vivio.coursecalendar.data.local.entity.ScheduleConfigEntity

@Database(
    entities = [
        ScheduleConfigEntity::class,
        ImportBatchEntity::class,
        EventMappingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleConfigDao(): ScheduleConfigDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun eventMappingDao(): EventMappingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vivio_calendar.db"
                ).build().also { INSTANCE = it }
            }
    }
}
