package com.example.myfile.data.webdav

import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        /** 动态账户主连接宽限期：若 800ms 内主连接未返回或直接报错，立即并发侦测新地址 */
        private const val DYNAMIC_PROBE_GRACE_MS = 800L
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
        if (!account.isDynamic) {
            val client = clientFactory(account)
            return block(client)
        }

        if (account.resolvedUrl.isBlank()) {
            val freshUrl = reResolveAndSave(account)
            if (freshUrl.isNotBlank()) {
                val newClient = clientFactory(account.copy(resolvedUrl = freshUrl))
                return block(newClient)
            }
            throw java.io.IOException("无法解析动态地址: ${account.url}")
        }

        return coroutineScope {
            val oldClient = clientFactory(account)
            val resultDeferred = CompletableDeferred<T>()
            val primaryFailed = CompletableDeferred<Exception>()

            val primaryJob = launch(Dispatchers.IO) {
                try {
                    val res = block(oldClient)
                    resultDeferred.complete(res)
                } catch (e: Exception) {
                    primaryFailed.complete(e)
                }
            }

            val watchdogJob = launch(Dispatchers.IO) {
                // 等待主连接报错，或等待 800ms 宽限期。若主连接在 800ms 内未成功或直接报错，说明连接受阻/超时，立即并发侦测新地址
                val failure = withTimeoutOrNull(DYNAMIC_PROBE_GRACE_MS) {
                    primaryFailed.await()
                }
                if (resultDeferred.isCompleted) return@launch

                android.util.Log.i(
                    TAG,
                    "动态账户 [${account.name}] 主连接未就绪或异常${if (failure != null) " (${failure.message})" else " (耗时超 ${DYNAMIC_PROBE_GRACE_MS}ms)"}，正在同步侦测新地址: ${account.url}"
                )
                try {
                    val freshUrl = reResolveAndSave(account)
                    if (freshUrl.isNotBlank()) {
                        if (freshUrl != account.resolvedUrl || primaryFailed.isCompleted) {
                            android.util.Log.i(TAG, "动态账户侦测到有效端点，立即切换并重试: $freshUrl (原地址: ${account.resolvedUrl})")
                            oldClient.cancelAll()
                            primaryJob.cancel()
                            val newClient = clientFactory(account.copy(resolvedUrl = freshUrl))
                            val res = block(newClient)
                            resultDeferred.complete(res)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "同步侦测新地址或重试失败: ${e.message}")
                    if (!resultDeferred.isCompleted && primaryFailed.isCompleted) {
                        resultDeferred.completeExceptionally(primaryFailed.await())
                        return@launch
                    }
                }

                // 若侦测的地址与原地址相同，且主连接仍在等待，则等待主连接结束或报错
                try {
                    val primaryErr = primaryFailed.await()
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.completeExceptionally(primaryErr)
                    }
                } catch (e: Exception) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.completeExceptionally(e)
                    }
                }
            }

            try {
                resultDeferred.await()
            } finally {
                primaryJob.cancel()
                watchdogJob.cancel()
                oldClient.cancelAll()
            }
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

    suspend fun download(account: WebDavAccount, path: String): okhttp3.Response =
        withContext(Dispatchers.IO) { withDynamicRetry(account) { it.download(path) } }
}
