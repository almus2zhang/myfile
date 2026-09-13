package com.example.myfile.core

import android.util.Log
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * WebDAV 文件索引（webdav_index.json）。
 *
 * 索引文件格式：一个 JSON 数组，元素为 [路径, 字节大小, Unix时间戳(秒)] 三元组，
 * 目录以 "/" 结尾且大小为 0，例如：
 *   [
 *     ["/path/to/folder/", 0, 1726148400],
 *     ["/path/to/file.pdf", 1048576, 1726148520]
 *   ]
 *
 * 支持特性：
 * 1. 本地磁盘持久化缓存（启动时秒级预热）
 * 2. 检查服务器 ETag / Last-Modified / Content-Length，服务器未更新时不重复下载
 * 3. 搜索语法：空格分词，每个词做包含匹配（不区分大小写），多个词为 AND 关系
 */
object WebDavIndex {

    private const val TAG = "WebDavIndex"
    private const val DEFAULT_INDEX_NAME = "webdav_index.json"

    /** 单条索引记录 */
    data class IndexEntry(
        val path: String,
        val size: Long,
        val modifiedSec: Long
    ) {
        val isDirectory: Boolean get() = path.endsWith("/")
    }

    /** 索引元数据（用于判断服务器是否有更新） */
    data class IndexMetadata(
        val etag: String = "",
        val lastModified: String = "",
        val contentLength: Long = 0L,
        val totalCount: Int = 0,
        val lastSyncTime: Long = 0L,
        val indexTime: Long = 0L
    )

    data class IndexProgress(
        val isDownloading: Boolean = false,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val percentage: Int = -1,
        val message: String = ""
    )

    sealed class SyncResult {
        /** 服务器未更新，直接复用本地缓存 */
        data class UpToDate(val count: Int, val file: File) : SyncResult()
        /** 成功从服务器下载并解析了新索引 */
        data class Downloaded(val count: Int, val file: File) : SyncResult()
        /** 同步失败 */
        data class Error(val message: String) : SyncResult()
    }

    /** 内存索引缓存：accountId -> 索引记录列表 */
    private val cache = ConcurrentHashMap<Long, List<IndexEntry>>()

    /**
     * 为指定配置创建专用的本地存储文件夹：
     * 路径形如：files/indexes/[配置名]_[配置ID]/
     */
    fun getAccountIndexDir(account: WebDavAccount): File {
        val safeName = account.name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { "account" }
        val dir = File(MyApp.instance.filesDir, "indexes/${safeName}_${account.id}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getIndexFile(account: WebDavAccount): File = File(getAccountIndexDir(account), "webdav_index.json")
    fun getMetaFile(account: WebDavAccount): File = File(getAccountIndexDir(account), "index_meta.json")

    /**
     * 根据 accountId 获取本地索引文件（兼容旧路径与新按配置命名的路径）
     */
    fun getIndexFile(accountId: Long): File {
        val baseDir = File(MyApp.instance.filesDir, "indexes")
        if (baseDir.exists()) {
            val matched = baseDir.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith("_$accountId") }
            if (matched != null) {
                val f = File(matched, "webdav_index.json")
                if (f.exists()) return f
            }
        }
        val legacy = File(File(MyApp.instance.filesDir, "webdav_index"), "index_$accountId.json")
        if (legacy.exists()) return legacy
        return File(File(baseDir, "account_$accountId"), "webdav_index.json")
    }

    fun getMetaFile(accountId: Long): File {
        val baseDir = File(MyApp.instance.filesDir, "indexes")
        if (baseDir.exists()) {
            val matched = baseDir.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith("_$accountId") }
            if (matched != null) {
                val f = File(matched, "index_meta.json")
                if (f.exists()) return f
            }
        }
        val legacy = File(File(MyApp.instance.filesDir, "webdav_index"), "meta_$accountId.json")
        if (legacy.exists()) return legacy
        return File(File(baseDir, "account_$accountId"), "index_meta.json")
    }

    /**
     * 读取本地保存的元数据
     */
    fun loadMetadata(accountId: Long): IndexMetadata {
        val f = getMetaFile(accountId)
        if (!f.exists()) return IndexMetadata()
        return try {
            val json = JSONObject(f.readText())
            IndexMetadata(
                etag = json.optString("etag", ""),
                lastModified = json.optString("lastModified", ""),
                contentLength = json.optLong("contentLength", 0L),
                totalCount = json.optInt("totalCount", 0),
                lastSyncTime = json.optLong("lastSyncTime", 0L),
                indexTime = json.optLong("indexTime", 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "read meta failed", e)
            IndexMetadata()
        }
    }

    private fun saveMetadata(account: WebDavAccount, meta: IndexMetadata) {
        try {
            val json = JSONObject().apply {
                put("etag", meta.etag)
                put("lastModified", meta.lastModified)
                put("contentLength", meta.contentLength)
                put("totalCount", meta.totalCount)
                put("lastSyncTime", meta.lastSyncTime)
                put("indexTime", meta.indexTime)
            }
            getMetaFile(account).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save meta failed", e)
        }
    }

    private val HTTP_DATE_PATTERNS = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )

    fun parseHttpDate(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        for (pattern in HTTP_DATE_PATTERNS) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }
                val d = sdf.parse(str)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    /**
     * 获取指定账户的索引时间戳（毫秒）
     */
    fun getIndexTime(accountId: Long): Long {
        val meta = loadMetadata(accountId)
        if (meta.indexTime > 0L) return meta.indexTime
        if (meta.lastModified.isNotBlank()) {
            val parsed = parseHttpDate(meta.lastModified)
            if (parsed > 0L) return parsed
        }
        if (meta.lastSyncTime > 0L) return meta.lastSyncTime
        val f = getIndexFile(accountId)
        if (f.exists() && f.length() > 0) {
            return f.lastModified()
        }
        return 0L
    }

    /**
     * 判断某账户是否已存在本地索引缓存
     */
    fun hasLocalCache(accountId: Long): Boolean {
        if (cache.containsKey(accountId)) return true
        val f = getIndexFile(accountId)
        return f.exists() && f.length() > 0
    }

    /**
     * 从本地磁盘加载索引到内存（秒级预热，无需网络）
     */
    fun warmupFromDisk(accountId: Long): List<IndexEntry>? {
        cache[accountId]?.let { return it }
        val f = getIndexFile(accountId)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            val text = f.readText()
            val parsed = parseIndex(text)
            if (parsed.isNotEmpty()) {
                cache[accountId] = parsed
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "warmup from disk failed", e)
            null
        }
    }

    /**
     * 获取当前可用索引（优先内存，其次本地磁盘，均无则返回空列表）
     */
    fun getAvailableIndex(accountId: Long): List<IndexEntry> {
        return cache[accountId] ?: warmupFromDisk(accountId) ?: emptyList()
    }

    /**
     * 解析索引文件在 WebDAV 上的完整路径。
     */
    fun resolveIndexPath(account: WebDavAccount): String? {
        val raw = account.indexPath.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("/")) raw else "/$raw"
        return if (normalized.lowercase().endsWith(".json")) {
            normalized
        } else {
            normalized.trimEnd('/') + "/" + DEFAULT_INDEX_NAME
        }
    }

    /**
     * 规范化拼接 WebDAV 索引的完整 URL（自动编码特殊字符，支持动态地址）
     */
    fun buildFullUrl(account: WebDavAccount): String? {
        val remotePath = resolveIndexPath(account) ?: return null
        val base = account.connectionUrl().trim().trimEnd('/')
        val p = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        return try {
            val okUrl = base.toHttpUrlOrNull()
            if (okUrl != null) {
                val builder = okUrl.newBuilder()
                val segments = p.split('/').filter { it.isNotEmpty() }
                for (seg in segments) {
                    builder.addPathSegment(seg)
                }
                builder.build().toString()
            } else {
                base + p
            }
        } catch (e: Exception) {
            base + p
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    /**
     * 检查并下载/更新索引文件。
     * 索引文件将保存在专属配置目录：files/indexes/[配置名]_[配置ID]/webdav_index.json
     */
    fun syncIndex(
        client: OkHttpClient,
        account: WebDavAccount,
        forceRefresh: Boolean = false,
        onProgress: ((IndexProgress) -> Unit)? = null
    ): SyncResult {
        val url = buildFullUrl(account)
            ?: return SyncResult.Error("未配置有效的索引路径")
        val auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray())

        val localFile = getIndexFile(account)
        val localMeta = loadMetadata(account.id)
        val hasLocal = localFile.exists() && localFile.length() > 0

        // 1. 如果本地已有缓存且非强制刷新，先通过轻量 HEAD 请求检查服务器是否更新
        if (hasLocal && !forceRefresh) {
            try {
                onProgress?.invoke(IndexProgress(isDownloading = true, message = "检查服务器索引是否有更新..."))
                val headReq = Request.Builder()
                    .url(url)
                    .header("Authorization", auth)
                    .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
                    .head()
                    .build()
                client.newCall(headReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val remoteEtag = resp.header("ETag")?.trim('"', ' ', 'W', '/') ?: ""
                        val remoteLastMod = resp.header("Last-Modified") ?: ""
                        val remoteLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L

                        val etagMatch = remoteEtag.isNotBlank() && localMeta.etag.isNotBlank() && remoteEtag == localMeta.etag
                        val modLenMatch = remoteLastMod.isNotBlank() && localMeta.lastModified.isNotBlank() &&
                                remoteLastMod == localMeta.lastModified &&
                                remoteLen > 0L && remoteLen == localMeta.contentLength

                        if (etagMatch || modLenMatch) {
                            Log.i(TAG, "Server index not modified. Reusing local cache. (${account.name})")
                            val entries = warmupFromDisk(account.id) ?: emptyList()
                            onProgress?.invoke(IndexProgress(isDownloading = false, percentage = 100, message = "索引已是最新 (${entries.size} 条)"))
                            return SyncResult.UpToDate(entries.size, localFile)
                        }
                    } else if (resp.code == 304) {
                        Log.i(TAG, "Server returned 304 Not Modified. (${account.name})")
                        val entries = warmupFromDisk(account.id) ?: emptyList()
                        onProgress?.invoke(IndexProgress(isDownloading = false, percentage = 100, message = "索引已是最新 (${entries.size} 条)"))
                        return SyncResult.UpToDate(entries.size, localFile)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HEAD request check failed: ${e.message}")
            }
        }

        // 2. 需要从服务器下载索引文件
        onProgress?.invoke(IndexProgress(isDownloading = true, message = "准备连接服务器下载索引..."))
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("User-Agent", "myfile/1.0 (Android; WebDAV)")

            // 若支持条件 GET
            if (hasLocal && !forceRefresh) {
                if (localMeta.etag.isNotBlank()) {
                    reqBuilder.header("If-None-Match", localMeta.etag)
                }
                if (localMeta.lastModified.isNotBlank()) {
                    reqBuilder.header("If-Modified-Since", localMeta.lastModified)
                }
            }

            val call = client.newCall(reqBuilder.get().build())
            val resp = call.execute()
            if (resp.code == 304) {
                resp.close()
                Log.i(TAG, "GET returned 304 Not Modified. Reusing local cache.")
                val entries = warmupFromDisk(account.id) ?: emptyList()
                onProgress?.invoke(IndexProgress(isDownloading = false, percentage = 100, message = "索引已是最新 (${entries.size} 条)"))
                return SyncResult.UpToDate(entries.size, localFile)
            }
            if (!resp.isSuccessful) {
                val code = resp.code
                resp.close()
                val err = "下载索引失败: HTTP $code ($url)"
                onProgress?.invoke(IndexProgress(isDownloading = false, message = err))
                return SyncResult.Error(err)
            }

            val body = resp.body ?: return SyncResult.Error("响应内容为空")
            val newEtag = resp.header("ETag")?.trim('"', ' ', 'W', '/') ?: ""
            val newLastMod = resp.header("Last-Modified") ?: ""
            val totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: -1L

            // 写入当前配置目录下的临时文件
            val targetDir = getAccountIndexDir(account)
            val tmpFile = File(targetDir, "webdav_index.json.tmp")
            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalDownloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        val pct = if (totalBytes > 0) ((totalDownloaded * 100) / totalBytes).toInt() else -1
                        val msg = if (totalBytes > 0) {
                            "下载索引: $pct% (${formatBytes(totalDownloaded)} / ${formatBytes(totalBytes)})"
                        } else {
                            "下载索引: ${formatBytes(totalDownloaded)}"
                        }
                        onProgress?.invoke(
                            IndexProgress(
                                isDownloading = true,
                                bytesDownloaded = totalDownloaded,
                                totalBytes = totalBytes,
                                percentage = pct,
                                message = msg
                            )
                        )
                    }
                    output.flush()
                }
            }

            onProgress?.invoke(IndexProgress(isDownloading = true, message = "正在解析索引文件..."))
            val text = tmpFile.readText()
            val parsed = parseIndex(text)
            if (parsed.isEmpty()) {
                tmpFile.delete()
                val err = "索引文件解析失败或内容为空"
                onProgress?.invoke(IndexProgress(isDownloading = false, message = err))
                return SyncResult.Error(err)
            }

            // 原子替换配置目录下的正式文件
            if (localFile.exists()) localFile.delete()
            tmpFile.renameTo(localFile)

            var jsonTime = 0L
            try {
                val trimmed = text.trimStart()
                if (!trimmed.startsWith("[")) {
                    val rootObj = JSONObject(trimmed)
                    val candidate = rootObj.optLong("time", 0L).takeIf { it > 0 }
                        ?: rootObj.optLong("timestamp", 0L).takeIf { it > 0 }
                        ?: rootObj.optLong("index_time", 0L).takeIf { it > 0 }
                        ?: rootObj.optLong("generated_at", 0L).takeIf { it > 0 }
                        ?: rootObj.optLong("mtime", 0L).takeIf { it > 0 }
                        ?: 0L
                    if (candidate > 0L) {
                        jsonTime = if (candidate < 10000000000L) candidate * 1000L else candidate
                    }
                }
            } catch (_: Exception) {}

            val parsedModTime = parseHttpDate(newLastMod)
            val actualIndexTime = when {
                jsonTime > 0L -> jsonTime
                parsedModTime > 0L -> parsedModTime
                else -> System.currentTimeMillis()
            }

            // 更新元数据
            saveMetadata(
                account,
                IndexMetadata(
                    etag = newEtag,
                    lastModified = newLastMod,
                    contentLength = if (totalBytes > 0) totalBytes else localFile.length(),
                    totalCount = parsed.size,
                    lastSyncTime = System.currentTimeMillis(),
                    indexTime = actualIndexTime
                )
            )

            // 更新内存缓存
            cache[account.id] = parsed
            Log.i(TAG, "Index synced successfully for ${account.name}: ${parsed.size} items to ${localFile.absolutePath}")
            val successMsg = "索引下载完成，共 ${parsed.size} 条"
            onProgress?.invoke(
                IndexProgress(
                    isDownloading = false,
                    bytesDownloaded = localFile.length(),
                    totalBytes = localFile.length(),
                    percentage = 100,
                    message = successMsg
                )
            )
            SyncResult.Downloaded(parsed.size, localFile)
        } catch (e: Exception) {
            Log.e(TAG, "sync index error for ${account.name}", e)
            val fallback = warmupFromDisk(account.id)
            if (fallback != null && fallback.isNotEmpty()) {
                val msg = "下载失败，已降级使用本地缓存 (${fallback.size} 条)"
                onProgress?.invoke(IndexProgress(isDownloading = false, message = msg))
                SyncResult.UpToDate(fallback.size, localFile)
            } else {
                val err = "下载索引异常: ${e.message ?: "网络超时"}"
                onProgress?.invoke(IndexProgress(isDownloading = false, message = err))
                SyncResult.Error(err)
            }
        }
    }

    /**
     * 兼容方法：加载索引（带缓存）。
     */
    fun loadIndex(
        client: OkHttpClient,
        account: WebDavAccount,
        forceRefresh: Boolean = false
    ): List<IndexEntry> {
        val cached = if (!forceRefresh) cache[account.id] ?: warmupFromDisk(account.id) else null
        if (cached != null) return cached
        return when (val res = syncIndex(client, account, forceRefresh)) {
            is SyncResult.UpToDate -> getAvailableIndex(account.id)
            is SyncResult.Downloaded -> getAvailableIndex(account.id)
            is SyncResult.Error -> getAvailableIndex(account.id)
        }
    }

    /**
     * 解析索引 JSON。
     */
    private fun parseIndex(text: String): List<IndexEntry> {
        return try {
            val trimmed = text.trimStart()
            val arr: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = org.json.JSONObject(trimmed)
                val key = obj.keys().asSequence().firstOrNull { obj.opt(it) is JSONArray }
                    ?: return emptyList()
                obj.getJSONArray(key)
            }
            val result = ArrayList<IndexEntry>(arr.length())
            for (i in 0 until arr.length()) {
                when (val el = arr.opt(i)) {
                    is JSONArray -> {
                        val p = el.optString(0, "")
                        if (p.isNotBlank()) {
                            result.add(
                                IndexEntry(
                                    path = p,
                                    size = el.optLong(1, 0L),
                                    modifiedSec = el.optLong(2, 0L)
                                )
                            )
                        }
                    }
                    is String -> {
                        if (el.isNotBlank()) result.add(IndexEntry(el, 0L, 0L))
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "parse index error", e)
            emptyList()
        }
    }

    /**
     * 按搜索词过滤索引。
     */
    fun search(index: List<IndexEntry>, keywords: List<String>): List<IndexEntry> {
        if (keywords.isEmpty()) return index
        return index.filter { entry ->
            val lower = entry.lastSegment().lowercase()
            keywords.all { lower.contains(it) }
        }
    }

    private fun IndexEntry.lastSegment(): String {
        val p = if (isDirectory) path.trimEnd('/') else path
        return p.substringAfterLast('/').ifEmpty { p }
    }

    fun toFileEntries(entries: List<IndexEntry>): List<FileEntry> {
        return entries.map { e ->
            val trimmedPath = if (e.isDirectory) e.path.trimEnd('/') else e.path
            val name = trimmedPath.substringAfterLast('/').ifEmpty { trimmedPath }
            FileEntry(
                name = name,
                path = e.path,
                isDirectory = e.isDirectory,
                size = e.size,
                lastModified = if (e.modifiedSec > 0) e.modifiedSec * 1000L else 0L
            )
        }
    }

    fun parseKeywords(query: String): List<String> {
        return query.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
    }

    /**
     * 清除某账户索引缓存（同时清除内存与本地磁盘文件）
     */
    fun clearCache(accountId: Long) {
        cache.remove(accountId)
        try {
            getIndexFile(accountId).delete()
            getMetaFile(accountId).delete()
        } catch (_: Exception) {}
    }
}
