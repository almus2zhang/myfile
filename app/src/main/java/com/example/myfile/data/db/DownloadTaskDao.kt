package com.example.myfile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myfile.data.db.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET downloadedBytes = :downloaded, status = :status, updatedAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, status: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, errorMessage = :err, updatedAt = :ts WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, err: String?, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: Long)
}
