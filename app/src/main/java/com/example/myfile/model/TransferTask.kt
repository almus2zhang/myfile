package com.example.myfile.model

/**
 * 传输任务 UI 模型
 */
data class TransferTask(
    val id: Long,
    val fileName: String,
    val remoteUrl: String,
    val localPath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val status: TransferStatus,
    val chunks: List<ChunkUi>,
    val isUpload: Boolean = false,
    val errorMessage: String? = null
)

enum class TransferStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED }

data class ChunkUi(
    val index: Int,
    val status: ChunkStatusUi,
    val startOffset: Long,
    val endOffset: Long,
    val downloaded: Long
)

enum class ChunkStatusUi { PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED }
