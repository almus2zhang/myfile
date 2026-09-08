package com.example.myfile.core.download

/**
 * 下载配置，从 SettingsStore 读取
 */
data class DownloadConfig(
    val chunkSize: Long = 4L * 1024 * 1024,
    val maxConnections: Int = 4,
    val maxRetries: Int = 3,
    val connectTimeoutSec: Long = 30,
    val readTimeoutSec: Long = 60,
    val singleConnectionMode: Boolean = false,  // true = 单连接完整 GET（不用 Range，模拟 CX）
    val streamPulseMode: Boolean = false,       // true = 流式节奏（边下边停，模拟边播边拉）
    val pulseBytes: Long = 4L * 1024 * 1024,    // 每下载这么多字节停顿
    val pulseDelayMs: Long = 1500L              // 停顿时长
) {
    init {
        require(chunkSize in (512L * 1024)..(256L * 1024 * 1024)) { "chunkSize out of range" }
        require(maxConnections in 1..16) { "maxConnections out of range" }
        require(maxRetries in 0..10) { "maxRetries out of range" }
    }
}

data class Chunk(
    val taskId: Long,
    val index: Int,
    val startOffset: Long,
    val endOffset: Long,
    var downloadedBytes: Long = 0,
    var status: ChunkStatus = ChunkStatus.PENDING
)

enum class ChunkStatus { PENDING, DOWNLOADING, COMPLETED, FAILED, PAUSED }

sealed class DownloadResult {
    object Success : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
    object Canceled : DownloadResult()
    object UnsupportedRange : DownloadResult()
}
