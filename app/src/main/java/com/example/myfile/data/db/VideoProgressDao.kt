package com.example.myfile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myfile.data.db.entity.VideoProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: VideoProgressEntity)

    @Query("SELECT * FROM video_progress WHERE uriKey = :uriKey")
    suspend fun get(uriKey: String): VideoProgressEntity?

    @Query("SELECT * FROM video_progress")
    fun observeAll(): Flow<List<VideoProgressEntity>>

    @Query("DELETE FROM video_progress WHERE uriKey = :uriKey")
    suspend fun delete(uriKey: String)
}
