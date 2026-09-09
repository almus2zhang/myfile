package com.example.myfile.core

import android.content.Context
import android.util.Log
import com.example.myfile.MyApp
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TransferOps {
    private const val TAG = "TransferOps"

    /**
     * 将剪贴板中的条目粘贴到目标本地目录
     * 支持：
     * 1. 本地 -> 本地（文件直接拷贝，文件夹递归拷贝）
     * 2. WebDAV -> 本地（启动下载管理器并发分片下载）
     */
    suspend fun pasteToLocal(
        context: Context,
        targetDir: File,
        items: List<ClipboardEntry>,
        isCut: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        if (!targetDir.exists()) targetDir.mkdirs()

        for (item in items) {
            try {
                if (item.account == null) {
                    // 本地 -> 本地
                    val src = File(item.entry.path)
                    if (src.exists()) {
                        val dst = File(targetDir, item.entry.name)
                        if (src.canonicalPath != dst.canonicalPath) {
                            if (isCut) {
                                val moved = src.renameTo(dst)
                                if (!moved) {
                                    if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
                                    else src.copyTo(dst, overwrite = true)
                                    src.deleteRecursively()
                                }
                            } else {
                                if (src.isDirectory) {
                                    src.copyRecursively(dst, overwrite = true)
                                } else {
                                    src.copyTo(dst, overwrite = true)
                                }
                            }
                            successCount++
                        }
                    }
                } else {
                    // WebDAV -> 本地
                    val mgr = MyApp.instance.downloadManager
                    com.example.myfile.core.download.DownloadService.start(context)
                    if (!item.entry.isDirectory) {
                        mgr.startDownload(item.account, item.entry.path, item.entry.name, targetDir, knownSize = item.entry.size)
                        successCount++
                    } else {
                        // 目录下载：递归遍历该目录下载所有文件
                        downloadWebDavDirectory(context, item.account, item.entry.path, File(targetDir, item.entry.name))
                        successCount++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pasteToLocal failed for ${item.entry.name}", e)
            }
        }
        successCount
    }

    private suspend fun downloadWebDavDirectory(
        context: Context,
        account: WebDavAccount,
        remoteDirPath: String,
        localTargetDir: File
    ) {
        if (!localTargetDir.exists()) localTargetDir.mkdirs()
        val repo = MyApp.instance.webDavRepository
        val mgr = MyApp.instance.downloadManager
        val children = repo.list(account, remoteDirPath)
        for (child in children) {
            if (child.isDirectory) {
                downloadWebDavDirectory(context, account, child.path, File(localTargetDir, child.name))
            } else {
                mgr.startDownload(account, child.path, child.name, localTargetDir, knownSize = child.size)
            }
        }
    }

    /**
     * 将剪贴板中的条目粘贴到目标 WebDAV 路径
     * 支持：
     * 1. 同 WebDAV 账号 -> WebDAV 目标目录（服务端原生 COPY/MOVE 指令，零流量秒级完成）
     * 2. 本地 -> WebDAV 目标目录（OkHttp PUT 上传，支持文件夹递归创建与上传）
     * 3. 跨 WebDAV 账号 -> WebDAV
     */
    suspend fun pasteToWebDav(
        context: Context,
        targetAccount: WebDavAccount,
        targetDirPath: String,
        items: List<ClipboardEntry>,
        isCut: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        val repo = MyApp.instance.webDavRepository
        val baseDir = targetDirPath.trimEnd('/')

        for (item in items) {
            try {
                val destPath = if (baseDir.isEmpty() || baseDir == "/") "/${item.entry.name}" else "$baseDir/${item.entry.name}"
                val srcPath = if (item.entry.path.startsWith("/")) item.entry.path else "/${item.entry.path}"
                // 同账号相同路径无需移动/复制
                if (item.account != null && item.account.id == targetAccount.id && srcPath == destPath) {
                    continue
                }
                if (item.account != null && item.account.id == targetAccount.id) {
                    // 同 WebDAV 账号间：
                    if (isCut) {
                        // 服务端原生 MOVE 指令，保留修改时间
                        val ok = repo.rename(targetAccount, item.entry.path, destPath, mtime = item.entry.lastModified)
                        if (ok) successCount++
                    } else {
                        // 服务端原生 COPY 指令
                        val ok = repo.copy(targetAccount, item.entry.path, destPath)
                        if (ok) successCount++
                    }
                } else if (item.account == null) {
                    // 本地 -> WebDAV 上传
                    val src = File(item.entry.path)
                    if (src.exists()) {
                        val ok = if (src.isDirectory) {
                            uploadLocalDirectory(repo, targetAccount, src, destPath)
                            true
                        } else {
                            repo.uploadFile(targetAccount, destPath, src)
                        }
                        if (ok) {
                            if (isCut) {
                                src.deleteRecursively()
                            }
                            successCount++
                        }
                    }
                } else {
                    // 跨 WebDAV 账号：先下载临时缓存再上传
                    val tmp = File(context.cacheDir, "transfer_${System.currentTimeMillis()}_${item.entry.name}")
                    try {
                        val auth = "Basic " + java.util.Base64.getEncoder()
                            .encodeToString("${item.account.username}:${item.account.password}".toByteArray())
                        val fullUrl = item.account.connectionUrl().trimEnd('/') + srcPath
                        val downloaded = FileOpener.downloadToCache(MyApp.instance.okHttpClient, auth, fullUrl, tmp.name)
                        if (downloaded != null && downloaded.exists()) {
                            val ok = repo.uploadFile(targetAccount, destPath, downloaded)
                            if (ok) {
                                successCount++
                                if (isCut) {
                                    try { repo.delete(item.account, item.entry.path) } catch (_: Exception) {}
                                }
                            }
                            downloaded.delete()
                        }
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pasteToWebDav failed for ${item.entry.name}", e)
            }
        }
        successCount
    }

    private suspend fun uploadLocalDirectory(
        repo: com.example.myfile.data.webdav.WebDavRepository,
        account: WebDavAccount,
        localDir: File,
        remoteDirPath: String
    ) {
        repo.mkdir(account, remoteDirPath)
        val files = localDir.listFiles() ?: return
        for (f in files) {
            val childDest = "$remoteDirPath/${f.name}"
            if (f.isDirectory) {
                uploadLocalDirectory(repo, account, f, childDest)
            } else {
                repo.uploadFile(account, childDest, f)
            }
        }
    }
}
