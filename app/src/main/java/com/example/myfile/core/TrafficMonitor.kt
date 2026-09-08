package com.example.myfile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TrafficRecord(
    val id: Long,
    val method: String,
    val url: String,
    val displayUrl: String,
    val category: String,
    val rangeHeader: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = 0L,
    var totalBytesRead: Long = 0L,
    var totalBytesExpected: Long = -1L,
    var speedBytesPerSec: Long = 0L,
    var status: TransferState = TransferState.ACTIVE,
    var responseCode: Int = 0,
    var errorMessage: String? = null,
    @Transient var cancelAction: (() -> Unit)? = null
) {
    val durationMs: Long
        get() = if (endTime > 0) (endTime - startTime).coerceAtLeast(0) else (System.currentTimeMillis() - startTime).coerceAtLeast(0)
}

enum class TransferState {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELED
}

/**
 * 全局网络传输实时监视器：
 * 挂载到 OkHttp 拦截器链，透明监控所有 HTTP 请求的数据传输速率、已传数据、耗时、状态码与来源分类。
 * 支持在 Debug 监控面板中查看详情并手动取消指定的活跃传输任务。
 */
object TrafficMonitor {

    private val idCounter = AtomicLong(1)
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _activeTransfers = MutableStateFlow<List<TrafficRecord>>(emptyList())
    val activeTransfers: StateFlow<List<TrafficRecord>> = _activeTransfers.asStateFlow()

    private val _recentTransfers = MutableStateFlow<List<TrafficRecord>>(emptyList())
    val recentTransfers: StateFlow<List<TrafficRecord>> = _recentTransfers.asStateFlow()

    private val _totalDownloadSpeed = MutableStateFlow(0L)
    val totalDownloadSpeed: StateFlow<Long> = _totalDownloadSpeed.asStateFlow()

    private val recordsMap = ConcurrentHashMap<Long, TrafficRecord>()
    private val speedTrackingMap = ConcurrentHashMap<Long, SpeedSample>()

    private data class SpeedSample(
        var lastBytes: Long,
        var lastTime: Long
    )

    private var tickerJob: Job? = null

    private fun ensureTicker() {
        synchronized(this) {
            if (tickerJob?.isActive == true) return
            tickerJob = scope.launch {
                while (isActive) {
                    delay(400)
                    calculateSpeeds()
                    if (recordsMap.isEmpty()) {
                        _totalDownloadSpeed.value = 0L
                        _activeTransfers.value = emptyList()
                        break
                    }
                }
            }
        }
    }

    private fun calculateSpeeds() {
        val now = System.currentTimeMillis()
        var totalSpeed = 0L
        val activeList = mutableListOf<TrafficRecord>()

        for ((id, record) in recordsMap) {
            val sample = speedTrackingMap[id]
            if (sample != null) {
                val dt = (now - sample.lastTime) / 1000.0
                if (dt >= 0.35) {
                    val delta = (record.totalBytesRead - sample.lastBytes).coerceAtLeast(0)
                    record.speedBytesPerSec = (delta / dt).toLong()
                    sample.lastBytes = record.totalBytesRead
                    sample.lastTime = now
                }
            }
            totalSpeed += record.speedBytesPerSec
            activeList.add(record)
        }

        _totalDownloadSpeed.value = totalSpeed
        _activeTransfers.value = activeList.sortedByDescending { it.id }
    }

    fun clearHistory() {
        _recentTransfers.value = emptyList()
    }

    fun cancelTransfer(id: Long) {
        val record = recordsMap[id] ?: return
        record.cancelAction?.invoke()
    }

    fun cancelAllActive() {
        for (record in recordsMap.values) {
            record.cancelAction?.invoke()
        }
    }

    private fun finishRecord(id: Long, finalStatus: TransferState, code: Int = 0, error: String? = null) {
        val record = recordsMap.remove(id) ?: return
        speedTrackingMap.remove(id)
        record.status = finalStatus
        if (code > 0) record.responseCode = code
        if (error != null) record.errorMessage = error
        if (record.endTime == 0L) record.endTime = System.currentTimeMillis()
        record.speedBytesPerSec = 0L

        val curRecent = _recentTransfers.value.toMutableList()
        curRecent.add(0, record)
        if (curRecent.size > 50) {
            _recentTransfers.value = curRecent.take(50)
        } else {
            _recentTransfers.value = curRecent
        }
        _activeTransfers.value = recordsMap.values.sortedByDescending { it.id }
    }

    private fun guessCategory(request: Request): String {
        val path = request.url.encodedPath.lowercase()
        val method = request.method.uppercase()
        val range = request.header("Range")
        val userAgent = request.header("User-Agent") ?: ""

        return when {
            method == "PROPFIND" -> "WebDAV 目录检索"
            method in listOf("MKCOL", "DELETE", "MOVE", "COPY") -> "WebDAV 操作 ($method)"
            method == "PUT" -> "WebDAV 上传 (PUT)"
            request.header("X-Download-Task") != null -> "多连接下载引擎"
            path.endsWith(".apk") && range != null -> "APK 图标解析"
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") -> "图片缩略图"
            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".flv") || path.endsWith(".wmv") -> {
                if (range != null && (range.contains("0-1048576") || range.contains("0-131071") || range.contains("0-65536"))) {
                    "视频缩略图提取"
                } else if (userAgent.contains("Coil") || userAgent.contains("MediaMetadataRetriever")) {
                    "视频缩略图提取"
                } else {
                    "视频/流媒体传输"
                }
            }
            path.contains("open_") -> "下载缓存查看"
            else -> "网络请求 ($method)"
        }
    }

    private fun shortenUrl(path: String, host: String): String {
        val decoded = try { java.net.URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }
        val name = decoded.substringAfterLast('/').ifBlank { decoded }
        return if (decoded.length > 40) ".../$name" else decoded
    }

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val recordId = idCounter.getAndIncrement()
        val urlStr = request.url.toString()
        val range = request.header("Range")
        val category = guessCategory(request)
        val displayUrl = shortenUrl(request.url.encodedPath, request.url.host)

        val record = TrafficRecord(
            id = recordId,
            method = request.method,
            url = urlStr,
            displayUrl = displayUrl,
            category = category,
            rangeHeader = range,
            cancelAction = {
                try { chain.call().cancel() } catch (_: Exception) {}
            }
        )

        recordsMap[recordId] = record
        speedTrackingMap[recordId] = SpeedSample(0L, System.currentTimeMillis())
        _activeTransfers.value = recordsMap.values.sortedByDescending { it.id }
        ensureTicker()

        val response: Response
        try {
            response = chain.proceed(request)
            record.responseCode = response.code
            val cl = response.body?.contentLength() ?: -1L
            record.totalBytesExpected = cl
        } catch (e: Exception) {
            val status = if (chain.call().isCanceled()) TransferState.CANCELED else TransferState.FAILED
            finishRecord(recordId, status, error = e.message)
            throw e
        }

        val originalBody = response.body
        if (originalBody == null) {
            finishRecord(recordId, TransferState.COMPLETED, response.code)
            return@Interceptor response
        }

        val monitoredBody = object : ResponseBody() {
            override fun contentType(): MediaType? = originalBody.contentType()
            override fun contentLength(): Long = originalBody.contentLength()
            override fun source(): BufferedSource {
                val forwardingSource = object : ForwardingSource(originalBody.source()) {
                    @Throws(IOException::class)
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        try {
                            val bytesRead = super.read(sink, byteCount)
                            if (bytesRead > 0) {
                                record.totalBytesRead += bytesRead
                            } else if (bytesRead == -1L) {
                                finishRecord(recordId, TransferState.COMPLETED, response.code)
                            }
                            return bytesRead
                        } catch (e: Exception) {
                            val status = if (chain.call().isCanceled()) TransferState.CANCELED else TransferState.FAILED
                            finishRecord(recordId, status, response.code, e.message)
                            throw e
                        }
                    }

                    @Throws(IOException::class)
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            if (record.status == TransferState.ACTIVE) {
                                finishRecord(recordId, TransferState.COMPLETED, response.code)
                            }
                        }
                    }
                }
                return forwardingSource.buffer()
            }
        }

        response.newBuilder().body(monitoredBody).build()
    }
}
