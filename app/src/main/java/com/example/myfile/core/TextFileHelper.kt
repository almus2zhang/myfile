package com.example.myfile.core

import com.example.myfile.ui.components.formatSize
import java.io.InputStream

/**
 * 文本与二进制文件安全流式读取与排版助手：
 * 1. 自动检测二进制数据（空字节与控制字符比例），对非文本文件自动生成 Hex 视图，杜绝原生文本排版引擎卡死。
 * 2. 对超长连续无换行行（如单行无断行压缩 JSON / 代码）进行安全折行，防止 Android Minikin / ICU 崩溃。
 */
object TextFileHelper {

    /**
     * 判断字节缓冲区是否为二进制数据
     */
    fun isBinaryBytes(bytes: ByteArray, length: Int): Boolean {
        if (length <= 0) return false
        val checkLen = minOf(length, 8192)
        var nullCount = 0
        var controlCount = 0
        for (i in 0 until checkLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) nullCount++
            else if (b < 32 && b != 9 && b != 10 && b != 13) controlCount++
        }
        // 如果含有空字节，或者控制字符比例大于 15%，判定为二进制
        return nullCount > 0 || (controlCount.toFloat() / checkLen > 0.15f)
    }

    /**
     * 将二进制数据格式化为规范的 Hex + ASCII 对照视图
     */
    fun formatHexDump(bytes: ByteArray, length: Int, totalSize: Long): String {
        val sb = StringBuilder()
        sb.append("--- [检测到二进制文件，已自动启用十六进制 Hex 安全预览（只读）] ---\n")
        if (totalSize > length) {
            sb.append("--- [文件总大小: ${formatSize(totalSize)}，已展示前 ${formatSize(length.toLong())}] ---\n\n")
        } else {
            sb.append("--- [文件大小: ${formatSize(totalSize)}] ---\n\n")
        }

        val hexChars = "0123456789ABCDEF".toCharArray()
        val lineBytes = 16
        var offset = 0
        while (offset < length) {
            // 8 位地址偏移 00000000:
            val offStr = offset.toString(16).padStart(8, '0').uppercase()
            sb.append(offStr).append("  ")

            // Hex 字节区 16 字节，中间加空格隔开
            val chunkLen = minOf(lineBytes, length - offset)
            for (i in 0 until lineBytes) {
                if (i == 8) sb.append(' ')
                if (i < chunkLen) {
                    val b = bytes[offset + i].toInt() and 0xFF
                    sb.append(hexChars[b ushr 4])
                    sb.append(hexChars[b and 0x0F])
                    sb.append(' ')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |")
            // ASCII 可视字符区（非可见字符用 . 代替）
            for (i in 0 until chunkLen) {
                val b = bytes[offset + i].toInt() and 0xFF
                if (b in 32..126) {
                    sb.append(b.toChar())
                } else {
                    sb.append('.')
                }
            }
            sb.append("|\n")
            offset += lineBytes
        }
        if (totalSize > length) {
            sb.append("\n--- [仅展示前 64KB 十六进制内容，更多内容已省略] ---")
        }
        return sb.toString()
    }

    /** 单次文本最大安全加载上限（256KB） */
    const val MAX_TEXT_LOAD_CHARS = 256 * 1024

    /**
     * 安全读取数据流：
     * 1. 自动检测二进制。若是二进制，读取前 64KB 生成 Hex 视图。
     * 2. 若是文本，读取前 256KB，并对长行（单行无换行超 200 字符）安全软折行，防 Android 渲染引擎卡死。
     */
    fun readStreamSafely(
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit
    ): String {
        val initialBuffer = ByteArray(8192)
        var initialRead = 0
        while (initialRead < initialBuffer.size) {
            val r = inputStream.read(initialBuffer, initialRead, initialBuffer.size - initialRead)
            if (r == -1) break
            initialRead += r
        }

        val isBinary = isBinaryBytes(initialBuffer, initialRead)
        if (isBinary) {
            // 二进制模式：读取最多 64KB 原始字节生成 Hex 视图
            val maxBinary = 65536
            val binaryData = ByteArray(maxBinary)
            System.arraycopy(initialBuffer, 0, binaryData, 0, initialRead)
            var totalBinaryRead = initialRead
            while (totalBinaryRead < maxBinary) {
                val r = inputStream.read(binaryData, totalBinaryRead, maxBinary - totalBinaryRead)
                if (r == -1) break
                totalBinaryRead += r
                onProgress(totalBinaryRead.toLong(), totalBytes)
            }
            onProgress(totalBinaryRead.toLong(), totalBytes)
            return formatHexDump(binaryData, totalBinaryRead, if (totalBytes > 0) totalBytes else totalBinaryRead.toLong())
        }

        // 文本模式：读取前 256KB 内容
        val sb = StringBuilder()
        var loadedBytes = 0L
        var lastReportTime = 0L
        val maxChars = MAX_TEXT_LOAD_CHARS
        var isTruncated = false

        // 辅助将 chunk 添加到 sb，对连续无换行超 200 字符的安全断行
        var lineCharCount = 0
        fun appendSafeChunk(chars: CharArray, count: Int) {
            for (i in 0 until count) {
                if (sb.length >= maxChars) {
                    isTruncated = true
                    break
                }
                val c = chars[i]
                if (c == '\n') {
                    sb.append('\n')
                    lineCharCount = 0
                } else if (c == '\r') {
                    sb.append('\r')
                } else {
                    sb.append(c)
                    lineCharCount++
                    if (lineCharCount >= 200) {
                        sb.append('\n')
                        lineCharCount = 0
                    }
                }
            }
        }

        // 处理第一个 buffer
        if (initialRead > 0) {
            val firstText = String(initialBuffer, 0, initialRead, Charsets.UTF_8)
            val chars = firstText.toCharArray()
            appendSafeChunk(chars, chars.size)
            loadedBytes += initialRead
        }

        // 继续逐块读取
        if (!isTruncated) {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8))
            val buf = CharArray(16384)
            var readChars: Int
            while (reader.read(buf).also { readChars = it } != -1) {
                appendSafeChunk(buf, readChars)
                loadedBytes += readChars
                val now = System.currentTimeMillis()
                if (now - lastReportTime > 100) {
                    lastReportTime = now
                    onProgress(loadedBytes, totalBytes)
                }
                if (isTruncated || sb.length >= maxChars) {
                    isTruncated = true
                    break
                }
            }
        }

        if (isTruncated) {
            sb.append("\n\n--- [文件过大，已自动截断前 256KB 内容] ---")
        }
        onProgress(loadedBytes, totalBytes)
        return sb.toString()
    }
}
