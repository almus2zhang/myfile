package com.example.myfile.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val remoteUrl: String,
    val localPath: String,
    val totalBytes: Long,
    val downloadedBytes: Long = 0,
    val status: String,        // TransferStatus name
    val isUpload: Boolean = false,
    val accountId: Long = 0,
    val authHeader: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
