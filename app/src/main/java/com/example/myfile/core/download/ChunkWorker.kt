package com.example.myfile.core.download

import com.example.myfile.core.DownloadLog
import com.example.myfile.data.webdav.WebDavClient
import kotlinx.coroutines.delay
import java.io.RandomAccessFile

/**
 * 单分片下载 Worker：用 Range 请求拉取 chunk 区段并流式写入 RandomAccessFile。
 * 使用 FileChannel 定位写入，避免频繁 seek + 全局锁。
 */
class ChunkWorker(
    private val client: WebDavClient,
    private val chunk: Chunk,
    private val path: String,
    private val outputFile: RandomAccessFile,
    private val maxRetries: Int,
    private val pulseBytes: Long = 4L * 1024 * 1024,     // 每下载这么多字节就停顿（模拟"播一段"）
    private val pulseDelayMs: Long = 1500L               // 停顿时长（模拟"播放耗时"）
) {
    var downloadedBytes: Long = chunk.downloadedBytes
        private set

    suspend fun download(onProgress: (Long) -> Unit): Boolean {
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                chunk.status = ChunkStatus.DOWNLOADING
                val start = chunk.startOffset + downloadedBytes
                val end = chunk.endOffset
                if (start > end) {
                    chunk.status = ChunkStatus.COMPLETED
                    return true
                }
                val t0 = System.currentTimeMillis()
                val resp = client.downloadRange(path, start, end)
                DownloadLog.log("ChunkWorker", "chunk#${chunk.index} Range $start-$end -> code=${resp.code} (attempt=$attempt)")
                if (resp.code == 200) {
                    DownloadLog.log("ChunkWorker", "chunk#${chunk.index} server returned 200 (no Range support), abort")
                    resp.close()
                    chunk.status = ChunkStatus.FAILED
                    return false
                }
                if (resp.code != 206) {
                    resp.close()
                    throw RuntimeException("Range request failed: ${resp.code}")
                }
                resp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastReport = 0L
                    // 每个 worker 用独立的 channel；position 写用带偏移的 write 保证线程安全
                    val channel = outputFile.channel
                    var sinceLastPulse = 0L
                    while (true) {
                        if (chunk.status == ChunkStatus.PAUSED) {
                            resp.close()
                            return false
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        // write(ByteBuffer, position) 是原子的、带绝对位置，线程安全
                        channel.write(java.nio.ByteBuffer.wrap(buffer, 0, n), start + written)
                        written += n
                        downloadedBytes += n
                        chunk.downloadedBytes = downloadedBytes
                        sinceLastPulse += n
                        // 每 256KB 上报一次「增量」字节数
                        if (downloadedBytes - lastReport >= 256 * 1024) {
                            onProgress(downloadedBytes - lastReport)
                            lastReport = downloadedBytes
                        }
                        // 流式节奏：每下载 pulseBytes 字节，主动停顿，模拟"边播边拉"，
                        // 让流量呈间歇性，绕过运营商对"持续下载"的限速
                        if (pulseDelayMs > 0 && sinceLastPulse >= pulseBytes) {
                            sinceLastPulse = 0L
                            DownloadLog.log("ChunkWorker", "chunk#${chunk.index} pulse pause ${pulseDelayMs}ms")
                            delay(pulseDelayMs)
                        }
                    }
                    if (start + written - 1 < end) {
                        throw RuntimeException("incomplete chunk")
                    }
                    chunk.status = ChunkStatus.COMPLETED
                    val elapsed = System.currentTimeMillis() - t0
                    DownloadLog.log("ChunkWorker", "chunk#${chunk.index} done: $written bytes in ${elapsed}ms (${if (elapsed > 0) written * 1000 / elapsed else 0} B/s)")
                    onProgress(downloadedBytes - lastReport)  // 上报剩余增量
                    return true
                } ?: run {
                    throw RuntimeException("empty response body")
                }
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) {
                    chunk.status = ChunkStatus.FAILED
                    return false
                }
                delay(200L * attempt)
            }
        }
        chunk.status = ChunkStatus.FAILED
        return false
    }

    fun pause() {
        chunk.status = ChunkStatus.PAUSED
    }
}
