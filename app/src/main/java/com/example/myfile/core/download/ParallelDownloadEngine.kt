package com.example.myfile.core.download

import com.example.myfile.core.DownloadLog
import com.example.myfile.data.db.ChunkRecordDao
import com.example.myfile.data.db.entity.ChunkRecordEntity
import com.example.myfile.data.webdav.WebDavClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * 核心加速引擎：分片调度 + 并发 Range 下载 + 流式写入 + 断点续传。
 * 支持多 WebDavClient 轮换（不同打洞端口），每个 chunk 严格轮询不同端口，
 * 让多个端口并发分摊下载，绕过运营商的端口级限速。
 */
class ParallelDownloadEngine(
    private val clients: List<WebDavClient>,
    private val config: DownloadConfig,
    private val chunkDao: ChunkRecordDao
) {
    companion object { private const val TAG = "ParallelDownloadEngine" }

    suspend fun startDownload(
        path: String,
        outputFile: RandomAccessFile,
        taskId: Long,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (totalBytes <= 0) {
            return@withContext DownloadResult.Failed("invalid file size: $totalBytes")
        }

        DownloadLog.log(TAG, "startDownload path=$path totalBytes=$totalBytes chunkSize=${config.chunkSize} conns=${config.maxConnections} retries=${config.maxRetries}")

        // 计算分片
        val chunks = buildChunks(taskId, totalBytes, config.chunkSize)
        DownloadLog.log(TAG, "total chunks: ${chunks.size}")

        // 从 DB 恢复已下载进度
        val saved = chunkDao.getChunks(taskId)
        val savedMap = saved.associateBy { it.index }
        chunks.forEach { chunk ->
            savedMap[chunk.index]?.let { rec ->
                chunk.downloadedBytes = rec.downloadedBytes
                val full = chunk.endOffset - chunk.startOffset + 1
                chunk.status = if (rec.downloadedBytes >= full) ChunkStatus.COMPLETED else ChunkStatus.PENDING
            }
        }

        // 持久化分片记录（一次性，返回 id 映射供后续更新）
        chunkDao.deleteByTask(taskId)
        val ids = chunkDao.insertAll(chunks.map {
            ChunkRecordEntity(
                id = 0, taskId = it.taskId, index = it.index,
                startOffset = it.startOffset, endOffset = it.endOffset,
                downloadedBytes = it.downloadedBytes, status = it.status.name
            )
        })
        val idByIndex = chunks.mapIndexed { i, c -> c.index to ids[i] }.toMap()

        // 启动端口探测：并行测试各端口速度，快的端口排在前面，
        // 解决「一上来就被限速」的情况（某些端口天生处于限速冷却期）
        val orderedClients = if (clients.size > 1) probePortSpeeds(path) else clients

        val totalDownloaded = AtomicLong(chunks.sumOf { it.downloadedBytes })
        val lastBytes = AtomicLong(totalDownloaded.get())
        // 端口偏移：检测到限速时 +1，让后续 chunk 切换到下一个端口（绕过限速冷却）
        val portOffset = java.util.concurrent.atomic.AtomicInteger(0)

        // 自适应限速规避：检测到速度骤降就切换端口
        val throttleController = if (orderedClients.size > 1) {
            AdaptiveThrottleController(this) {
                val newOffset = portOffset.incrementAndGet()
                DownloadLog.log(TAG, "throttle detected -> port offset = $newOffset")
            }.also { it.start() }
        } else null

        // 速率统计：每 500ms 打印一次，同时负责进度上报（节流）
        val speedTask = launch(Dispatchers.IO) {
            while (true) {
                delay(500)
                val now = totalDownloaded.get()
                val prev = lastBytes.getAndSet(now)
                DownloadLog.log(TAG, "speed: ${(now - prev) * 2} B/s, total=$now/$totalBytes (portOffset=${portOffset.get()})")
                onProgress(now, totalBytes)
            }
        }

        // 只处理未完成的分片
        val pending = chunks.filter { it.status != ChunkStatus.COMPLETED }

        val semaphore = Semaphore(config.maxConnections)
        val results = pending.map { chunk ->
            async {
                semaphore.withPermit {
                    // 端口轮换：chunk.index + portOffset 决定端口，检测到限速后偏移递增
                    val clientIdx = (chunk.index + portOffset.get()) % orderedClients.size
                    val c = orderedClients[clientIdx]
                    DownloadLog.log(TAG, "chunk#${chunk.index} -> client#$clientIdx (port ${portOf(c)})")
                    val worker = ChunkWorker(
                        client = c,
                        chunk = chunk,
                        path = path,
                        outputFile = outputFile,
                        maxRetries = config.maxRetries,
                        pulseBytes = if (config.streamPulseMode) config.pulseBytes else 0L,
                        pulseDelayMs = if (config.streamPulseMode) config.pulseDelayMs else 0L
                    )
                    val ok = worker.download { delta ->
                        totalDownloaded.addAndGet(delta)
                        throttleController?.reportBytes(delta)
                    }
                    // 持久化该分片进度（用预取的 id，避免重复查询）
                    val recId = idByIndex[chunk.index] ?: 0L
                    if (recId > 0) {
                        chunkDao.updateProgress(
                            id = recId,
                            downloaded = worker.downloadedBytes,
                            status = if (ok) ChunkStatus.COMPLETED.name else chunk.status.name
                        )
                    }
                    ok
                }
            }
        }

        val okResults = results.awaitAll()
        throttleController?.stop()
        speedTask.cancel()
        onProgress(totalDownloaded.get(), totalBytes)

        if (okResults.all { it }) DownloadResult.Success
        else DownloadResult.Failed("${okResults.count { !it }} chunks failed")
    }

    private fun buildChunks(taskId: Long, totalBytes: Long, chunkSize: Long): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var offset = 0L
        var index = 0
        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize - 1, totalBytes - 1)
            chunks.add(Chunk(taskId, index, offset, end))
            offset = end + 1
            index++
        }
        return chunks
    }

    private fun portOf(c: WebDavClient): String = try {
        java.net.URI(c.baseUrlString()).port.toString()
    } catch (e: Exception) { "?" }

    /**
     * 启动端口探测：并行向每个端口发一个小 Range 请求（拉 1MB），
     * 测实际下载速度，返回按速度降序排列的 clients（快的在前）。
     * 用于解决「一上来就被限速」的情况。
     */
    private suspend fun probePortSpeeds(path: String): List<WebDavClient> = coroutineScope {
        DownloadLog.log(TAG, "probing ${clients.size} ports...")
        // 探测大小：128KB 足够让 TCP 慢启动爬起来体现真实吞吐，又不太浪费流量
        val probeSize = 128L * 1024
        // 探测耗时上限：超过则按已下载字节数算速度，避免某个端口卡死拖慢整体
        val probeTimeoutMs = 2000L
        // 速度阈值：达到该速度即认为「快」，提前结束探测
        val fastThreshold = 500L * 1024   // 500KB/s

        val firstFast = CompletableDeferred<Pair<Int, Long>>()  // (idx, speed)
        val speeds = clients.mapIndexed { idx, c ->
            async {
                val speed = try {
                    val t0 = System.currentTimeMillis()
                    val resp = c.downloadRange(path, 0, probeSize - 1)
                    var bytes = 0L
                    resp.body?.byteStream()?.use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            if (System.currentTimeMillis() - t0 > probeTimeoutMs) break
                            val n = input.read(buf)
                            if (n < 0) break
                            bytes += n
                            if (bytes >= probeSize) break
                        }
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    resp.close()
                    if (elapsed > 0 && bytes > 0) bytes * 1000 / elapsed else 0L
                } catch (e: Exception) {
                    0L
                }
                DownloadLog.log(TAG, "probe port ${portOf(c)}: ${speed}B/s")
                // 一旦有端口达标，通知主协程提前结束
                if (speed >= fastThreshold) {
                    firstFast.complete(Pair(idx, speed))
                }
                Pair(idx, speed)
            }
        }

        // 轮询等待：第一个达标端口出现就提前结束，或全部完成
        while (!firstFast.isCompleted && speeds.any { it.isActive }) {
            delay(50)
        }
        if (firstFast.isCompleted) {
            val (idx, spd) = firstFast.await()
            DownloadLog.log(TAG, "probe early-finish: port ${portOf(clients[idx])} is fast (${spd}B/s), cancel others")
            // 取消其余探测协程
            speeds.forEach { it.cancel() }
            // 快端口放最前，其余按原顺序
            return@coroutineScope listOf(clients[idx]) + clients.filterIndexed { i, _ -> i != idx }
        }

        // 没有达标的端口（全慢），按实测速度降序排列
        val sorted = speeds.awaitAll().sortedByDescending { it.second }
        DownloadLog.log(TAG, "probe result: ${sorted.joinToString { "port${portOf(clients[it.first])}=${it.second}" }}")
        sorted.map { clients[it.first] }
    }
}
