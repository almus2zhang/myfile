package com.example.myfile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.FileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 处理系统分享（ACTION_SEND / ACTION_SEND_MULTIPLE）
 * 将接收到的文件暂存至 cacheDir/shared_incoming/ 并写入全局剪贴板 TransferClipboard，
 * 相当于执行「复制」操作，用户可在 myfile 内部任意本地目录或 WebDAV 目录点击「粘贴」完成保存。
 */
object ShareReceiver {
    private const val TAG = "ShareReceiver"

    fun handleSendIntent(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                uris.add(uri)
            } else if (intent.data != null) {
                intent.data?.let { uris.add(it) }
            } else if (intent.clipData != null && (intent.clipData?.itemCount ?: 0) > 0) {
                intent.clipData?.getItemAt(0)?.uri?.let { uris.add(it) }
            }
        } else {
            val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (list != null) {
                uris.addAll(list)
            }
            val clipData = intent.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val u = clipData.getItemAt(i).uri
                    if (u != null && !uris.contains(u)) {
                        uris.add(u)
                    }
                }
            }
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty() && sharedText.isNullOrBlank()) {
            return
        }

        MyApp.instance.appScope.launch {
            val incomingDir = File(context.cacheDir, "shared_incoming").apply {
                if (!exists()) mkdirs()
            }
            val entries = mutableListOf<ClipboardEntry>()

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val fileName = resolveFileName(context, uri)
                        val targetFile = makeUniqueFile(incomingDir, fileName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (targetFile.exists() && targetFile.length() > 0) {
                            val entry = FileEntry(
                                name = targetFile.name,
                                path = targetFile.absolutePath,
                                isDirectory = false,
                                size = targetFile.length(),
                                lastModified = targetFile.lastModified(),
                                mimeType = FileOpener.guessMime(targetFile.name),
                                source = FileSource.LOCAL
                            )
                            entries.add(ClipboardEntry(entry = entry, account = null))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "copy shared uri failed: $uri", e)
                    }
                }

                if (entries.isEmpty() && !sharedText.isNullOrBlank()) {
                    try {
                        val txtFile = makeUniqueFile(incomingDir, "分享文本_${System.currentTimeMillis()}.txt")
                        txtFile.writeText(sharedText)
                        val entry = FileEntry(
                            name = txtFile.name,
                            path = txtFile.absolutePath,
                            isDirectory = false,
                            size = txtFile.length(),
                            lastModified = txtFile.lastModified(),
                            mimeType = "text/plain",
                            source = FileSource.LOCAL
                        )
                        entries.add(ClipboardEntry(entry = entry, account = null))
                    } catch (e: Exception) {
                        Log.e(TAG, "save shared text failed", e)
                    }
                }
            }

            if (entries.isNotEmpty()) {
                TransferClipboard.copy(entries)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "已接收 ${entries.size} 个分享文件，请选择保存位置后点击「粘贴」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            name = cursor.getString(idx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        if (name.isNullOrBlank()) {
            name = "shared_${System.currentTimeMillis()}"
        }
        return name!!.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun makeUniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val dotExt = if (ext.isNotBlank() && ext != name) ".$ext" else ""

        var target = File(dir, name)
        var count = 1
        while (target.exists()) {
            target = File(dir, "${base}_$count$dotExt")
            count++
        }
        return target
    }
}
