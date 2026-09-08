package com.example.myfile.core

import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.myfile.MyApp
import com.example.myfile.data.db.entity.VideoProgressEntity
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 HTTP 流式代理：把带 Basic Auth 的 WebDAV 文件转成无认证的
 * http://127.0.0.1:<port>/<token>/<filename> 链接，交给第三方播放器。
 *
 * 播放器通过该本地地址流式读取（内部自行缓冲/seek），避免先完整下载。
 * 支持 Range 请求，让视频播放器可以拖动进度条。
 * 采用固定优先端口与基于文件唯一路径的 MD5 标识，使得第三方播放器内部的历史进度记忆机制能准确命中同一文件！
 */
object StreamProxy {

    private const val TAG = "StreamProxy"
    private const val BUFFER = 64 * 1024
    private const val PREFERRED_PORT = 58241

    @Volatile
    private var serverSocket0: ServerSocket? = null
    private var port: Int = 0
    private var serverThread: Thread? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val proxyScope = CoroutineScope(Dispatchers.IO)

    /** token -> Entry */
    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(
        val client: OkHttpClient,
        var account: WebDavAccount,
        val remotePath: String,
        val rawKey: String,
        val originalPath: String = remotePath,
        val fakeAvi: Boolean = false
    ) {
        val username get() = account.username
        val password get() = account.password
        fun currentBaseUrl(): String = account.connectionUrl()
    }

    /** 启动本地服务器，优先使用固定端口 58241，被占用则由系统分配 */
    @Synchronized
    private fun ensureStarted(): Int {
        if (running.get()) return port
        val ss = try {
            ServerSocket(PREFERRED_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        }
        port = ss.localPort
        serverSocket0 = ss
        running.set(true)
        serverThread = Thread({ acceptLoop(ss) }, "StreamProxy").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "StreamProxy listening on 127.0.0.1:$port")
        return port
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                Thread({ handle(socket) }, "StreamProxy-conn").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
            }
        }
    }

    private fun handle(socket: java.net.Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                // 解析请求行
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]

                // 读取并解析请求头
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }

                // 解析 /token/encoded-filename
                val path = URLDecoder.decode(rawPath, "UTF-8")
                val seg = path.trimStart('/').split('/', limit = 2)
                if (seg.isEmpty()) { writeError(output, 400, "bad path"); return }
                val token = seg[0]
                val entry = entries[token]
                if (entry == null) { writeError(output, 404, "stream expired"); return }

                val rangeHeader = headers["range"]
                var upstream = buildRequest(entry, entry.remotePath, rangeHeader)

                val callExecution = {
                    try {
                        entry.client.newCall(upstream).execute()
                    } catch (e: Exception) {
                        if (entry.account.isDynamic) {
                            try {
                                val freshUrl = kotlinx.coroutines.runBlocking {
                                    MyApp.instance.webDavRepository.reResolveAndSave(entry.account)
                                }
                                entry.account = entry.account.copy(resolvedUrl = freshUrl)
                                upstream = buildRequest(entry, entry.remotePath, rangeHeader)
                                entry.client.newCall(upstream).execute()
                            } catch (_: Exception) {
                                throw e
                            }
                        } else {
                            throw e
                        }
                    }
                }

                callExecution().use { resp ->
                    val status = resp.code
                    val respHeaders = resp.headers
                    val respBody = resp.body

                    // 写状态行
                    val reason = if (status == 206) "Partial Content" else "OK"
                    output.write("HTTP/1.1 $status $reason\r\n".toByteArray())

                    // 透传关键头
                    listOf("Content-Length", "Content-Range", "Content-Type", "Accept-Ranges").forEach { h ->
                        val v = if (h == "Content-Type" && entry.fakeAvi) {
                            val origMime = FileOpener.guessMime(entry.originalPath)
                            if (origMime != "*/*") origMime else "video/*"
                        } else {
                            respHeaders[h]
                        }
                        if (v != null) output.write("$h: $v\r\n".toByteArray())
                    }
                    output.write("Connection: close\r\n".toByteArray())
                    output.write("\r\n".toByteArray())

                    // 根据 Content-Range 估算并实时记录当前流式播放字节进度
                    val cr = respHeaders["Content-Range"]
                    if (cr != null) {
                        val match = Regex("""bytes\s+(\d+)-\d+/(\d+)""").find(cr)
                        val startByte = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val totalBytes = match?.groupValues?.get(2)?.toLongOrNull() ?: 0L
                        if (totalBytes > 0L && startByte > 0L) {
                            val ratio = startByte.toDouble() / totalBytes
                            proxyScope.launch {
                                try {
                                    val existing = MyApp.instance.db.videoProgressDao().get(entry.rawKey)
                                    if (existing != null && existing.durationMs > 0L) {
                                        val calculatedPos = (ratio * existing.durationMs).toLong()
                                        if (calculatedPos > 1000L) {
                                            MyApp.instance.db.videoProgressDao().save(
                                                VideoProgressEntity(
                                                    uriKey = entry.rawKey,
                                                    positionMs = calculatedPos,
                                                    durationMs = existing.durationMs,
                                                    updatedAt = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    respBody?.byteStream()?.use { bodyIn ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = bodyIn.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 播放器主动断开是正常的（拖动进度/关闭），忽略
        }
    }

    private fun buildRequest(entry: Entry, remotePath: String, rangeHeader: String?): Request {
        val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        val fullUrl = entry.currentBaseUrl().trimEnd('/') + path
        val auth = "Basic " + Base64.getEncoder()
            .encodeToString("${entry.username}:${entry.password}".toByteArray())
        return Request.Builder()
            .url(fullUrl)
            .header("Authorization", auth)
            .header("User-Agent", "myfile/1.0 (Android; WebDAV)")
            .header("Accept-Encoding", "identity")
            .apply { if (rangeHeader != null) header("Range", rangeHeader) }
            .get()
            .build()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun writeError(output: java.io.OutputStream, code: Int, msg: String) {
        try {
            val body = msg.toByteArray()
            output.write("HTTP/1.1 $code Error\r\n".toByteArray())
            output.write("Content-Length: ${body.size}\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        } catch (_: Exception) {}
    }

    /**
     * 为指定账户的远程文件注册一个流式代理，返回本地可访问的稳定 http URL。
     */
    fun register(
        client: OkHttpClient,
        account: WebDavAccount,
        remotePath: String,
        displayName: String? = null,
        fakeAvi: Boolean = false,
        originalPath: String = remotePath
    ): String {
        val p = ensureStarted()
        val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        val origPath = if (originalPath.startsWith("/")) originalPath else "/$originalPath"
        val rawKey = "acc_${account.id}$origPath"
        val md5 = MessageDigest.getInstance("MD5")
            .digest(rawKey.toByteArray())
            .joinToString("") { "%02x".format(it) }

        entries[md5] = Entry(
            client = client,
            account = account,
            remotePath = path,
            rawKey = rawKey,
            originalPath = origPath,
            fakeAvi = fakeAvi
        )

        val baseName = path.substringAfterLast('/').ifBlank { "video.mp4" }
        val fileName = displayName?.ifBlank { null } ?: baseName
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val streamUrl = "http://127.0.0.1:$p/$md5/$encodedName"

        // 后台异步获取视频总时长（若数据库中尚无此视频时长记录）
        proxyScope.launch {
            try {
                val existing = MyApp.instance.db.videoProgressDao().get(rawKey)
                if (existing == null || existing.durationMs <= 0L) {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(streamUrl, emptyMap())
                    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                    if (dur > 0L) {
                        MyApp.instance.db.videoProgressDao().save(
                            VideoProgressEntity(
                                uriKey = rawKey,
                                positionMs = existing?.positionMs ?: 0L,
                                durationMs = dur,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return streamUrl
    }

    /** 释放某个 token */
    fun unregister(token: String) {
        entries.remove(token)
    }
}
