package com.example.myfile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myfile.data.db.entity.ChunkRecordEntity
import com.example.myfile.data.db.entity.DownloadTaskEntity

@Database(
    entities = [DownloadTaskEntity::class, ChunkRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun chunkRecordDao(): ChunkRecordDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "myfile.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
