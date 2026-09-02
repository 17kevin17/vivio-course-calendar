package com.vivio.coursecalendar

import android.content.Context
import androidx.room.Room
import com.vivio.coursecalendar.data.local.AppDatabase
import java.util.concurrent.Executors

/**
 * Robolectric 测试辅助：Room 内存库。
 * Legacy SQLite 有线程亲和性，因此把所有 DB 访问集中到同一单线程 executor。
 */
object TestDb {
    fun inMemory(context: Context): AppDatabase {
        val single = Executors.newSingleThreadExecutor()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(single)
            .setTransactionExecutor(single)
            .build()
    }
}
