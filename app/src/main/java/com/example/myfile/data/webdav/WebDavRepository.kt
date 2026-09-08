package com.example.myfile.data.webdav

import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 业务逻辑封装：根据账户创建 client 并执行操作。
 * 所有网络调用强制在 IO 线程执行，避免 NetworkOnMainThreadException。
 */
class WebDavRepository(
    private val clientFactory: (WebDavAccount) -> WebDavClient
) {
    suspend fun list(account: WebDavAccount, path: String): List<FileEntry> =
        withContext(Dispatchers.IO) { clientFactory(account).list(path) }

    /**
     * 测试连接：先 OPTIONS 探测，再 PROPFIND 列目录。
     * 若原始路径失败，尝试 /dav /webdav 等候选路径，自动找能用的。
     * 返回 (ok, message)
     */
    suspend fun testConnection(account: WebDavAccount): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val client = clientFactory(account)
            val probeInfo = client.probe()
            try {
                val files = client.listRoot()
                Pair(true, "OK, 共 ${files.size} 项 ($probeInfo)")
            } catch (e: Exception) {
                // 原始路径失败，尝试自动探测候选 baseUrl
                val (ok, workingUrl, files) = client.probeAndList()
                if (ok) {
                    Pair(true, "原路径失败，但探测到正确路径: $workingUrl (共 ${files.size} 项)")
                } else {
                    Pair(false, "${e.message ?: e.javaClass.simpleName} | $probeInfo")
                }
            }
        }

    suspend fun mkdir(account: WebDavAccount, path: String): Boolean =
        withContext(Dispatchers.IO) { clientFactory(account).mkcol(path) }

    suspend fun delete(account: WebDavAccount, path: String): Boolean =
        withContext(Dispatchers.IO) { clientFactory(account).delete(path) }

    suspend fun rename(account: WebDavAccount, from: String, to: String): Boolean =
        withContext(Dispatchers.IO) { clientFactory(account).move(from, to) }

    suspend fun upload(account: WebDavAccount, path: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) { clientFactory(account).upload(path, bytes) }
}
