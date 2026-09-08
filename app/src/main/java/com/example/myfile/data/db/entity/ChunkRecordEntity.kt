package com.example.myfile.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunk_records",
    indices = [Index("taskId")]
)
data class ChunkRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val index: Int,
    val startOffset: Long,
    val endOffset: Long,
    val downloadedBytes: Long = 0,
    val status: String         // ChunkStatus name
)
