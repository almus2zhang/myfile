package com.example.myfile.data.webdav

import com.example.myfile.model.FileEntry
import com.example.myfile.model.FileSource
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Base64

/**
 * WebDAV 协议客户端：自实现 PROPFIND/GET/PUT/MKCOL/DELETE/MOVE/COPY
 * 以便完全控制连接复用与并发
 */
class WebDavClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    @Volatile
    private var effectiveBaseUrl: String? = null

    fun getEffectiveBaseUrl(): String {
        effectiveBaseUrl?.let { return it }
        synchronized(this) {
            if (effectiveBaseUrl == null) {
                effectiveBaseUrl = resolveEndpoint(baseUrl)
            }
            return effectiveBaseUrl!!
        }
    }

    private val authHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    /** 获取认证头，供下载引擎复用 */
    fun authHeader(): String = authHeader

    /** 暴露 baseUrl（用于端口轮换日志） */
    fun baseUrlString(): String = getEffectiveBaseUrl()

    /** 规范化路径，确保以 / 开头且拼接 baseUrl。
     *  使用 OkHttp 的 HttpUrl API 做规范化（自动编码中文字符、避免拼接错误）
     */
    private fun fullUrl(path: String): String {
        val base = getEffectiveBaseUrl().trim().trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        // 用 HttpUrl 解析 + 重组，自动 URL 编码
        return try {
            val httpUrl = base.toHttpUrlOrNull()
            if (httpUrl != null) {
                val basePath = httpUrl.encodedPath.trimEnd('/')
                httpUrl.newBuilder()
                    .encodedPath(basePath + p)
                    .build()
                    .toString()
            } else {
                val baseUri = java.net.URI(base)
                okhttp3.HttpUrl.Builder()
                    .scheme(if (base.startsWith("https")) "https" else "http")
                    .host(baseUri.host ?: return base + p)
                    .apply {
                        val port = baseUri.port
                        if (port > 0) port(port)
                    }
                    .encodedPath((baseUri.path ?: "/").trimEnd('/') + p)
                    .build()
                    .toString()
            }
        } catch (e: Exception) {
            Log.w("WebDavClient", "fullUrl error, fallback string concat: ${e.message}")
            base + p
        }
    }

    /** 基础 URL，规范化（确保有尾斜杠，方便拼接子路径） */
    val rootUrl: String get() = fullUrl("/")

    private fun requestBuilder(method: String, path: String): Request.Builder =
        Request.Builder()
            .url(fullUrl(path))
            .header("Authorization", authHeader)
            // 与 CX 文件浏览器完全一致：用 Android 系统默认的 Dalvik UA（非浏览器 UA），
            // 避免被运营商 DPI 识别为浏览器下载/下载工具而限速
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 15; V2309A Build/AP3A.240905.015.A1)")
            .header("Accept-Encoding", "gzip")
            .header("Connection", "Keep-Alive")
            .method(method, null)

    /** 探测服务器 WebDAV 能力（OPTIONS），不抛异常 */
    fun probe(): String = try {
        val req = requestBuilder("OPTIONS", "/").build()
        client.newCall(req).execute().use { resp ->
            val dav = resp.header("DAV") ?: ""
            val allow = resp.header("Allow") ?: ""
            val server = resp.header("Server") ?: ""
            Log.d("WebDavClient", "OPTIONS ${resp.code} Server=$server DAV=$dav Allow=$allow")
            "code=${resp.code} Server=$server DAV=$dav Allow=$allow"
        }
    } catch (e: Exception) { "probe-error: ${e.message}" }

    /** PROPFIND Depth:1 列目录 */
    fun list(path: String): List<FileEntry> {
        // 使用与 rclone 完全一致的带命名空间前缀格式，确保兼容
        // Apache mod_dav / sharelist / Alist 等对无前缀 body 可能解析失败返回 404
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getlastmodified/>
                <d:getcontentlength/>
                <d:getcontenttype/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>""".trimIndent()

        var currentUrl = fullUrl(path)
        repeat(4) { _ ->
            val req = Request.Builder()
                .url(currentUrl)
                .header("Authorization", authHeader)
                .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
                .header("Accept", "*/*")
                .header("Accept-Charset", "utf-8")
                .header("Accept-Encoding", "identity")
                .header("MS-Author-Via", "DAV")
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .build()
            Log.d("WebDavClient", "PROPFIND $currentUrl")
            val (code, respBody, location) = executeWithCapture(req)
            Log.d("WebDavClient", "PROPFIND response code=$code for $currentUrl")
            if (code in 301..302 || code == 307 || code == 308) {
                val loc = location ?: throw RuntimeException("PROPFIND 重定向但无 Location header: $code")
                Log.d("WebDavClient", "PROPFIND redirect $currentUrl -> $loc")
                currentUrl = if (loc.startsWith("http")) loc else {
                    val base = (currentUrl).toHttpUrlOrNull() ?: throw RuntimeException("bad url: $currentUrl")
                    base.newBuilder().encodedPath(loc).build().toString()
                }
                return@repeat
            }
            if (code != 207) {
                val hint = respBody.take(200).replace("\n", " ").replace("\\s+".toRegex(), " ")
                throw RuntimeException("PROPFIND $code on $currentUrl${if (hint.isNotBlank()) " body=$hint" else ""}")
            }
            return parsePropfind(respBody, path)
        }
        throw RuntimeException("PROPFIND 重定向次数超限，最后 URL: $currentUrl")
    }

    /** 执行请求并捕获状态码、body、Location 头 */
    private fun executeWithCapture(req: Request): Triple<Int, String, String?> =
        client.newCall(req).execute().use { resp ->
            Triple(resp.code, resp.body?.string() ?: "", resp.header("Location"))
        }

    /**
     * 尝试不同 baseUrl 找到第一个 PROPFIND 成功的。
     * 用于保存账户前的智能探测（路径猜测）。
     * 返回 (ok, actualBaseUrl, files)
     */
    fun probeAndList(): Triple<Boolean, String, List<FileEntry>> {
        val base = getEffectiveBaseUrl().trimEnd('/')
        // 候选 baseUrl：原始 / 去尾斜杠 / 加 /dav / /webdav / /files
        val candidates = mutableListOf<String>()
        candidates.add(base)
        listOf("/dav", "/webdav", "/files", "/remote.php/webdav", "/public.php/webdav").forEach { p ->
            if (!base.endsWith(p)) candidates.add(base + p)
        }
        for (c in candidates) {
            try {
                val builder = Request.Builder()
                    .url("$c/")
                    .header("Authorization", authHeader)
                    .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
                    .header("Accept", "*/*")
                    .header("Accept-Charset", "utf-8")
                    .header("Accept-Encoding", "identity")
                    .header("MS-Author-Via", "DAV")
                    .header("Depth", "1")
                    .header("Content-Type", "application/xml; charset=utf-8")
                val req = builder.method("PROPFIND", """<?xml version="1.0"?>
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:displayname/><d:getcontentlength/><d:getcontenttype/>
                        <d:getlastmodified/><d:resourcetype/>
                      </d:prop>
                    </d:propfind>""".trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
                Log.d("WebDavClient", "probeAndList trying: $c/")
                client.newCall(req).execute().use { resp ->
                    Log.d("WebDavClient", "probeAndList $c/ -> ${resp.code}")
                    if (resp.code == 207) {
                        val files = parsePropfind(resp.body?.string() ?: "", "/")
                        return Triple(true, c, files)
                    }
                }
            } catch (e: Exception) { Log.w("WebDavClient", "probeAndList $c failed: ${e.message}") }
        }
        return Triple(false, baseUrl, emptyList())
    }

    /** 对 baseUrl 本身做 PROPFIND Depth:1，列出根目录内容 */
    fun listRoot(): List<FileEntry> = list("/")

    /** 获取文件大小：优先用 HEAD 读 Content-Length，回退 PROPFIND Depth:0 */
    fun getSize(path: String): Long {
        // 方法1：HEAD 读 Content-Length（最简单可靠）
        try {
            val headReq = requestBuilder("HEAD", path).build()
            client.newCall(headReq).execute().use { resp ->
                val len = resp.header("Content-Length")?.toLongOrNull()
                if (len != null && len > 0) {
                    Log.d("WebDavClient", "getSize via HEAD: $len")
                    return len
                }
                val range = resp.header("Content-Range")
                if (range != null) {
                    // Content-Range: bytes 0-1023/2048 -> 总长 2048
                    val total = range.substringAfterLast('/').toLongOrNull()
                    if (total != null && total > 0) {
                        Log.d("WebDavClient", "getSize via Content-Range: $total")
                        return total
                    }
                }
            }
        } catch (e: Exception) { Log.w("WebDavClient", "HEAD getSize failed: ${e.message}") }

        // 方法2：PROPFIND Depth:0，解析 getcontentlength
        try {
            val body = """<?xml version="1.0"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop><d:getcontentlength/></d:prop>
                </d:propfind>""".trimIndent()
            val req = requestBuilder("PROPFIND", path)
                .header("Depth", "0")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val size = extractContentLength(text)
                if (size > 0) {
                    Log.d("WebDavClient", "getSize via PROPFIND: $size")
                    return size
                }
            }
        } catch (e: Exception) { Log.w("WebDavClient", "PROPFIND getSize failed: ${e.message}") }

        return -1L
    }

    /** 从 PROPFIND 响应 XML 里提取 getcontentlength（不依赖 parsePropfind 过滤） */
    private fun extractContentLength(xml: String): Long {
        val regex = Regex("""<[^>]*getcontentlength[^>]*>(\d+)<""", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    /** 检测服务器是否支持 Range（返回 206） */
    fun supportsRange(path: String): Boolean {
        val req = requestBuilder("GET", path)
            .header("Range", "bytes=0-0")
            .build()
        return try {
            client.newCall(req).execute().use { it.code == 206 }
        } catch (e: Exception) { false }
    }

    fun mkcol(path: String): Boolean {
        val req = requestBuilder("MKCOL", path).build()
        client.newCall(req).execute().use { return it.isSuccessful || it.code == 201 }
    }

    fun delete(path: String): Boolean {
        val req = requestBuilder("DELETE", path).build()
        client.newCall(req).execute().use { return it.isSuccessful || it.code == 204 }
    }

    fun move(from: String, to: String): Boolean {
        val req = requestBuilder("MOVE", from)
            .header("Destination", fullUrl(to))
            .header("Overwrite", "T")
            .build()
        client.newCall(req).execute().use { return it.isSuccessful || it.code == 201 }
    }

    fun upload(path: String, bytes: ByteArray): Boolean {
        val req = requestBuilder("PUT", path)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { return it.isSuccessful || it.code == 201 || it.code == 204 }
    }

    /** 上传大文件用 RequestBody 流式 */
    fun uploadStream(path: String, body: RequestBody): Boolean {
        val req = requestBuilder("PUT", path).put(body).build()
        client.newCall(req).execute().use { return it.isSuccessful || it.code == 201 || it.code == 204 }
    }

    /** 下载完整文件（单连接，仅小文件用） */
    fun download(path: String): Response {
        val req = requestBuilder("GET", path).build()
        return client.newCall(req).execute()
    }

    /** 构造 Range 请求的 Response，由引擎调用 */
    fun downloadRange(path: String, start: Long, end: Long): Response {
        val req = requestBuilder("GET", path)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .build()
        return client.newCall(req).execute()
    }

    private fun parsePropfind(xml: String, requestPath: String): List<FileEntry> {
        if (xml.isBlank()) return emptyList()
        val result = mutableListOf<FileEntry>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var currentHref: String? = null
        var name = ""
        var size = 0L
        var lastMod = 0L
        var isDir = false
        var mime = ""
        var inResponse = false
        var inProp = false
        var eventType = parser.eventType

        fun reset() {
            currentHref = null; name = ""; size = 0L; lastMod = 0L; isDir = false; mime = ""
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when (tag) {
                        "response" -> { inResponse = true; reset() }
                        "href" -> if (inResponse) currentHref = parser.nextText()
                        "collection" -> if (inProp) isDir = true
                        "getcontentlength" -> if (inProp) size = parser.nextText().trim().toLongOrNull() ?: 0L
                        "displayname" -> if (inProp) name = parser.nextText()
                        "getcontenttype" -> if (inProp) mime = parser.nextText()
                        "getlastmodified" -> if (inProp) {
                            lastMod = try {
                                val sdf = java.text.SimpleDateFormat(
                                    "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                                )
                                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                sdf.parse(parser.nextText().trim())?.time ?: 0L
                            } catch (e: Exception) { 0L }
                        }
                        "prop" -> inProp = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    when (tag) {
                        "prop" -> inProp = false
                        "response" -> {
                            inResponse = false
                            val href = currentHref ?: ""
                            val decoded = java.net.URLDecoder.decode(href, "UTF-8")
                            val baseUriPath = try {
                                java.net.URI(getEffectiveBaseUrl()).path?.trimEnd('/') ?: ""
                            } catch (e: Exception) { "" }

                            // 提取纯路径（去除 http(s)://host:port 前缀）
                            val decodedPath = if (decoded.startsWith("http://", ignoreCase = true) ||
                                decoded.startsWith("https://", ignoreCase = true)
                            ) {
                                try { java.net.URI(decoded).path ?: decoded } catch (e: Exception) { decoded }
                            } else {
                                decoded
                            }
                            val cleanPath = decodedPath.trimEnd('/')

                            // 相对账户 baseUrl 的路径
                            val pathInAccount = if (baseUriPath.isNotEmpty() && cleanPath.startsWith(baseUriPath)) {
                                cleanPath.removePrefix(baseUriPath).ifEmpty { "/" }
                            } else {
                                cleanPath.ifEmpty { "/" }
                            }
                            val entryPath = if (pathInAccount.startsWith("/")) pathInAccount else "/$pathInAccount"

                            // 规范化请求路径对比，跳过目录自身
                            val reqNorm = if (requestPath.trimEnd('/').startsWith("/")) requestPath.trimEnd('/') else "/${requestPath.trimEnd('/')}"
                            val reqNormClean = if (reqNorm.isEmpty()) "/" else reqNorm
                            val isSelf = entryPath.equals(reqNormClean, ignoreCase = true) ||
                                cleanPath.equals(reqNormClean, ignoreCase = true) ||
                                (baseUriPath.isNotEmpty() && cleanPath.equals(baseUriPath, ignoreCase = true) && reqNormClean == "/")

                            if (!isSelf) {
                                val folderOrFileName = cleanPath.substringAfterLast('/').ifEmpty { cleanPath }
                                val displayName = if (name.isNotBlank() && !name.contains('/')) {
                                    name.trimEnd('/')
                                } else {
                                    folderOrFileName
                                }
                                result.add(
                                    FileEntry(
                                        name = displayName,
                                        path = entryPath,
                                        isDirectory = isDir,
                                        size = size,
                                        lastModified = lastMod,
                                        mimeType = mime,
                                        source = FileSource.WEBDAV
                                    )
                                )
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    companion object {
        private const val TAG = "WebDavClient"

        fun is302Url(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            return trimmed.startsWith("302:", ignoreCase = true) ||
                trimmed.startsWith("302：") ||
                trimmed.startsWith("301:", ignoreCase = true) ||
                trimmed.startsWith("301：")
        }

        fun extract302TargetUrl(url: String): String {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("302:", ignoreCase = true) -> trimmed.substring(4).trim()
                trimmed.startsWith("302：") -> trimmed.substring(4).trim()
                trimmed.startsWith("301:", ignoreCase = true) -> trimmed.substring(4).trim()
                trimmed.startsWith("301：") -> trimmed.substring(4).trim()
                else -> trimmed
            }
        }

        private data class HttpResponseSnapshot(
            val statusCode: Int,
            val location: String?,
            val body: String
        )

        private fun executeHttpSocketRequest(urlString: String): HttpResponseSnapshot {
            val uri = java.net.URI(urlString)
            val scheme = uri.scheme?.lowercase(java.util.Locale.US) ?: "http"
            val host = uri.host ?: throw java.net.MalformedURLException("Invalid host: $urlString")
            var port = uri.port
            if (port == -1) {
                port = if (scheme == "https") 443 else 80
            }
            var path = uri.rawPath
            if (path.isNullOrEmpty()) path = "/"
            if (uri.rawQuery != null) path += "?" + uri.rawQuery

            val socket: java.net.Socket
            if (scheme == "https") {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                })
                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, trustAll, java.security.SecureRandom())

                // 针对非标准服务端（如 Lucky/NAS等打洞），解析真实IP并移除 SNI serverNames 避免握手阻断/断连
                val ip = java.net.InetAddress.getByName(host)
                val ipOnly = java.net.InetAddress.getByAddress(ip.address)

                val raw = java.net.Socket()
                raw.connect(java.net.InetSocketAddress(ipOnly, port), 8000)
                raw.soTimeout = 8000

                val ssl = sc.socketFactory.createSocket(raw, null, port, true) as javax.net.ssl.SSLSocket
                try {
                    val p = ssl.sslParameters
                    p.serverNames = null
                    ssl.sslParameters = p
                } catch (_: Exception) {}
                ssl.startHandshake()
                socket = ssl
            } else {
                socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 8000)
                socket.soTimeout = 8000
            }

            socket.use { s ->
                val out = s.getOutputStream()
                val hostHeader = if (port == 80 || port == 443) host else "$host:$port"
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "User-Agent: Dalvik/2.1.0 (Linux; U; Android 15; V2309A Build/AP3A.240905.015.A1)\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(req.toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val statusLine = reader.readLine() ?: throw java.io.IOException("服务器无响应")
                var statusCode = 200
                val parts = statusLine.split(" ")
                if (parts.size >= 2) {
                    statusCode = parts[1].toIntOrNull() ?: 200
                }

                var location: String? = null
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    if (headerLine.startsWith("Location:", ignoreCase = true)) {
                        location = headerLine.substring(9).trim()
                    }
                }

                val body = java.lang.StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    body.append(line).append("\n")
                    if (body.length > 65536) break
                }

                return HttpResponseSnapshot(
                    statusCode = statusCode,
                    location = location,
                    body = body.toString()
                )
            }
        }

        private fun resolveRedirectUrl(baseUrl: String, location: String): String {
            val loc = location.trim()
            if (loc.startsWith("http://", ignoreCase = true) || loc.startsWith("https://", ignoreCase = true)) {
                return loc
            }
            val uri = try {
                java.net.URI(baseUrl)
            } catch (_: Exception) {
                null
            }
            val normalizedBase = if (uri == null || uri.rawPath.isNullOrEmpty()) {
                if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            } else {
                baseUrl
            }
            return try {
                java.net.URI(normalizedBase).resolve(loc).toString()
            } catch (_: Exception) {
                val scheme = uri?.scheme ?: "http"
                val authority = uri?.rawAuthority ?: uri?.host ?: ""
                if (loc.startsWith("/")) {
                    "$scheme://$authority$loc"
                } else {
                    val baseWithSlash = if (normalizedBase.endsWith("/")) normalizedBase else "$normalizedBase/"
                    baseWithSlash + loc
                }
            }
        }

        /**
         * 参照 Jellycine 实现的重定向追踪与纯文本端点提取：
         * 1. 采用 Socket 直连（信任所有证书并剥离 SNI，彻底解决 Lucky / nasnas.site 的 connection closed 问题）
         * 2. 跟踪 HTTP 301/302/303/307/308 重定向链
         * 3. 提取响应内容中的有效 URL 或 ip:port
         */
        fun fetch302WithRedirects(startUrl: String, maxHops: Int = 8): String {
            var currentUrl = startUrl.trim()
            if (!currentUrl.startsWith("http://", ignoreCase = true) && !currentUrl.startsWith("https://", ignoreCase = true)) {
                currentUrl = "http://$currentUrl"
            }
            val visited = mutableSetOf<String>()

            for (hop in 0 until maxHops) {
                visited.add(currentUrl.trimEnd('/'))
                val response = try {
                    executeHttpSocketRequest(currentUrl)
                } catch (e: Exception) {
                    Log.w(TAG, "executeHttpSocketRequest 失败 ($currentUrl): ${e.message}")
                    if (currentUrl.startsWith("https://", ignoreCase = true) && hop == 0) {
                        val httpUrl = "http://" + currentUrl.removePrefix("https://")
                        try {
                            executeHttpSocketRequest(httpUrl)
                        } catch (_: Exception) {
                            return currentUrl
                        }
                    } else {
                        return currentUrl
                    }
                }

                val statusCode = response.statusCode
                val location = response.location

                if ((statusCode in 301..303 || statusCode == 307 || statusCode == 308) && !location.isNullOrBlank()) {
                    val nextUrl = resolveRedirectUrl(currentUrl, location)
                    if (nextUrl.contains("0.0.0.0") || visited.contains(nextUrl.trimEnd('/'))) {
                        break
                    }
                    Log.i(TAG, "fetch302WithRedirects 重定向: $currentUrl -> $nextUrl ($statusCode)")
                    currentUrl = nextUrl
                    continue
                }

                // 检查非重定向返回内容（例如 200 OK 中的 IP:端口 或 URL）
                val bodyStr = response.body.trim()
                for (line in bodyStr.lines()) {
                    val candidate = line.trim()
                    if (candidate.isNotBlank() &&
                        (candidate.startsWith("http://", ignoreCase = true) ||
                         candidate.startsWith("https://", ignoreCase = true) ||
                         (candidate.contains(":") && !candidate.contains("<") && !candidate.contains("{") && !candidate.contains(" ")))
                    ) {
                        val fullAddress = if (!candidate.startsWith("http://", ignoreCase = true) && !candidate.startsWith("https://", ignoreCase = true)) {
                            "http://$candidate"
                        } else {
                            candidate
                        }
                        Log.i(TAG, "fetch302WithRedirects 提取到端点: $fullAddress (来源: $currentUrl)")
                        return fullAddress
                    }
                }

                if (hop > 0) {
                    return currentUrl
                }

                if (bodyStr.isNotBlank() && !bodyStr.contains("<html", ignoreCase = true) && !bodyStr.contains("<?xml", ignoreCase = true)) {
                    val ipPortRegex = """(?:https?://)?([a-zA-Z0-9.-]+:\d{1,5}(?:/\S*)?)""".toRegex()
                    val match = ipPortRegex.find(bodyStr)
                    if (match != null) {
                        var extracted = match.value.trim()
                        if (!extracted.startsWith("http://", ignoreCase = true) && !extracted.startsWith("https://", ignoreCase = true)) {
                            extracted = "http://$extracted"
                        }
                        Log.i(TAG, "fetch302WithRedirects 正则提取到: $extracted")
                        return extracted
                    }
                }

                return currentUrl
            }
            return currentUrl
        }

        fun resolveEndpoint(rawUrl: String): String {
            val target = extract302TargetUrl(rawUrl).trim()
            if (target.isBlank()) return target
            return try {
                fetch302WithRedirects(target)
            } catch (e: Exception) {
                Log.w(TAG, "resolveEndpoint 异常: ${e.message}")
                target
            }
        }
    }
}
