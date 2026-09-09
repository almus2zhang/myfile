package com.example.myfile.core.download

import android.content.Context
import android.util.Log
import com.example.myfile.core.DownloadLog
import com.example.myfile.data.db.AppDatabase
import com.example.myfile.data.db.ChunkRecordDao
import com.example.myfile.data.db.DownloadTaskDao
import com.example.myfile.data.db.entity.DownloadTaskEntity
import com.example.myfile.data.prefs.DownloadSettings
import com.example.myfile.data.webdav.WebDavClient
import com.example.myfile.model.ChunkStatusUi
import com.example.myfile.model.ChunkUi
import com.example.myfile.model.TransferStatus
import com.example.myfile.model.TransferTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 下载任务生命周期管理：创建任务、启动/暂停/取消、统一进度流
 */
class DownloadManager(
    private val context: Context,
    private val db: AppDatabase,
    private val clientProvider: (com.example.myfile.model.WebDavAccount) -> WebDavClient,
    private val settingsProvider: () -> DownloadSettings
) {
    companion object { private const val TAG = "DownloadManager" }

    private val taskDao: DownloadTaskDao = db.downloadTaskDao()
    private val chunkDao: ChunkRecordDao = db.chunkRecordDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningTasks = mutableMapOf<Long, kotlinx.coroutines.Job>()
    private val mutex = Mutex()
    private val renamePrefs = context.getSharedPreferences("download_pending_renames", Context.MODE_PRIVATE)

    init {
        recoverPendingRenames()
        scope.launch {
            try {
                taskDao.fixStuckCompletedTasks()
            } catch (e: Exception) {
                Log.e(TAG, "fixStuckCompletedTasks error", e)
            }
        }
    }

    /** UI 层订阅的任务流 */
    val tasks: StateFlow<List<TransferTask>> =
        combine(taskDao.observeAll(), settingsProvider().let { MutableStateFlow(it) }) { entities, _ ->
            entities.map { it.toUi() }
        }.let { combined ->
            kotlinx.coroutines.flow.MutableStateFlow<List<TransferTask>>(emptyList()).also { state ->
                scope.launch {
                    combined.collect { state.value = it }
                }
            }
        }

    /** 单任务分片流 */
    fun observeChunks(taskId: Long) = chunkDao.observeChunks(taskId).map { list ->
        list.map {
            ChunkUi(
                index = it.index,
                status = runCatching { ChunkStatusUi.valueOf(it.status) }.getOrDefault(ChunkStatusUi.PENDING),
                startOffset = it.startOffset,
                endOffset = it.endOffset,
                downloaded = it.downloadedBytes
            )
        }
    }

    /** 视频扩展名集合：这些类型运营商不按 Content-Type 限速，无需改名 */
    private val videoExtensions = setOf(
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "ts", "m4v",
        "mpg", "mpeg", "3gp", "rmvb", "rm", "vob", "m2ts"
    )

    /** 创建并启动下载任务 */
    suspend fun startDownload(
        account: com.example.myfile.model.WebDavAccount,
        remotePath: String,
        fileName: String,
        localDir: File,
        knownSize: Long = -1L,
        forceRename: Boolean = false
    ): Long = withContext(Dispatchers.IO) {
        val client = clientProvider(account)
        // 优先用已知大小（列目录时 PROPFIND Depth:1 已拿到 getcontentlength），否则回退 HEAD/PROPFIND
        var totalBytes = knownSize
        if (totalBytes <= 0) {
            totalBytes = client.getSize(remotePath)
        }
        require(totalBytes > 0) { "cannot get file size (knownSize=$knownSize)" }

        val localFile = File(localDir, fileName)

        // 改名下载（伪装视频）：把非 .avi 文件临时改成 .avi 后缀（服务器端 MOVE），
        // 绕过运营商按 Content-Type / 扩展名的限速，下载完成后改回原名。
        // 仅影响远程路径，本地文件名保持原名不变。
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val shouldRename = (forceRename || account.renameToVideoExt) && ext != "avi"
        // 实际下载用的远程路径（可能被改名为 .avi）
        val downloadPath: String
        if (shouldRename) {
            val tmpPath = renameToVideo(remotePath)
            downloadPath = if (client.move(remotePath, tmpPath)) {
                recordPendingRename(tmpPath, remotePath, account)
                DownloadLog.log(TAG, "rename download: $remotePath -> $tmpPath")
                tmpPath
            } else {
                DownloadLog.log(TAG, "rename download MOVE failed, fallback original: $remotePath")
                remotePath
            }
        } else {
            downloadPath = remotePath
        }

        val taskId = taskDao.insert(
            DownloadTaskEntity(
                fileName = fileName,
                remoteUrl = remotePath,
                localPath = localFile.absolutePath,
                totalBytes = totalBytes,
                downloadedBytes = 0,
                status = TransferStatus.QUEUED.name,
                isUpload = false,
                accountId = account.id,
                authHeader = client.authHeader()
            )
        )
        launchEngine(taskId, account, downloadPath, localFile, totalBytes) {
            // 下载结束后（无论成败），把服务器端文件改回原名，并清理持久化记录
            if (shouldRename && downloadPath != remotePath) {
                try {
                    client.move(downloadPath, remotePath)
                    removePendingRename(downloadPath)
                    DownloadLog.log(TAG, "rename back: $downloadPath -> $remotePath")
                } catch (e: Exception) {
                    DownloadLog.log(TAG, "rename back failed: ${e.message}")
                }
            }
        }
        return@withContext taskId
    }

    private fun recordPendingRename(tmpPath: String, originalPath: String, account: com.example.myfile.model.WebDavAccount) {
        try {
            val json = org.json.JSONObject().apply {
                put("originalPath", originalPath)
                put("accountId", account.id)
                put("url", account.url)
                put("username", account.username)
                put("password", account.password)
            }.toString()
            renamePrefs.edit().putString(tmpPath, json).apply()
        } catch (_: Exception) {}
    }

    private fun removePendingRename(tmpPath: String) {
        renamePrefs.edit().remove(tmpPath).apply()
    }

    /**
     * 自动恢复机制：若之前改名下载过程中程序闪退、被系统强杀或断网中断，
     * 在重新启动或初始化时自动将服务器端残留的 .avi 临时文件名改回原始文件名。
     */
    fun recoverPendingRenames() {
        scope.launch {
            val all = renamePrefs.all
            if (all.isEmpty()) return@launch
            DownloadLog.log(TAG, "recovering ${all.size} pending renames...")
            for ((tmpPath, value) in all) {
                val jsonStr = value as? String ?: continue
                try {
                    val obj = org.json.JSONObject(jsonStr)
                    val originalPath = obj.getString("originalPath")
                    val acc = com.example.myfile.model.WebDavAccount(
                        id = obj.optLong("accountId", 0L),
                        name = "Recover",
                        url = obj.getString("url"),
                        username = obj.getString("username"),
                        password = obj.getString("password")
                    )
                    val client = clientProvider(acc)
                    val ok = client.move(tmpPath, originalPath)
                    DownloadLog.log(TAG, "recovered pending rename: $tmpPath -> $originalPath, result=$ok")
                    removePendingRename(tmpPath)
                } catch (e: Exception) {
                    DownloadLog.log(TAG, "failed recovering pending rename $tmpPath: ${e.message}")
                }
            }
        }
    }

    data class StreamingRename(
        val account: com.example.myfile.model.WebDavAccount,
        val tmpPath: String,
        val originalPath: String
    )

    private val activeStreamingRenames = java.util.concurrent.ConcurrentHashMap<String, StreamingRename>()

    /** 针对在线流式播放：临时把远程文件改名为 .avi，加速流媒体传输 */
    suspend fun startStreamingRename(
        account: com.example.myfile.model.WebDavAccount,
        remotePath: String
    ): String? = withContext(Dispatchers.IO) {
        val ext = remotePath.substringAfterLast('.', "").lowercase()
        if (ext == "avi") return@withContext remotePath
        val client = clientProvider(account)
        val tmpPath = renameToVideo(remotePath)
        if (client.move(remotePath, tmpPath)) {
            recordPendingRename(tmpPath, remotePath, account)
            activeStreamingRenames[remotePath] = StreamingRename(account, tmpPath, remotePath)
            DownloadLog.log(TAG, "streaming rename: $remotePath -> $tmpPath")
            tmpPath
        } else {
            DownloadLog.log(TAG, "streaming rename MOVE failed: $remotePath")
            null
        }
    }

    suspend fun finishStreamingRename(remotePath: String) = withContext(Dispatchers.IO) {
        val rename = activeStreamingRenames.remove(remotePath) ?: return@withContext
        restoreRemoteFile(rename.account, rename.tmpPath, rename.originalPath)
    }

    suspend fun finishAllStreamingRenames() = withContext(Dispatchers.IO) {
        val all = activeStreamingRenames.values.toList()
        activeStreamingRenames.clear()
        for (rename in all) {
            restoreRemoteFile(rename.account, rename.tmpPath, rename.originalPath)
        }
    }

    suspend fun restoreRemoteFile(
        account: com.example.myfile.model.WebDavAccount,
        tmpPath: String,
        originalPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (tmpPath == originalPath) return@withContext true
        val client = clientProvider(account)
        try {
            val ok = client.move(tmpPath, originalPath)
            if (ok) {
                removePendingRename(tmpPath)
                DownloadLog.log(TAG, "restore remote file: $tmpPath -> $originalPath")
            }
            ok
        } catch (e: Exception) {
            DownloadLog.log(TAG, "restore remote file failed: ${e.message}")
            false
        }
    }

    /** 生成一个 .avi 后缀的临时远程路径（同目录、唯一名，避免覆盖已有文件） */
    fun renameToVideo(remotePath: String): String {
        val p = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        val dir = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        // 去掉原扩展名后加 .avi，并加时间戳避免重名
        val base = name.substringBeforeLast('.', name)
        val ts = System.currentTimeMillis() % 100000
        return if (dir.isEmpty() || dir == "/") "/$base.mf$ts.avi" else "$dir/$base.mf$ts.avi"
    }

    private fun launchEngine(
        taskId: Long,
        account: com.example.myfile.model.WebDavAccount,
        remotePath: String,
        localFile: File,
        totalBytes: Long,
        onFinished: (() -> Unit)? = null
    ) {
        val job = scope.launch {
            try {
                taskDao.updateStatus(taskId, TransferStatus.DOWNLOADING.name, null)
                val settings = settingsProvider()
                val config = DownloadConfig(
                    chunkSize = settings.chunkSize,
                    maxConnections = settings.maxConnections,
                    maxRetries = settings.maxRetries,
                    connectTimeoutSec = settings.connectTimeoutSec,
                    readTimeoutSec = settings.readTimeoutSec,
                    singleConnectionMode = settings.singleConnectionMode,
                    streamPulseMode = settings.streamPulseMode
                )
                // 构建多个 client（主地址 + 备用端口），用于轮换绕过限速
                val clients = account.allUrls().map { url ->
                    clientProvider(account.copy(url = url))
                }
                DownloadLog.log(TAG, "download with ${clients.size} clients (ports: ${clients.map { portOfUrl(it) }}) mode=${if (config.singleConnectionMode) "single" else "parallel"}")
                val client = clients.first()

                if (config.singleConnectionMode) {
                    // 单连接完整 GET（不用 Range），模拟 CX 文件浏览器的行为
                    DownloadLog.log(TAG, "using single connection full GET mode")
                    fallbackSingle(client, remotePath, localFile, taskId, totalBytes)
                    taskDao.markCompleted(taskId)
                } else {
                    val engine = ParallelDownloadEngine(clients, config, chunkDao)
                    val raf = RandomAccessFile(localFile, "rw").apply { setLength(totalBytes) }
                    var lastProgressJob: kotlinx.coroutines.Job? = null
                    raf.use {
                        val result = engine.startDownload(remotePath, it, taskId, totalBytes) { downloaded, _ ->
                            lastProgressJob = scope.launch {
                                taskDao.updateProgress(taskId, downloaded)
                            }
                        }
                        // 等待可能尚未完成的并发进度入库协程
                        try { lastProgressJob?.join() } catch (_: Exception) {}

                        when (result) {
                            DownloadResult.Success -> taskDao.markCompleted(taskId)
                            DownloadResult.UnsupportedRange -> {
                                // 回退单连接顺序下载
                                fallbackSingle(client, remotePath, localFile, taskId, totalBytes)
                                taskDao.markCompleted(taskId)
                            }
                            DownloadResult.Canceled -> taskDao.updateStatus(taskId, TransferStatus.CANCELED.name, null)
                            is DownloadResult.Failed -> taskDao.updateStatus(taskId, TransferStatus.FAILED.name, result.message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                taskDao.updateStatus(taskId, TransferStatus.FAILED.name, e.message)
            } finally {
                try { onFinished?.invoke() } catch (e: Exception) {
                    DownloadLog.log(TAG, "onFinished error: ${e.message}")
                }
                mutex.withLock { runningTasks.remove(taskId) }
            }
        }
        scope.launch { mutex.withLock { runningTasks[taskId] = job } }
    }

    /** 服务器不支持 Range 时的单连接回退 */
    private suspend fun fallbackSingle(
        client: WebDavClient,
        path: String,
        localFile: File,
        taskId: Long,
        totalBytes: Long
    ) {
        val resp = client.download(path)
        resp.use { r ->
            r.body?.byteStream()?.use { input ->
                localFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total - lastReport > 512 * 1024) {
                            taskDao.updateProgress(taskId, total)
                            lastReport = total
                        }
                    }
                    taskDao.markCompleted(taskId)
                }
            }
        }
    }

    suspend fun pause(taskId: Long) {
        runningTasks[taskId]?.cancel()
        mutex.withLock { runningTasks.remove(taskId) }
        taskDao.updateStatus(taskId, TransferStatus.PAUSED.name, null)
    }

    suspend fun resume(taskId: Long, account: com.example.myfile.model.WebDavAccount) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        if (task.status == TransferStatus.COMPLETED.name) return@withContext
        val settings = settingsProvider()
        val ext = task.fileName.substringAfterLast('.', "").lowercase()
        val shouldRename = settings.renameToVideoExt && ext != "avi"
        val client = clientProvider(account)
        val downloadPath: String
        if (shouldRename) {
            val tmpPath = renameToVideo(task.remoteUrl)
            downloadPath = if (client.move(task.remoteUrl, tmpPath)) {
                recordPendingRename(tmpPath, task.remoteUrl, account)
                DownloadLog.log(TAG, "resume rename download: ${task.remoteUrl} -> $tmpPath")
                tmpPath
            } else {
                DownloadLog.log(TAG, "resume rename download MOVE failed, fallback: ${task.remoteUrl}")
                task.remoteUrl
            }
        } else {
            downloadPath = task.remoteUrl
        }
        launchEngine(taskId, account, downloadPath, File(task.localPath), task.totalBytes) {
            if (shouldRename && downloadPath != task.remoteUrl) {
                try {
                    client.move(downloadPath, task.remoteUrl)
                    removePendingRename(downloadPath)
                    DownloadLog.log(TAG, "resume rename back: $downloadPath -> ${task.remoteUrl}")
                } catch (e: Exception) {
                    DownloadLog.log(TAG, "resume rename back failed: ${e.message}")
                }
            }
        }
    }

    suspend fun cancel(taskId: Long) {
        runningTasks[taskId]?.cancel()
        mutex.withLock { runningTasks.remove(taskId) }
        taskDao.updateStatus(taskId, TransferStatus.CANCELED.name, null)
    }

    suspend fun delete(taskId: Long) {
        cancel(taskId)
        chunkDao.deleteByTask(taskId)
        taskDao.delete(taskId)
    }

    private fun portOfUrl(c: WebDavClient): String = try {
        java.net.URI(c.baseUrlString()).port.toString()
    } catch (e: Exception) { "?" }

    private fun DownloadTaskEntity.toUi(): TransferTask {
        val parsedStatus = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.QUEUED)
        val finalStatus = if (parsedStatus == TransferStatus.DOWNLOADING && totalBytes > 0 && downloadedBytes >= totalBytes) {
            TransferStatus.COMPLETED
        } else {
            parsedStatus
        }
        return TransferTask(
            id = id,
            fileName = fileName,
            remoteUrl = remoteUrl,
            localPath = localPath,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = 0,
            status = finalStatus,
            chunks = emptyList(),
            isUpload = isUpload,
            errorMessage = errorMessage
        )
    }
}
