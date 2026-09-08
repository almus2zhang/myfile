package com.example.myfile.data.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.myfile.model.FileEntry
import com.example.myfile.model.FileSource
import java.io.File

/**
 * 本地文件操作封装。优先使用 java.io.File（对外部存储目录），
 * 兼容 Android 11+ 分区存储时回退到 SAF DocumentFile。
 */
class LocalFileRepository(private val context: Context) {

    /** 列出指定目录的子项 */
    fun list(dir: File): List<FileEntry> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { f ->
                FileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified(),
                    source = FileSource.LOCAL
                )
            } ?: emptyList()
    }

    fun listByUri(treeUri: Uri): List<FileEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return root.listFiles().map { df ->
            FileEntry(
                name = df.name ?: "",
                path = df.uri.toString(),
                isDirectory = df.isDirectory,
                size = if (df.isFile) df.length() else 0L,
                lastModified = df.lastModified(),
                source = FileSource.LOCAL
            )
        }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun mkdir(parent: File, name: String): Boolean =
        File(parent, name).mkdirs() || File(parent, name).isDirectory

    fun delete(file: File): Boolean = file.deleteRecursively()

    fun rename(file: File, newName: String): Boolean {
        val target = File(file.parentFile, newName)
        return file.renameTo(target)
    }

    fun copy(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copy(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun move(src: File, dest: File): Boolean {
        copy(src, dest)
        return if (dest.exists()) { src.deleteRecursively(); true } else false
    }
}
