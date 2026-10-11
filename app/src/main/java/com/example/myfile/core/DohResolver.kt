package com.example.myfile.core

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DNS-over-HTTPS (DoH) 解析器
 *
 * 功能：
 * 优先并发请求国内高可用 DoH（阿里 223.5.5.5、腾讯 1.12.12.12）与国外备用（1.1.1.1），
 * 解析域名的 DNS TXT 记录获取真实 IP、端口和协议（http/https）。
 * 若 TXT 记录未指定协议，则默认使用 http。
 */
object DohResolver {
    private const val TAG = "DohResolver"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // 国内高可用 DoH (阿里 223.5.5.5, 腾讯 1.12.12.12 以及域名备用)
    private val DOMESTIC_DOH_TEMPLATES = listOf(
        "https://223.5.5.5/resolve?name=%s&type=TXT",
        "https://1.12.12.12/resolve?name=%s&type=TXT",
        "https://dns.alidns.com/resolve?name=%s&type=TXT",
        "https://doh.pub/resolve?name=%s&type=TXT"
    )

    // 国外备用 DoH (Cloudflare 1.1.1.1 等)
    private val BACKUP_DOH_TEMPLATES = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=TXT",
        "https://cloudflare-dns.com/dns-query?name=%s&type=TXT"
    )

    /**
     * 判断地址是否不包含 http 或 https 协议头
     */
    fun hasNoHttpScheme(rawUrl: String): Boolean {
        var s = rawUrl.trim()
        for (p in listOf("302:", "301:", "302：", "301：")) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim()
                break
            }
        }
        return !s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)
    }

    /**
     * 从可能带有 302: / 端口 / 路径 的地址中提取域名和原始路径
     */
    fun extractDomainAndPath(rawUrl: String): Pair<String, String> {
        var s = rawUrl.trim()
        for (p in listOf("302:", "301:", "302：", "301：")) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim()
                break
            }
        }
        if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
        else if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)

        val slashIdx = s.indexOf('/')
        val hostPort = if (slashIdx != -1) s.substring(0, slashIdx) else s
        val path = if (slashIdx != -1) s.substring(slashIdx) else ""

        val colonIdx = hostPort.lastIndexOf(':')
        val domain = if (colonIdx != -1 && !hostPort.startsWith("[")) {
            hostPort.substring(0, colonIdx)
        } else {
            hostPort
        }
        return Pair(domain.trim(), path.trim())
    }

    /**
     * 解析 DNS TXT 记录
     * 优先并发请求国内高可用 DoH（阿里 223.5.5.5、腾讯 1.12.12.12），若失败则使用国外备用（1.1.1.1）
     */
    suspend fun resolveTxt(domain: String): String? = withContext(Dispatchers.IO) {
        val cleanDomain = domain.trim().trimEnd('.')
        if (cleanDomain.isBlank()) return@withContext null

        Log.i(TAG, "开始 DoH 并发解析 TXT 记录, 域名: $cleanDomain")

        // 1. 优先并发请求国内高可用 DoH
        val domesticResult = queryFastest(cleanDomain, DOMESTIC_DOH_TEMPLATES, timeoutMs = 2500)
        if (!domesticResult.isNullOrBlank()) {
            Log.i(TAG, "国内 DoH 解析成功: $domesticResult (域名: $cleanDomain)")
            return@withContext domesticResult
        }

        // 2. 国内未获取到，使用国外备用 DoH
        Log.i(TAG, "国内 DoH 未获取到有效 TXT，启动国外备用 DoH (1.1.1.1)...")
        val backupResult = queryFastest(cleanDomain, BACKUP_DOH_TEMPLATES, timeoutMs = 3000)
        if (!backupResult.isNullOrBlank()) {
            Log.i(TAG, "国外备用 DoH 解析成功: $backupResult (域名: $cleanDomain)")
            return@withContext backupResult
        }

        Log.w(TAG, "所有 DoH 均未解析到 TXT 记录: $cleanDomain")
        null
    }

    /**
     * 解析给定无协议地址的 DNS TXT 记录，并格式化为标准 WebDAV URL
     * 若未指定协议则默认使用 http
     */
    suspend fun resolveTxtAndFormat(rawUrl: String): String? {
        val (domain, originalPath) = extractDomainAndPath(rawUrl)
        if (domain.isBlank()) return null

        val txtContent = resolveTxt(domain) ?: return null
        return formatResolvedEndpoint(txtContent, originalPath)
    }

    /**
     * 同步阻塞版本（用于旧接口或同步上下文）
     */
    fun resolveTxtAndFormatBlocking(rawUrl: String): String? {
        return try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                resolveTxtAndFormat(rawUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveTxtAndFormatBlocking 异常: ${e.message}")
            null
        }
    }

    /**
     * 将 TXT 记录内容规范化为连接 URL
     * 如果获取到的没有指定协议则默认用 http；保留原始路径（若有）
     */
    fun formatResolvedEndpoint(txt: String, originalPath: String = ""): String {
        var s = txt.trim()
        // 去除外层引号
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length >= 2) s = s.substring(1, s.length - 1).trim()
        }
        s = s.replace("\\\"", "\"").trim()

        // 处理类似 "url=http://..." 格式
        if (s.startsWith("url=", ignoreCase = true)) {
            s = s.substring(4).trim()
        }

        // 如果没有指定协议，则默认使用 http
        var formatted = if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            "http://$s"
        } else {
            s
        }

        // 若原始输入包含路径（如 /dav），且解析出的地址未包含非根路径，则拼接原始路径
        if (originalPath.isNotBlank() && originalPath != "/") {
            try {
                val uri = URI(formatted)
                if (uri.path.isNullOrEmpty() || uri.path == "/") {
                    formatted = formatted.trimEnd('/') + if (originalPath.startsWith("/")) originalPath else "/$originalPath"
                }
            } catch (_: Exception) {
                if (!formatted.contains("/")) {
                    formatted = formatted.trimEnd('/') + if (originalPath.startsWith("/")) originalPath else "/$originalPath"
                }
            }
        }

        return formatted
    }

    private suspend fun queryFastest(
        domain: String,
        templates: List<String>,
        timeoutMs: Long
    ): String? = coroutineScope {
        val deferred = CompletableDeferred<String?>()
        val remaining = AtomicInteger(templates.size)

        val jobs = templates.map { template ->
            val queryUrl = String.format(template, domain)
            launch(Dispatchers.IO) {
                try {
                    val result = querySingleEndpoint(queryUrl)
                    if (!result.isNullOrBlank()) {
                        deferred.complete(result)
                        return@launch
                    }
                } catch (_: Exception) {
                    // 记录或忽略单个错误
                }
                if (remaining.decrementAndGet() == 0) {
                    deferred.complete(null)
                }
            }
        }

        val fastResult = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        jobs.forEach { it.cancel() }
        fastResult
    }

    private fun querySingleEndpoint(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("User-Agent", "myfile-doh/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseDohJsonTxt(body)
        }
    }

    private fun parseDohJsonTxt(jsonStr: String): String? {
        try {
            val json = JSONObject(jsonStr)
            val status = json.optInt("Status", -1)
            if (status != 0) return null

            val answer = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answer.length()) {
                val item = answer.optJSONObject(i) ?: continue
                val type = item.optInt("type", 0)
                if (type == 16) { // 16 为 TXT 记录类型
                    val rawData = item.optString("data", "").trim()
                    if (rawData.isNotBlank()) {
                        var cleaned = rawData.trim()
                        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
                            cleaned = cleaned.substring(1, cleaned.length - 1)
                        }
                        cleaned = cleaned.replace("\\\"", "\"").trim()
                        if (cleaned.isNotBlank()) {
                            return cleaned
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 DoH JSON 异常: ${e.message}")
        }
        return null
    }
}
