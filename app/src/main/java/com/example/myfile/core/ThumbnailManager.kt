package com.example.myfile.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

object ThumbnailManager {

    private fun getCacheFile(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "thumb_cache").apply { if (!exists()) mkdirs() }
        return File(dir, "$hash.jpg")
    }

    /**
     * 检查本地是否已有缓存的缩略图文件
     */
    fun getCachedThumbnail(context: Context, key: String): File? {
        val file = getCacheFile(context, key)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 为 WebDAV 图片智能提取缩略图：
     * 1. 优先通过 HTTP Range: bytes=0-131071 (128KB) 请求头部，提取内嵌的 EXIF 缩略图（绝大多数手机和相机拍照都包含）
     * 2. 若无 EXIF 缩略图且文件 <= 1.5MB，允许全量下载生成本地 128x128 缩略图
     * 3. 若文件 > 1.5MB 且无 EXIF 缩略图，则返回 null，避免在列表过度下载大文件偷跑流量
     */
    suspend fun getOrFetchWebDavImageThumb(
        context: Context,
        account: WebDavAccount,
        entry: FileEntry
    ): File? = withContext(Dispatchers.IO) {
        val key = "${account.url}|${entry.path}"
        val cached = getCachedThumbnail(context, key)
        if (cached != null) return@withContext cached

        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val fullUrl = account.url.trimEnd('/') + p
        val auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray())

        val isJpeg = entry.name.endsWith(".jpg", ignoreCase = true) ||
                     entry.name.endsWith(".jpeg", ignoreCase = true)

        val targetFile = getCacheFile(context, key)

        // 1. 对于 JPEG 图片，尝试用 HTTP Range (0-128KB) 快速探测 EXIF 内置缩略图
        if (isJpeg) {
            try {
                val rangeRequest = Request.Builder()
                    .url(fullUrl)
                    .addHeader("Authorization", auth)
                    .addHeader("Range", "bytes=0-131071")
                    .build()

                MyApp.instance.okHttpClient.newCall(rangeRequest).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.size >= 128) {
                            val exif = ExifInterface(ByteArrayInputStream(bytes))
                            if (exif.hasThumbnail()) {
                                val thumbBytes = exif.thumbnailBytes
                                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                                    targetFile.writeBytes(thumbBytes)
                                    return@withContext targetFile
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. 若文件较小 (<= 1.5MB)，可下载并生成缩略图
        if (entry.size in 1..1_500_000L) {
            try {
                val request = Request.Builder()
                    .url(fullUrl)
                    .addHeader("Authorization", auth)
                    .build()
                MyApp.instance.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            val maxDim = maxOf(opts.outWidth, opts.outHeight)
                            var sampleSize = 1
                            while (maxDim / sampleSize > 128) {
                                sampleSize *= 2
                            }
                            opts.inJustDecodeBounds = false
                            opts.inSampleSize = sampleSize
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                            if (bmp != null) {
                                targetFile.outputStream().use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                bmp.recycle()
                                return@withContext targetFile
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 超过 1.5MB 且无 EXIF 缩略图：不下载全量大图，返回 null（显示默认类型图标），杜绝偷跑流量
        null
    }
}
