package com.example.myfile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.myfile.data.db.entity.ChunkRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkRecordEntity>): List<Long>

    @Update
    suspend fun update(chunk: ChunkRecordEntity)

    @Query("SELECT * FROM chunk_records WHERE taskId = :taskId ORDER BY `index`")
    fun observeChunks(taskId: Long): Flow<List<ChunkRecordEntity>>

    @Query("SELECT * FROM chunk_records WHERE taskId = :taskId ORDER BY `index`")
    suspend fun getChunks(taskId: Long): List<ChunkRecordEntity>

    @Query("DELETE FROM chunk_records WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    @Query("UPDATE chunk_records SET downloadedBytes = :downloaded, status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, status: String)
}
