package com.example.myfile.core.ota

import android.content.Context
import android.util.Log
import com.example.myfile.BuildConfig
import com.example.myfile.MyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTA 更新数据实体
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val apkSize: Long = 0L,
    val releaseDate: String = "",
    val forceUpdate: Boolean = false,
    val changelog: String = "",
    val hasUpdate: Boolean = false
)

object OtaManager {

    private const val TAG = "OtaManager"

    /** 远程版本配置地址 */
    const val DEFAULT_VERSION_URL = "https://chat.a66.nasnas.site/web/myfile/version.json"

    /** 默认 APK 下载地址回退 */
    const val DEFAULT_APK_URL = "https://chat.a66.nasnas.site/web/myfile/myfile.apk"

    /**
     * 检查远程 OTA 更新
     */
    suspend fun checkUpdate(url: String = DEFAULT_VERSION_URL): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val client = MyApp.instance.okHttpClient
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MyFile-Android/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(IOException("版本响应体为空"))
            val json = JSONObject(body)

            val remoteVersionCode = json.optInt("versionCode", 0)
            val remoteVersionName = json.optString("versionName", "")
            var downloadUrl = json.optString("downloadUrl", "")
            if (downloadUrl.isBlank()) {
                downloadUrl = DEFAULT_APK_URL
            }
            val apkSize = json.optLong("apkSize", 0L)
            val releaseDate = json.optString("releaseDate", "")
            val forceUpdate = json.optBoolean("forceUpdate", false)
            val changelog = json.optString("changelog", "有新版本可用")

            val currentVersionCode = BuildConfig.VERSION_CODE
            val hasUpdate = remoteVersionCode > currentVersionCode

            Log.i(TAG, "OTA 检查完成: 本地版本=$currentVersionCode, 远程版本=$remoteVersionCode ($remoteVersionName), hasUpdate=$hasUpdate")

            Result.success(
                UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                    releaseDate = releaseDate,
                    forceUpdate = forceUpdate,
                    changelog = changelog,
                    hasUpdate = hasUpdate
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "OTA 检查更新失败", e)
            Result.failure(e)
        }
    }

    /**
     * 下载更新包 APK 到应用专用缓存目录
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        isCanceled: AtomicBoolean
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val otaDir = File(context.externalCacheDir ?: context.cacheDir, "ota")
            if (!otaDir.exists()) otaDir.mkdirs()

            val destFile = File(otaDir, "myfile_update.apk")
            if (destFile.exists()) destFile.delete()

            val client = MyApp.instance.okHttpClient
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "MyFile-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("响应体为空"))
            val totalBytes = body.contentLength()

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(32 * 1024)
            var downloaded = 0L
            var lastReportTime = 0L

            try {
                while (true) {
                    if (isCanceled.get()) {
                        destFile.delete()
                        return@withContext Result.failure(IOException("已取消下载"))
                    }

                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastReportTime >= 100 || downloaded == totalBytes) {
                        lastReportTime = now
                        onProgress(downloaded, totalBytes)
                    }
                }
                outputStream.flush()
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
                try { outputStream.close() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }

            Log.i(TAG, "OTA 安装包下载完成: ${destFile.absolutePath}, 大小: ${destFile.length()} 字节")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "OTA 下载失败", e)
            Result.failure(e)
        }
    }
}
