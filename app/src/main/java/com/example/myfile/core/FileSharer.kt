package com.example.myfile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件分享：把本地文件通过系统分享面板分享给其他应用。
 * 支持单个文件（ACTION_SEND）和多个文件（ACTION_SEND_MULTIPLE）。
 */
object FileSharer {

    private const val TAG = "FileSharer"

    /** 分享单个本地文件 */
    fun shareFile(context: Context, file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "shareFile: file not exists: ${file.absolutePath}")
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = guessMime(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享 ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "shareFile failed", e)
            false
        }
    }

    /** 分享多个本地文件 */
    fun shareFiles(context: Context, files: List<File>): Boolean {
        val valid = files.filter { it.exists() && it.isFile }
        if (valid.isEmpty()) {
            Log.w(TAG, "shareFiles: no valid files")
            return false
        }
        return try {
            val uris = ArrayList<Uri>()
            for (f in valid) {
                uris.add(
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        f
                    )
                )
            }
            val intent = if (valid.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = guessMime(valid[0].name)
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    // 多文件时用统配类型，避免类型不一致导致目标应用不匹配
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val title = if (valid.size == 1) "分享 ${valid[0].name}" else "分享 ${valid.size} 个文件"
            context.startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            })
            true
        } catch (e: Exception) {
            Log.e(TAG, "shareFiles failed", e)
            false
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
}
