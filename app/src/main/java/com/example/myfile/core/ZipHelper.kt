package com.example.myfile.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 智能副本命名助手：生成 "abc copy.a"、"abc copy 2.a" 等
 */
object DuplicateNameHelper {
    /**
     * 根据已有名称集合生成副本名称
     */
    fun generate(originalName: String, existingNames: Set<String>): String {
        val lowerSet = existingNames.map { it.lowercase() }.toSet()
        val dotIndex = originalName.lastIndexOf('.')
        val (baseName, ext) = if (dotIndex > 0) {
            originalName.substring(0, dotIndex) to originalName.substring(dotIndex)
        } else {
            originalName to ""
        }

        // 匹配 "abc copy" 或 "abc copy 2" 模式，避免重复叠加成 "abc copy copy.a"
        val copyRegex = Regex("""^(.*?)(?: copy(?: (\d+))?)?$""", RegexOption.IGNORE_CASE)
        val match = copyRegex.matchEntire(baseName)
        val rootBase = match?.groupValues?.get(1)?.ifBlank { baseName } ?: baseName

        var candidate = "$rootBase copy$ext"
        if (candidate.lowercase() !in lowerSet) {
            return candidate
        }

        var counter = 2
        while (true) {
            val nextCandidate = "$rootBase copy $counter$ext"
            if (nextCandidate.lowercase() !in lowerSet) {
                return nextCandidate
            }
            counter++
        }
    }
}

/**
 * ZIP 内部条目结构
 */
data class ZipEntryItem(
    val name: String,             // 显示名，例如 "beach.jpg" 或 "photos"
    val entryPath: String,        // 完整包内相对路径，例如 "photos/beach.jpg" 或 "photos/"
    val isDirectory: Boolean,     // 是否为目录
    val size: Long,               // 未压缩大小
    val compressedSize: Long,     // 压缩后大小
    val time: Long                // 最后修改时间戳 (毫秒)
)

/**
 * ZIP 核心解析与解压辅助类
 */
object ZipHelper {
    private const val TAG = "ZipHelper"

    /**
     * 自动兼容 UTF-8 和 GBK 编码打开 ZipFile
     */
    fun openZipFile(file: File): Pair<ZipFile, Charset> {
        val charsets = listOf(
            Charsets.UTF_8,
            Charset.forName("GBK"),
            Charset.defaultCharset()
        )
        for (cs in charsets) {
            try {
                val zf = ZipFile(file, cs)
                val entries = zf.entries()
                if (entries.hasMoreElements()) {
                    // 读取第一个条目名称测试是否乱码/抛异常
                    entries.nextElement().name
                }
                return Pair(zf, cs)
            } catch (e: Exception) {
                Log.d(TAG, "Failed to open zip with charset ${cs.name()}: ${e.message}")
            }
        }
        return Pair(ZipFile(file), Charsets.UTF_8)
    }

    /**
     * 读取 ZIP 内所有文件与目录，解析为条目列表
     */
    fun parseAllEntries(file: File): List<ZipEntryItem> {
        val (zf, _) = openZipFile(file)
        val list = mutableListOf<ZipEntryItem>()
        try {
            val enumEntries = zf.entries()
            while (enumEntries.hasMoreElements()) {
                val entry = enumEntries.nextElement()
                val path = entry.name.replace('\\', '/')
                val isDir = entry.isDirectory || path.endsWith('/')
                val cleanPath = if (isDir && !path.endsWith('/')) "$path/" else path
                val name = cleanPath.trimEnd('/').substringAfterLast('/')
                val time = entry.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                list.add(
                    ZipEntryItem(
                        name = name.ifEmpty { cleanPath },
                        entryPath = cleanPath,
                        isDirectory = isDir,
                        size = entry.size.coerceAtLeast(0L),
                        compressedSize = entry.compressedSize.coerceAtLeast(0L),
                        time = time
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseAllEntries failed", e)
        } finally {
            try { zf.close() } catch (_: Exception) {}
        }
        return list
    }

    /**
     * 获取指定目录（例如 "" 或 "folder/"）下的直接子项
     * 包含对隐式目录的自动补齐与去重
     */
    fun listDirectory(allEntries: List<ZipEntryItem>, currentPath: String): List<ZipEntryItem> {
        val prefix = when {
            currentPath.isEmpty() -> ""
            currentPath.endsWith('/') -> currentPath
            else -> "$currentPath/"
        }

        val directDirs = mutableMapOf<String, ZipEntryItem>()
        val directFiles = mutableListOf<ZipEntryItem>()

        for (item in allEntries) {
            val p = item.entryPath
            if (p == prefix) continue
            if (!p.startsWith(prefix)) continue

            val relative = p.substring(prefix.length)
            if (relative.isEmpty()) continue

            val slashIdx = relative.indexOf('/')
            if (slashIdx >= 0) {
                val subDirName = relative.substring(0, slashIdx)
                val subDirPath = "$prefix$subDirName/"
                if (!directDirs.containsKey(subDirName)) {
                    directDirs[subDirName] = ZipEntryItem(
                        name = subDirName,
                        entryPath = subDirPath,
                        isDirectory = true,
                        size = 0L,
                        compressedSize = 0L,
                        time = item.time
                    )
                }
            } else {
                directFiles.add(item.copy(name = relative))
            }
        }

        val sortedDirs = directDirs.values.sortedBy { it.name.lowercase() }
        val sortedFiles = directFiles.sortedBy { it.name.lowercase() }
        return sortedDirs + sortedFiles
    }

    /**
     * 单独提取 ZIP 中的某个文件到目标文件
     */
    suspend fun extractEntry(zipFile: File, entryPath: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val (zf, _) = openZipFile(zipFile)
                zf.use { zip ->
                    val entry = zip.getEntry(entryPath)
                        ?: zip.entries().asSequence().find { it.name.replace('\\', '/') == entryPath }
                        ?: return@withContext false

                    destFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "extractEntry failed: $entryPath", e)
                false
            }
        }

    /**
     * 全部解压到指定目录
     */
    suspend fun extractAll(
        zipFile: File,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val canonicalDestDirPath = destDir.canonicalPath
            val (zf, _) = openZipFile(zipFile)
            var extractedCount = 0
            zf.use { zip ->
                val allList = zip.entries().asSequence().toList()
                val fileEntries = allList.filter { !it.isDirectory && !it.name.replace('\\', '/').endsWith('/') }
                val totalCount = fileEntries.size

                for (entry in allList) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val outFile = File(destDir, normalizedName)
                    // 防范 Zip Slip 路径穿越
                    if (!outFile.canonicalPath.startsWith(canonicalDestDirPath)) {
                        Log.w(TAG, "Skipping malicious entry: ${entry.name}")
                        continue
                    }

                    if (entry.isDirectory || normalizedName.endsWith('/')) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        extractedCount++
                        val displayName = normalizedName.substringAfterLast('/')
                        onProgress(extractedCount, totalCount, displayName)
                    }
                }
            }
            Result.success(extractedCount)
        } catch (e: Exception) {
            Log.e(TAG, "extractAll failed", e)
            Result.failure(e)
        }
    }
}
