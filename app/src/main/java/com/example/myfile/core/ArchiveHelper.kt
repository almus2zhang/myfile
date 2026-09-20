package com.example.myfile.core

import android.util.Log
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 统一归档/压缩包条目模型
 */
data class ArchiveEntryItem(
    val name: String,             // 显示名（例如 "beach.jpg" 或 "photos"）
    val entryPath: String,        // 归档内相对规范路径（如 "photos/beach.jpg" 或 "photos/"）
    val isDirectory: Boolean,     // 是否为目录
    val size: Long,               // 未压缩大小（-1 表示未知）
    val compressedSize: Long,     // 压缩后大小（-1 表示未知）
    val time: Long                // 最后修改时间戳 (毫秒)
)

enum class ArchiveType(val displayName: String) {
    ZIP("ZIP"),
    RAR("RAR"),
    SEVEN_Z("7-Zip"),
    TAR("TAR"),
    TAR_GZ("TAR.GZ"),
    TAR_BZ2("TAR.BZ2"),
    TAR_XZ("TAR.XZ"),
    GZ("GZIP"),
    BZ2("BZIP2"),
    XZ("XZ")
}

/**
 * 全能解压缩与归档管理器：支持 ZIP, RAR, 7Z, TAR, GZ/TGZ, BZ2, XZ 等
 */
object ArchiveHelper {
    private const val TAG = "ArchiveHelper"

    /**
     * 判断文件名是否为支持的压缩包格式
     */
    fun isArchive(fileName: String): Boolean {
        return getArchiveType(fileName) != null
    }

    /**
     * 解析压缩包类型
     */
    fun getArchiveType(fileName: String): ArchiveType? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveType.ZIP
            lower.endsWith(".rar") -> ArchiveType.RAR
            lower.endsWith(".7z") -> ArchiveType.SEVEN_Z
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveType.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> ArchiveType.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveType.TAR_XZ
            lower.endsWith(".tar") -> ArchiveType.TAR
            lower.endsWith(".gz") -> ArchiveType.GZ
            lower.endsWith(".bz2") -> ArchiveType.BZ2
            lower.endsWith(".xz") -> ArchiveType.XZ
            else -> null
        }
    }

    /**
     * 获取指定目录（例如 "" 或 "folder/"）下的直接子项
     * 自动补齐并去重隐式目录
     */
    fun listDirectory(allEntries: List<ArchiveEntryItem>, currentPath: String): List<ArchiveEntryItem> {
        val prefix = when {
            currentPath.isEmpty() -> ""
            currentPath.endsWith('/') -> currentPath
            else -> "$currentPath/"
        }

        val directDirs = mutableMapOf<String, ArchiveEntryItem>()
        val directFiles = mutableListOf<ArchiveEntryItem>()

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
                    directDirs[subDirName] = ArchiveEntryItem(
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
     * 读取压缩包内所有条目
     */
    fun parseAllEntries(file: File): List<ArchiveEntryItem> {
        val type = getArchiveType(file.name) ?: ArchiveType.ZIP
        return when (type) {
            ArchiveType.ZIP -> parseZipEntries(file)
            ArchiveType.RAR -> parseRarEntries(file)
            ArchiveType.SEVEN_Z -> parseSevenZEntries(file)
            ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> parseTarEntries(file, type)
            ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ -> parseSingleStreamEntry(file, type)
        }
    }

    /**
     * 单独提取归档中的某个文件到目标位置
     */
    suspend fun extractEntry(archiveFile: File, entryPath: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val type = getArchiveType(archiveFile.name) ?: ArchiveType.ZIP
            try {
                when (type) {
                    ArchiveType.ZIP -> ZipHelper.extractEntry(archiveFile, entryPath, destFile)
                    ArchiveType.RAR -> extractRarEntry(archiveFile, entryPath, destFile)
                    ArchiveType.SEVEN_Z -> extractSevenZEntry(archiveFile, entryPath, destFile)
                    ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ ->
                        extractTarEntry(archiveFile, type, entryPath, destFile)
                    ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ ->
                        extractSingleStream(archiveFile, type, destFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "extractEntry failed for $entryPath in ${archiveFile.name}", e)
                false
            }
        }

    /**
     * 全部解压到目标目录
     */
    suspend fun extractAll(
        archiveFile: File,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val type = getArchiveType(archiveFile.name) ?: ArchiveType.ZIP
        try {
            if (!destDir.exists()) destDir.mkdirs()
            when (type) {
                ArchiveType.ZIP -> ZipHelper.extractAll(archiveFile, destDir, onProgress)
                ArchiveType.RAR -> extractAllRar(archiveFile, destDir, onProgress)
                ArchiveType.SEVEN_Z -> extractAllSevenZ(archiveFile, destDir, onProgress)
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ ->
                    extractAllTar(archiveFile, type, destDir, onProgress)
                ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ ->
                    extractAllSingleStream(archiveFile, type, destDir, onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractAll failed for ${archiveFile.name}", e)
            Result.failure(e)
        }
    }

    // =========================================================================
    // ZIP 解析与提取
    // =========================================================================
    private fun parseZipEntries(file: File): List<ArchiveEntryItem> {
        val (zf, _) = ZipHelper.openZipFile(file)
        val list = mutableListOf<ArchiveEntryItem>()
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
                    ArchiveEntryItem(
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
            Log.e(TAG, "parseZipEntries failed", e)
        } finally {
            try { zf.close() } catch (_: Exception) {}
        }
        return list
    }

    // =========================================================================
    // RAR 解析与提取 (junrar)
    // =========================================================================
    private fun parseRarEntries(file: File): List<ArchiveEntryItem> {
        val list = mutableListOf<ArchiveEntryItem>()
        try {
            Archive(file).use { archive ->
                while (true) {
                    val header = archive.nextFileHeader() ?: break
                    val rawPath = (header.fileNameW.ifBlank { header.fileName }).replace('\\', '/')
                    val isDir = header.isDirectory || rawPath.endsWith('/')
                    val cleanPath = if (isDir && !rawPath.endsWith('/')) "$rawPath/" else rawPath
                    val name = cleanPath.trimEnd('/').substringAfterLast('/')
                    val time = header.mTime?.time ?: System.currentTimeMillis()
                    list.add(
                        ArchiveEntryItem(
                            name = name.ifEmpty { cleanPath },
                            entryPath = cleanPath,
                            isDirectory = isDir,
                            size = header.unpSize.coerceAtLeast(0L),
                            compressedSize = header.packSize.coerceAtLeast(0L),
                            time = time
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseRarEntries failed", e)
        }
        return list
    }

    private fun extractRarEntry(file: File, entryPath: String, destFile: File): Boolean {
        Archive(file).use { archive ->
            while (true) {
                val header = archive.nextFileHeader() ?: break
                val rawPath = (header.fileNameW.ifBlank { header.fileName }).replace('\\', '/')
                val cleanPath = if (header.isDirectory && !rawPath.endsWith('/')) "$rawPath/" else rawPath
                if (cleanPath == entryPath || rawPath == entryPath) {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { out ->
                        archive.extractFile(header, out)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun extractAllRar(
        file: File,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> {
        val canonicalDest = destDir.canonicalPath
        var count = 0
        Archive(file).use { archive ->
            val headers = archive.fileHeaders
            val totalFiles = headers.count { !it.isDirectory }
            for (header in headers) {
                val rawPath = (header.fileNameW.ifBlank { header.fileName }).replace('\\', '/')
                val outFile = File(destDir, rawPath)
                if (!outFile.canonicalPath.startsWith(canonicalDest)) continue // Zip Slip 防护

                if (header.isDirectory || rawPath.endsWith('/')) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        archive.extractFile(header, out)
                    }
                    count++
                    onProgress(count, totalFiles, outFile.name)
                }
            }
        }
        return Result.success(count)
    }

    // =========================================================================
    // 7-Zip 解析与提取 (Apache Commons Compress SevenZFile)
    // =========================================================================
    private fun parseSevenZEntries(file: File): List<ArchiveEntryItem> {
        val list = mutableListOf<ArchiveEntryItem>()
        try {
            SevenZFile.builder().setFile(file).get().use { szf ->
                for (entry in szf.entries) {
                    val path = entry.name.replace('\\', '/')
                    val isDir = entry.isDirectory || path.endsWith('/')
                    val cleanPath = if (isDir && !path.endsWith('/')) "$path/" else path
                    val name = cleanPath.trimEnd('/').substringAfterLast('/')
                    val time = entry.lastModifiedDate?.time ?: System.currentTimeMillis()
                    list.add(
                        ArchiveEntryItem(
                            name = name.ifEmpty { cleanPath },
                            entryPath = cleanPath,
                            isDirectory = isDir,
                            size = entry.size.coerceAtLeast(0L),
                            compressedSize = 0L,
                            time = time
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseSevenZEntries failed", e)
        }
        return list
    }

    private fun extractSevenZEntry(file: File, entryPath: String, destFile: File): Boolean {
        SevenZFile.builder().setFile(file).get().use { szf ->
            var entry = szf.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val cleanPath = if (entry.isDirectory && !path.endsWith('/')) "$path/" else path
                if (cleanPath == entryPath || path == entryPath) {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (szf.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                        }
                    }
                    return true
                }
                entry = szf.nextEntry
            }
        }
        return false
    }

    private fun extractAllSevenZ(
        file: File,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> {
        val canonicalDest = destDir.canonicalPath
        var count = 0
        SevenZFile.builder().setFile(file).get().use { szf ->
            val allEntries = szf.entries.toList()
            val totalFiles = allEntries.count { !it.isDirectory }

            var entry = szf.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val outFile = File(destDir, path)
                if (outFile.canonicalPath.startsWith(canonicalDest)) {
                    if (entry.isDirectory || path.endsWith('/')) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (szf.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                            }
                        }
                        count++
                        onProgress(count, totalFiles, outFile.name)
                    }
                }
                entry = szf.nextEntry
            }
        }
        return Result.success(count)
    }

    // =========================================================================
    // TAR / TAR.GZ / TAR.BZ2 / TAR.XZ 解析与提取
    // =========================================================================
    private fun createTarInputStream(file: File, type: ArchiveType): TarArchiveInputStream {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        val decompress: InputStream = when (type) {
            ArchiveType.TAR_GZ -> GzipCompressorInputStream(bis)
            ArchiveType.TAR_BZ2 -> BZip2CompressorInputStream(bis)
            ArchiveType.TAR_XZ -> XZCompressorInputStream(bis)
            else -> bis
        }
        return TarArchiveInputStream(decompress)
    }

    private fun parseTarEntries(file: File, type: ArchiveType): List<ArchiveEntryItem> {
        val list = mutableListOf<ArchiveEntryItem>()
        try {
            createTarInputStream(file, type).use { tis ->
                var entry: TarArchiveEntry? = tis.nextTarEntry
                while (entry != null) {
                    val path = entry.name.replace('\\', '/')
                    val isDir = entry.isDirectory || path.endsWith('/')
                    val cleanPath = if (isDir && !path.endsWith('/')) "$path/" else path
                    val name = cleanPath.trimEnd('/').substringAfterLast('/')
                    val time = entry.modTime?.time ?: System.currentTimeMillis()
                    list.add(
                        ArchiveEntryItem(
                            name = name.ifEmpty { cleanPath },
                            entryPath = cleanPath,
                            isDirectory = isDir,
                            size = entry.size.coerceAtLeast(0L),
                            compressedSize = 0L,
                            time = time
                        )
                    )
                    entry = tis.nextTarEntry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTarEntries failed", e)
        }
        return list
    }

    private fun extractTarEntry(file: File, type: ArchiveType, entryPath: String, destFile: File): Boolean {
        createTarInputStream(file, type).use { tis ->
            var entry: TarArchiveEntry? = tis.nextTarEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val cleanPath = if (entry.isDirectory && !path.endsWith('/')) "$path/" else path
                if (cleanPath == entryPath || path == entryPath) {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { out ->
                        tis.copyTo(out)
                    }
                    return true
                }
                entry = tis.nextTarEntry
            }
        }
        return false
    }

    private fun extractAllTar(
        file: File,
        type: ArchiveType,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> {
        val canonicalDest = destDir.canonicalPath
        var count = 0
        val allEntries = parseTarEntries(file, type)
        val totalFiles = allEntries.count { !it.isDirectory }

        createTarInputStream(file, type).use { tis ->
            var entry: TarArchiveEntry? = tis.nextTarEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val outFile = File(destDir, path)
                if (outFile.canonicalPath.startsWith(canonicalDest)) {
                    if (entry.isDirectory || path.endsWith('/')) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            tis.copyTo(out)
                        }
                        count++
                        onProgress(count, totalFiles, outFile.name)
                    }
                }
                entry = tis.nextTarEntry
            }
        }
        return Result.success(count)
    }

    // =========================================================================
    // 单文件压缩流 (GZ, BZ2, XZ)
    // =========================================================================
    private fun getInnerFileName(file: File, type: ArchiveType): String {
        val name = file.name
        return when (type) {
            ArchiveType.GZ -> name.removeSuffix(".gz").removeSuffix(".GZ")
            ArchiveType.BZ2 -> name.removeSuffix(".bz2").removeSuffix(".BZ2")
            ArchiveType.XZ -> name.removeSuffix(".xz").removeSuffix(".XZ")
            else -> name
        }.ifBlank { "uncompressed_file" }
    }

    private fun createCompressorStream(file: File, type: ArchiveType): InputStream {
        val bis = BufferedInputStream(FileInputStream(file))
        return when (type) {
            ArchiveType.GZ -> GzipCompressorInputStream(bis)
            ArchiveType.BZ2 -> BZip2CompressorInputStream(bis)
            ArchiveType.XZ -> XZCompressorInputStream(bis)
            else -> bis
        }
    }

    private fun parseSingleStreamEntry(file: File, type: ArchiveType): List<ArchiveEntryItem> {
        val innerName = getInnerFileName(file, type)
        return listOf(
            ArchiveEntryItem(
                name = innerName,
                entryPath = innerName,
                isDirectory = false,
                size = -1L,
                compressedSize = file.length(),
                time = file.lastModified()
            )
        )
    }

    private fun extractSingleStream(file: File, type: ArchiveType, destFile: File): Boolean {
        destFile.parentFile?.mkdirs()
        createCompressorStream(file, type).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return true
    }

    private fun extractAllSingleStream(
        file: File,
        type: ArchiveType,
        destDir: File,
        onProgress: (extractedCount: Int, totalCount: Int, currentName: String) -> Unit
    ): Result<Int> {
        val innerName = getInnerFileName(file, type)
        val outFile = File(destDir, innerName)
        outFile.parentFile?.mkdirs()
        createCompressorStream(file, type).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        onProgress(1, 1, innerName)
        return Result.success(1)
    }
}
