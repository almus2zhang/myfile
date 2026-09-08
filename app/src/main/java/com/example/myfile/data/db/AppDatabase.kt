package com.example.myfile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myfile.data.db.entity.ChunkRecordEntity
import com.example.myfile.data.db.entity.DownloadTaskEntity
import com.example.myfile.data.db.entity.VideoProgressEntity

@Database(
    entities = [DownloadTaskEntity::class, ChunkRecordEntity::class, VideoProgressEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun chunkRecordDao(): ChunkRecordDao
    abstract fun videoProgressDao(): VideoProgressDao

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
