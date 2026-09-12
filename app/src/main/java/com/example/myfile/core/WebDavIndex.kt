package com.example.myfile.core

import android.util.Log
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
 * 搜索语法：空格分词，每个词做「*词*」包含匹配（不区分大小写），多个词为 AND 关系。
 *   例如输入 "abc .p" → 匹配同时包含 "abc" 和 ".p" 的路径。
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
        /** 目录判定：路径以 "/" 结尾 */
        val isDirectory: Boolean get() = path.endsWith("/")
    }

    /** 账户索引缓存：accountId -> 索引记录列表 */
    private val cache = ConcurrentHashMap<Long, List<IndexEntry>>()

    /**
     * 解析索引文件在 WebDAV 上的完整路径。
     * - 配置为空 → 返回 null（不启用）
     * - 以 .json 结尾 → 直接用该文件
     * - 否则视为目录 → 在目录下拼上 webdav_index.json
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
     * 加载索引（带缓存）。返回路径列表，失败返回空表。
     */
    fun loadIndex(
        client: OkHttpClient,
        account: WebDavAccount,
        forceRefresh: Boolean = false
    ): List<IndexEntry> {
        if (!forceRefresh) {
            cache[account.id]?.let { return it }
        }
        val indexRemotePath = resolveIndexPath(account) ?: return emptyList()
        val base = account.url.trimEnd('/')
        val url = base + indexRemotePath
        return try {
            val auth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("${account.username}:${account.password}".toByteArray())
            val req = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
                .get()
                .build()
            val text = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "load index failed: HTTP ${resp.code} for $url")
                    return emptyList()
                }
                resp.body?.string() ?: return emptyList()
            }
            val parsed = parseIndex(text)
            cache[account.id] = parsed
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "load index error", e)
            emptyList()
        }
    }

    /**
     * 解析索引 JSON。
     * 支持两种元素写法：
     *   - [路径, 大小, 时间戳] 三元组（当前格式）
     *   - "路径" 纯字符串（旧格式兼容）
     */
    private fun parseIndex(text: String): List<IndexEntry> {
        return try {
            val trimmed = text.trimStart()
            val arr: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                // 兼容对象形式 { "files": [...] }
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
     * 只匹配「最后一级名称」（文件名，或目录的最后一级目录名），父目录不参与匹配。
     * @param keywords 空格分词后的关键词（已小写化）。空则返回全部。
     */
    fun search(index: List<IndexEntry>, keywords: List<String>): List<IndexEntry> {
        if (keywords.isEmpty()) return index
        return index.filter { entry ->
            val lower = entry.lastSegment().lowercase()
            keywords.all { lower.contains(it) }
        }
    }

    /** 取路径的最后一级名称（目录会去掉结尾的 "/"） */
    private fun IndexEntry.lastSegment(): String {
        val p = if (isDirectory) path.trimEnd('/') else path
        return p.substringAfterLast('/').ifEmpty { p }
    }

    /**
     * 把索引记录转成 FileEntry 列表（用于复用主视图展示与操作）。
     */
    fun toFileEntries(entries: List<IndexEntry>): List<FileEntry> {
        return entries.map { e ->
            // 目录：去掉结尾的 "/" 再取最后一段
            val trimmedPath = if (e.isDirectory) e.path.trimEnd('/') else e.path
            val name = trimmedPath.substringAfterLast('/').ifEmpty { trimmedPath }
            FileEntry(
                name = name,
                path = e.path,
                isDirectory = e.isDirectory,
                size = e.size,
                // 索引里是秒级时间戳，转换为毫秒以匹配 FileEntry 约定
                lastModified = if (e.modifiedSec > 0) e.modifiedSec * 1000L else 0L
            )
        }
    }

    /** 把搜索词拆分成小写关键词列表 */
    fun parseKeywords(query: String): List<String> {
        return query.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
    }

    /** 清除某账户索引缓存 */
    fun clearCache(accountId: Long) {
        cache.remove(accountId)
    }
}
