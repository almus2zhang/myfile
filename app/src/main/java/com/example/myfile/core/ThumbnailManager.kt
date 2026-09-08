package com.example.myfile.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater

object ThumbnailManager {

    private const val TAG = "ThumbnailManager"

    private fun getCacheFile(context: Context, key: String, isPng: Boolean = false): File {
        val hash = MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "thumb_cache").apply { if (!exists()) mkdirs() }
        val ext = if (isPng) "png" else "jpg"
        return File(dir, "$hash.$ext")
    }

    /**
     * 检查本地是否已有缓存的缩略图文件
     */
    fun getCachedThumbnail(context: Context, key: String, isPng: Boolean = false): File? {
        val file = getCacheFile(context, key, isPng)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 为本地 APK 解析应用图标并持久化缓存为 PNG
     */
    fun getOrFetchLocalApkThumb(context: Context, apkFile: File): File? {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0) return null
        val key = "local_apk_${apkFile.absolutePath}_${apkFile.lastModified()}_${apkFile.length()}"
        val cached = getCachedThumbnail(context, key, isPng = true)
        if (cached != null) return cached

        val targetFile = getCacheFile(context, key, isPng = true)
        return try {
            val pm = context.packageManager
            val pi = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            val appInfo = pi.applicationInfo ?: return null
            appInfo.sourceDir = apkFile.absolutePath
            appInfo.publicSourceDir = apkFile.absolutePath
            val drawable = appInfo.loadIcon(pm) ?: return null
            val bitmap = drawableToBitmap(drawable, 144, 144)
            targetFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract apk icon for ${apkFile.name}", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    /**
     * 将任意 Drawable 转换为规整的高清 Bitmap，保证各类矢量和自适应图标安全呈现
     */
    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width == width && bmp.height == height) return bmp
            return Bitmap.createScaledBitmap(bmp, width, height, true)
        }
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else width
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else height
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return if (w != width || h != height) {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled != bitmap) bitmap.recycle()
            scaled
        } else {
            bitmap
        }
    }

    private data class ZipEntryInfo(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long
    )

    private fun readIntLE(b: ByteArray, o: Int): Int {
        return (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(b: ByteArray, o: Int): Short {
        return ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    private fun evaluateIconPriority(name: String): Int {
        val lower = name.lowercase()
        if (!lower.endsWith(".png") && !lower.endsWith(".webp")) return -1
        if (!lower.contains("icon") && !lower.contains("launcher") && !lower.contains("logo")) return -1
        return when {
            lower.contains("mipmap-xxhdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 100
            lower.contains("mipmap-xhdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 90
            lower.contains("mipmap-xxxhdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 85
            lower.contains("drawable-xxhdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 80
            lower.contains("drawable-xhdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 70
            lower.contains("mipmap-hdpi") && (lower.contains("ic_launcher") || lower.contains("app_icon")) -> 60
            lower.contains("ic_launcher_round") -> 50
            lower.contains("ic_launcher") -> 45
            lower.contains("app_icon") -> 40
            lower.contains("icon") -> 20
            else -> 10
        }
    }

    private fun fetchRange(url: String, auth: String, offset: Long, length: Long): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .addHeader("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        return try {
            MyApp.instance.okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decompressDeflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true) // nowrap = true
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) {
                if (inflater.needsInput()) break
            }
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun saveBitmapToFile(bytes: ByteArray, targetFile: File): File? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val scaled = if (bmp.width > 144 || bmp.height > 144) {
                Bitmap.createScaledBitmap(bmp, 144, 144, true)
            } else bmp
            targetFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (scaled != bmp) scaled.recycle()
            bmp.recycle()
            targetFile
        } catch (e: Exception) {
            bmp.recycle()
            null
        }
    }

    /**
     * 为远程 WebDAV 上的 APK 智能探测应用图标：
     * 通过 Range 请求精准提取 ZIP Central Directory 并定位图标，仅需传输 ~100KB 数据即可提取图标
     */
    suspend fun getOrFetchWebDavApkThumb(
        context: Context,
        account: WebDavAccount,
        entry: FileEntry
    ): File? = withContext(Dispatchers.IO) {
        val key = "acc_${account.id}|${entry.path}|apk"
        val cached = getCachedThumbnail(context, key, isPng = true)
        if (cached != null) return@withContext cached

        if (entry.size <= 0) return@withContext null

        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val fullUrl = account.connectionUrl().trimEnd('/') + p
        val auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray())

        val targetFile = getCacheFile(context, key, isPng = true)

        try {
            // 1. 请求 APK 末尾 64KB 读取 ZIP EOCD (End of Central Directory)
            val readLen = minOf(65536L, entry.size)
            val rangeStart = entry.size - readLen
            val tailBytes = fetchRange(fullUrl, auth, rangeStart, readLen) ?: return@withContext null

            // 寻找 EOCD 签名 0x06054b50
            var eocdOffset = -1
            for (i in tailBytes.size - 22 downTo 0) {
                if (tailBytes[i] == 0x50.toByte() &&
                    tailBytes[i + 1] == 0x4b.toByte() &&
                    tailBytes[i + 2] == 0x05.toByte() &&
                    tailBytes[i + 3] == 0x06.toByte()
                ) {
                    eocdOffset = i
                    break
                }
            }
            if (eocdOffset < 0) return@withContext null

            // 读取 Central Directory 尺寸和偏移
            val cdSize = readIntLE(tailBytes, eocdOffset + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = readIntLE(tailBytes, eocdOffset + 16).toLong() and 0xFFFFFFFFL
            if (cdSize <= 0 || cdSize > 50 * 1024 * 1024L) return@withContext null

            // 获取整个 Central Directory 的字节
            val cdBytes: ByteArray = if (cdOffset >= rangeStart) {
                val startInTail = (cdOffset - rangeStart).toInt()
                if (startInTail + cdSize <= tailBytes.size) {
                    tailBytes.copyOfRange(startInTail, (startInTail + cdSize).toInt())
                } else {
                    fetchRange(fullUrl, auth, cdOffset, cdSize) ?: return@withContext null
                }
            } else {
                fetchRange(fullUrl, auth, cdOffset, cdSize) ?: return@withContext null
            }

            // 遍历 Central Directory 寻找应用图标
            var bestCandidate: ZipEntryInfo? = null
            var bestPriority = -1

            var cursor = 0
            while (cursor + 46 <= cdBytes.size) {
                if (cdBytes[cursor] != 0x50.toByte() ||
                    cdBytes[cursor + 1] != 0x4b.toByte() ||
                    cdBytes[cursor + 2] != 0x01.toByte() ||
                    cdBytes[cursor + 3] != 0x02.toByte()
                ) {
                    break
                }
                val method = readShortLE(cdBytes, cursor + 10).toInt() and 0xFFFF
                val compSize = readIntLE(cdBytes, cursor + 20).toLong() and 0xFFFFFFFFL
                val uncompSize = readIntLE(cdBytes, cursor + 24).toLong() and 0xFFFFFFFFL
                val nameLen = readShortLE(cdBytes, cursor + 28).toInt() and 0xFFFF
                val extraLen = readShortLE(cdBytes, cursor + 30).toInt() and 0xFFFF
                val commentLen = readShortLE(cdBytes, cursor + 32).toInt() and 0xFFFF
                val localHeaderOffset = readIntLE(cdBytes, cursor + 42).toLong() and 0xFFFFFFFFL

                if (cursor + 46 + nameLen <= cdBytes.size) {
                    val entryName = String(cdBytes, cursor + 46, nameLen, Charsets.UTF_8)
                    val priority = evaluateIconPriority(entryName)
                    if (priority > bestPriority && compSize in 1..2_000_000L) {
                        bestPriority = priority
                        bestCandidate = ZipEntryInfo(
                            name = entryName,
                            method = method,
                            compressedSize = compSize,
                            uncompressedSize = uncompSize,
                            localHeaderOffset = localHeaderOffset
                        )
                    }
                }
                cursor += 46 + nameLen + extraLen + commentLen
            }

            if (bestCandidate == null) return@withContext null

            // 获取图标数据：读取 local header + compressed data
            val fetchLen = minOf(30L + 512 + bestCandidate.compressedSize, 2_000_000L)
            val headerBytes = fetchRange(fullUrl, auth, bestCandidate.localHeaderOffset, fetchLen)
                ?: return@withContext null

            if (headerBytes.size < 30 ||
                headerBytes[0] != 0x50.toByte() ||
                headerBytes[1] != 0x4b.toByte() ||
                headerBytes[2] != 0x03.toByte() ||
                headerBytes[3] != 0x04.toByte()
            ) {
                return@withContext null
            }

            val localNameLen = readShortLE(headerBytes, 26).toInt() and 0xFFFF
            val localExtraLen = readShortLE(headerBytes, 28).toInt() and 0xFFFF
            val dataStart = 30 + localNameLen + localExtraLen
            val dataEnd = (dataStart + bestCandidate.compressedSize).toInt()

            if (dataEnd > headerBytes.size) {
                val exactData = fetchRange(fullUrl, auth, bestCandidate.localHeaderOffset + dataStart, bestCandidate.compressedSize)
                    ?: return@withContext null
                val decompressed = if (bestCandidate.method == 8) {
                    decompressDeflate(exactData)
                } else {
                    exactData
                }
                saveBitmapToFile(decompressed, targetFile)
            } else {
                val compData = headerBytes.copyOfRange(dataStart, dataEnd)
                val decompressed = if (bestCandidate.method == 8) {
                    decompressDeflate(compData)
                } else {
                    compData
                }
                saveBitmapToFile(decompressed, targetFile)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch remote WebDAV apk thumb for ${entry.name}", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
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
        val key = "acc_${account.id}|${entry.path}"
        val cached = getCachedThumbnail(context, key)
        if (cached != null) return@withContext cached

        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val fullUrl = account.connectionUrl().trimEnd('/') + p
        val auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray())

        val isJpeg = entry.name.endsWith(".jpg", ignoreCase = true) ||
                     entry.name.endsWith(".jpeg", ignoreCase = true)

        val targetFile = getCacheFile(context, key)

        // 1. 对于 JPEG 图片，尝试用 HTTP Range (0-128KB) 快速探测 EXIF 内置缩略图
        if (isJpeg) {
            try {
                val bytes = fetchRange(fullUrl, auth, 0L, 131072L)
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

    /**
     * 为 WebDAV 远程视频提取首帧缩略图：
     * 利用系统的 MediaMetadataRetriever 发起基于 HTTP Range 的轻量分片按需探测，
     * 仅拉取视频索引（moov/header）与首个关键帧，绝不全量下载几个 G 的视频整包！
     * 提取成功后保存为规整的 144x144 JPEG 缩略图并落盘缓存。
     */
    suspend fun getOrFetchWebDavVideoThumb(
        context: Context,
        account: WebDavAccount,
        entry: FileEntry
    ): File? = withContext(Dispatchers.IO) {
        val key = "webdav_video_${account.id}_${entry.path}_${entry.size}_${entry.lastModified}"
        val cached = getCachedThumbnail(context, key)
        if (cached != null) return@withContext cached

        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val fullUrl = account.connectionUrl().trimEnd('/') + p
        val auth = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("${account.username}:${account.password}".toByteArray())

        val targetFile = getCacheFile(context, key)
        val mmr = MediaMetadataRetriever()
        try {
            val headers = HashMap<String, String>()
            headers["Authorization"] = auth
            headers["User-Agent"] = "myfile/1.0 (Android; WebDAV)"
            mmr.setDataSource(fullUrl, headers)
            // 提取第一秒（1,000,000 微秒）的关键帧；若无则取第 0 帧
            val frame = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null

            val scaled = Bitmap.createScaledBitmap(frame, 144, 144, true)
            targetFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaled != frame) scaled.recycle()
            frame.recycle()
            targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract remote video thumb for ${entry.name}: ${e.message}")
            if (targetFile.exists()) targetFile.delete()
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
