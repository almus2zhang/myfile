package com.example.myfile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.myfile.MyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * 文件打开逻辑。与 CX 浏览器一致：
 * - 「默认程序」由 myfile 自己记录（DefaultAppStore），与系统默认应用无关
 * - 点文件：查映射，有默认程序则直接用它打开，没有则弹「打开方式」选择对话框
 * - 「打开为」：强制弹选择对话框，选「始终」后写入映射
 */
object FileOpener {

    /** 打开本地文件的 ACTION_VIEW 意图（content:// URI） */
    fun buildLocalViewIntent(context: Context, file: File): Intent? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = guessMime(file.name)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e("FileOpener", "build local intent failed", e)
            null
        }
    }

    /** 构造远程视频的流式打开意图（本地 http://127.0.0.1 链接） */
    fun buildVideoStreamIntent(
        client: OkHttpClient,
        account: com.example.myfile.model.WebDavAccount,
        remotePath: String,
        fileName: String,
        fakeAvi: Boolean = false
    ): Intent? {
        return try {
            val displayName = if (fakeAvi) {
                val base = fileName.substringBeforeLast('.').ifBlank { "video" }
                "$base.avi"
            } else {
                fileName
            }
            val localUrl = StreamProxy.register(client, account, remotePath, displayName, fakeAvi)
            val mime = if (fakeAvi) "video/*" else guessMime(fileName)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(localUrl), mime)
            }
        } catch (e: Exception) {
            Log.e("FileOpener", "build video intent failed", e)
            null
        }
    }

    /** 查询能处理该意图的所有候选应用 */
    fun resolveCandidates(context: Context, intent: Intent): List<AppCandidate> =
        AppResolver.resolveCandidates(context, intent)

    /**
     * 用 myfile 自己记录的默认程序打开。
     * @return true = 已用默认程序打开；false = 无默认程序，需弹选择器
     */
    suspend fun openWithDefault(context: Context, category: String, intent: Intent): Boolean {
        val default = MyApp.instance.defaultAppStore.get(category) ?: return false
        val parts = default.split('/')
        if (parts.size != 2) return false
        val candidate = AppCandidate(
            label = "",
            packageName = parts[0],
            activityName = parts[1],
            icon = null
        )
        return AppResolver.openWith(context, intent, candidate)
    }

    /** 记录某类别的默认程序 */
    suspend fun setDefault(category: String, candidate: AppCandidate) {
        MyApp.instance.defaultAppStore.set(category, candidate.packageName, candidate.activityName)
    }

    /** 清除某类别的默认程序 */
    suspend fun clearDefault(category: String) {
        MyApp.instance.defaultAppStore.clear(category)
    }

    /** 用指定候选应用打开 */
    fun openWith(context: Context, intent: Intent, candidate: AppCandidate): Boolean =
        AppResolver.openWith(context, intent, candidate)

    /** 调用 Android 系统打开方式选择器 */
    fun openWithSystemChooser(context: Context, intent: Intent, title: String = "打开为") =
        AppResolver.openWithSystemChooser(context, intent, title)

    /**
     * 打开远程 WebDAV 文件（非视频）：流式下载到 cache，返回本地文件。
     * 返回 null 表示下载失败。
     */
    suspend fun downloadToCache(
        client: OkHttpClient,
        authHeader: String,
        url: String,
        fileName: String,
        onProgress: ((Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val tmp = File(MyApp.instance.cacheDir, "open_${System.currentTimeMillis()}_$fileName")
        try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStream = resp.body?.byteStream() ?: return@withContext null
                bodyStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            onProgress?.invoke(total)
                        }
                    }
                }
            }
            tmp
        } catch (e: Exception) {
            Log.e("FileOpener", "download to cache failed", e)
            tmp.delete()
            null
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "txt", "log" -> "text/plain"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "m4v" -> "video/x-m4v"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "3gp" -> "video/3gpp"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "*/*"
        }
    }

    /** 视频扩展名集合：这些类型走本地流式代理，其余走缓存下载后打开 */
    private val videoExtensions = setOf(
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "ts", "m4v",
        "mpg", "mpeg", "3gp", "rmvb", "rm", "vob", "m2ts"
    )

    fun isVideo(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    /** 判断扩展名类型（用于图标显示 & 默认程序映射）：video / image / audio / text / archive / doc / apk / other */
    fun fileCategory(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in videoExtensions -> "video"
            in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "ico") -> "image"
            in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus") -> "audio"
            in setOf("txt", "log", "md", "json", "xml", "csv", "ini", "conf", "yml", "yaml") -> "text"
            in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> "archive"
            in setOf("doc", "docx", "pdf", "ppt", "pptx", "xls", "xlsx") -> "doc"
            "apk" -> "apk"
            else -> "other"
        }
    }
}
