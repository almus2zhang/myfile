package com.example.myfile.data.webdav

import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebDavTestResult(
    val ok: Boolean,
    val message: String,
    val resolvedUrl: String = ""
)

/**
 * WebDAV 业务逻辑封装：根据账户创建 client 并执行操作。
 * 所有网络调用强制在 IO 线程执行，避免 NetworkOnMainThreadException。
 */
class WebDavRepository(
    private val clientFactory: (WebDavAccount) -> WebDavClient,
    private val accountStore: AccountStore? = null
) {
    companion object {
        private const val TAG = "WebDavRepo"
    }

    /**
     * 针对动态解析/重定向账户：重新从原始 URL 解析最新有效连接地址并更新持久化存储
     */
    suspend fun reResolveAndSave(account: WebDavAccount): String = withContext(Dispatchers.IO) {
        try {
            android.util.Log.i(TAG, "正在从原始网址重新获取动态地址: ${account.url}")
            var resolved = WebDavClient.resolveEndpoint(account.url)
            val testAcc = account.copy(resolvedUrl = resolved)
            val client = clientFactory(testAcc)
            val (ok, workingUrl, _) = client.probeAndList()
            if (ok && workingUrl.isNotBlank()) {
                resolved = workingUrl
            }
            if (resolved.isNotBlank()) {
                android.util.Log.i(TAG, "动态地址重新获取成功: $resolved")
                accountStore?.updateResolvedUrl(account.id, resolved)
                return@withContext resolved
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "重新解析动态地址失败: ${e.message}", e)
        }
        return@withContext ""
    }

    private suspend fun <T> withDynamicRetry(account: WebDavAccount, block: suspend (WebDavClient) -> T): T {
        val client = clientFactory(account)
        try {
            return block(client)
        } catch (e: Exception) {
            if (account.isDynamic) {
                android.util.Log.w(TAG, "动态账户 [${account.name}] 访问失败 (${e.message})，正在重新获取连接地址并重试...")
                val freshUrl = reResolveAndSave(account)
                if (freshUrl.isNotBlank()) {
                    val newClient = clientFactory(account.copy(resolvedUrl = freshUrl))
                    return block(newClient)
                }
            }
            throw e
        }
    }

    suspend fun list(account: WebDavAccount, path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            withDynamicRetry(account) { it.list(path) }
        }

    /**
     * 测试连接：先 OPTIONS 探测，再 PROPFIND 列目录。
     * 若原始路径失败，尝试 /dav /webdav 等候选路径，自动找能用的。
     * 支持跟踪重定向和解析纯文本 ip:port。
     * 返回 WebDavTestResult (ok, message, resolvedUrl)
     */
    suspend fun testConnection(account: WebDavAccount): WebDavTestResult =
        withContext(Dispatchers.IO) {
            // 如果是动态账户，先从原始地址重新解析真实端点
            val resolvedUrl = if (account.isDynamic || account.resolvedUrl.isBlank()) {
                val fresh = WebDavClient.resolveEndpoint(account.url)
                if (fresh.isNotBlank()) fresh else account.connectionUrl()
            } else {
                account.connectionUrl()
            }
            val testAcc = account.copy(resolvedUrl = resolvedUrl)
            val client = clientFactory(testAcc)
            val effectiveUrl = client.getEffectiveBaseUrl()
            val probeInfo = client.probe()
            try {
                val files = client.listRoot()
                val note = if (effectiveUrl.trimEnd('/') != account.url.trimEnd('/')) " [解析端点: $effectiveUrl]" else ""
                WebDavTestResult(true, "OK, 共 ${files.size} 项$note ($probeInfo)", effectiveUrl)
            } catch (e: Exception) {
                // 原始路径失败，尝试自动探测候选 baseUrl
                val (ok, workingUrl, files) = client.probeAndList()
                if (ok) {
                    WebDavTestResult(true, "原路径失败，但探测到正确路径: $workingUrl (共 ${files.size} 项)", workingUrl)
                } else {
                    WebDavTestResult(false, "${e.message ?: e.javaClass.simpleName} | $probeInfo", effectiveUrl)
                }
            }
        }

    suspend fun mkdir(account: WebDavAccount, path: String): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.mkcol(path) } }

    suspend fun delete(account: WebDavAccount, path: String): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.delete(path) } }

    suspend fun rename(account: WebDavAccount, from: String, to: String, mtime: Long? = null): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.move(from, to, mtime) } }

    suspend fun upload(account: WebDavAccount, path: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.upload(path, bytes) } }

    suspend fun copy(account: WebDavAccount, from: String, to: String): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.copy(from, to) } }

    suspend fun uploadFile(account: WebDavAccount, path: String, file: java.io.File): Boolean =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.uploadFile(path, file) } }
}
