package com.example.myfile.core

import android.util.Log
import com.example.myfile.model.WebDavAccount
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 HTTP 流式代理：把带 Basic Auth 的 WebDAV 文件转成无认证的
 * http://127.0.0.1:<port>/<token>/<url-encoded-path> 链接，交给第三方播放器。
 *
 * 播放器通过该本地地址流式读取（内部自行缓冲/seek），避免先完整下载。
 * 支持 Range 请求，让视频播放器可以拖动进度条。
 */
object StreamProxy {

    private const val TAG = "StreamProxy"
    private const val BUFFER = 64 * 1024

    @Volatile
    private var serverSocket0: ServerSocket? = null
    private var port: Int = 0
    private var serverThread: Thread? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** token -> (client, baseUrl, username, password, remotePath) */
    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Entry(
        val client: OkHttpClient,
        val baseUrl: String,
        val username: String,
        val password: String,
        val remotePath: String
    )

    /** 懒启动本地服务器，返回端口 */
    @Synchronized
    private fun ensureStarted(): Int {
        if (running.get()) return port
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
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

                // 读取并丢弃请求头
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }

                // 解析 /token/encoded-path
                val path = URLDecoder.decode(rawPath, "UTF-8")
                val seg = path.trimStart('/').split('/', limit = 2)
                if (seg.size < 2) { writeError(output, 400, "bad path"); return }
                val token = seg[0]
                val remotePath = "/" + seg[1]
                val entry = entries[token]
                if (entry == null) { writeError(output, 404, "stream expired"); return }

                val rangeHeader = headers["range"]
                val upstream = buildRequest(entry, remotePath, rangeHeader)

                entry.client.newCall(upstream).execute().use { resp ->
                    val status = resp.code
                    val respHeaders = resp.headers
                    val respBody = resp.body

                    // 写状态行
                    val reason = if (status == 206) "Partial Content" else "OK"
                    output.write("HTTP/1.1 $status $reason\r\n".toByteArray())

                    // 透传关键头
                    listOf("Content-Length", "Content-Range", "Content-Type", "Accept-Ranges").forEach { h ->
                        val v = respHeaders[h]
                        if (v != null) output.write("$h: $v\r\n".toByteArray())
                    }
                    output.write("Connection: close\r\n".toByteArray())
                    output.write("\r\n".toByteArray())

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
        val fullUrl = entry.baseUrl.trimEnd('/') + remotePath
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
     * 为指定账户的远程文件注册一个流式代理，返回本地可访问的 http URL。
     */
    fun register(
        client: OkHttpClient,
        account: WebDavAccount,
        remotePath: String
    ): String {
        val p = ensureStarted()
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(
            client = client,
            baseUrl = account.url,
            username = account.username,
            password = account.password,
            remotePath = remotePath
        )
        val encoded = java.net.URLEncoder.encode(remotePath.trimStart('/'), "UTF-8")
        return "http://127.0.0.1:$p/$token/$encoded"
    }

    /** 释放某个 token（播放器关闭后清理，避免内存泄漏） */
    fun unregister(token: String) {
        entries.remove(token)
    }
}
