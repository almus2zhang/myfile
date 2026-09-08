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

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadTaskEntity?>

    /** 仅在任务处于 DOWNLOADING 时更新进度，绝不覆盖 COMPLETED/PAUSED/CANCELED */
    @Query("UPDATE download_tasks SET downloadedBytes = :downloaded, updatedAt = :ts WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun updateProgress(id: Long, downloaded: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, errorMessage = :err, updatedAt = :ts WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, err: String?, ts: Long = System.currentTimeMillis())

    /** 完成下载任务：原子性同步进度为 totalBytes 且标记为 COMPLETED */
    @Query("UPDATE download_tasks SET downloadedBytes = totalBytes, status = 'COMPLETED', errorMessage = NULL, updatedAt = :ts WHERE id = :id")
    suspend fun markCompleted(id: Long, ts: Long = System.currentTimeMillis())

    /** 自动修复历史因并发竞争卡在 100% 但状态仍为 DOWNLOADING 的任务 */
    @Query("UPDATE download_tasks SET status = 'COMPLETED', updatedAt = :ts WHERE downloadedBytes >= totalBytes AND totalBytes > 0 AND status = 'DOWNLOADING'")
    suspend fun fixStuckCompletedTasks(ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: Long)
}
