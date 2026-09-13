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

    /** 文本读取结果封装 */
    data class TextLoadResult(
        val content: String,
        val charsetName: String,
        val isBinary: Boolean = false,
        val isTruncated: Boolean = false
    )

    /** 编码选项 */
    data class EncodingOption(
        val name: String,
        val displayName: String
    )

    /** 支持的常用编码列表 */
    val COMMON_ENCODINGS: List<EncodingOption> = listOf(
        EncodingOption("UTF-8", "UTF-8 (推荐)"),
        EncodingOption("GB18030", "GB18030 (中文/GBK/GB2312)"),
        EncodingOption("GBK", "GBK (简体中文)"),
        EncodingOption("Big5", "Big5 (繁体中文)"),
        EncodingOption("UTF-16LE", "UTF-16 LE (Unicode)"),
        EncodingOption("UTF-16BE", "UTF-16 BE (Unicode)"),
        EncodingOption("windows-1252", "Windows-1252 (ANSI 西欧)"),
        EncodingOption("ISO-8859-1", "ISO-8859-1 (Latin-1)"),
        EncodingOption("US-ASCII", "US-ASCII (纯英文)")
    ).filter {
        try { java.nio.charset.Charset.isSupported(it.name) } catch (_: Exception) { false }
    }

    /**
     * 校验字节数组是否符合某种编码的解码规则
     */
    fun isValidCharset(charset: java.nio.charset.Charset, bytes: ByteArray, length: Int): Boolean {
        if (length <= 0) return true
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val byteBuf = java.nio.ByteBuffer.wrap(bytes, 0, length)
            val charBuf = java.nio.CharBuffer.allocate(length)
            val result = decoder.decode(byteBuf, charBuf, true)
            !result.isError
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 自动探测文本字节流的编码：
     * 1. 优先根据 BOM 识别 UTF-8 / UTF-16LE / UTF-16BE
     * 2. 无 BOM 时，严格检验 UTF-8 编码结构
     * 3. 若 UTF-8 校验失败，检验 GB18030 / GBK（中文 Windows 常见编码）
     * 4. 若仍不匹配，检验 Big5（繁体中文）
     * 5. 默认回退为 UTF-8
     */
    fun detectEncoding(bytes: ByteArray, length: Int): Pair<String, Int> {
        if (length >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return Pair("UTF-8", 3)
        }
        if (length >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xFE
        ) {
            return Pair("UTF-16LE", 2)
        }
        if (length >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFE &&
            (bytes[1].toInt() and 0xFF) == 0xFF
        ) {
            return Pair("UTF-16BE", 2)
        }

        // 优先严格校验 UTF-8
        if (isValidCharset(Charsets.UTF_8, bytes, length)) {
            return Pair("UTF-8", 0)
        }

        // 校验 GB18030 (向下完全兼容 GBK 与 GB2312)
        try {
            val gbk = java.nio.charset.Charset.forName("GB18030")
            if (isValidCharset(gbk, bytes, length)) {
                return Pair("GB18030", 0)
            }
        } catch (_: Exception) {}

        // 校验 Big5
        try {
            val big5 = java.nio.charset.Charset.forName("Big5")
            if (isValidCharset(big5, bytes, length)) {
                return Pair("Big5", 0)
            }
        } catch (_: Exception) {}

        return Pair("UTF-8", 0)
    }

    /**
     * 安全流式读取数据：支持常用字符集编码（自动探测或用户指定编码），
     * 限制单次最大读取 256KB 并在必要时软换行防止 Android Minikin 排版卡死。
     */
    fun readStreamSafely(
        inputStream: InputStream,
        totalBytes: Long,
        requestedCharset: String? = null,
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit
    ): TextLoadResult {
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
            return TextLoadResult(
                content = formatHexDump(binaryData, totalBinaryRead, if (totalBytes > 0) totalBytes else totalBinaryRead.toLong()),
                charsetName = "Binary",
                isBinary = true,
                isTruncated = totalBytes > totalBinaryRead
            )
        }

        // 确定使用的编码
        val (resolvedCharsetName, bomSkip) = if (requestedCharset.isNullOrBlank()) {
            detectEncoding(initialBuffer, initialRead)
        } else {
            val skip = when {
                requestedCharset.equals("UTF-8", ignoreCase = true) && initialRead >= 3 &&
                    (initialBuffer[0].toInt() and 0xFF) == 0xEF &&
                    (initialBuffer[1].toInt() and 0xFF) == 0xBB &&
                    (initialBuffer[2].toInt() and 0xFF) == 0xBF -> 3
                requestedCharset.startsWith("UTF-16", ignoreCase = true) && initialRead >= 2 &&
                    (((initialBuffer[0].toInt() and 0xFF) == 0xFF && (initialBuffer[1].toInt() and 0xFF) == 0xFE) ||
                     ((initialBuffer[0].toInt() and 0xFF) == 0xFE && (initialBuffer[1].toInt() and 0xFF) == 0xFF)) -> 2
                else -> 0
            }
            Pair(requestedCharset, skip)
        }

        val targetCharset = try {
            java.nio.charset.Charset.forName(resolvedCharsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
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

        // 处理第一个 buffer（跳过可能存在的 BOM）
        val actualInitialOffset = bomSkip
        val actualInitialLen = maxOf(0, initialRead - actualInitialOffset)
        if (actualInitialLen > 0) {
            val firstText = String(initialBuffer, actualInitialOffset, actualInitialLen, targetCharset)
            val chars = firstText.toCharArray()
            appendSafeChunk(chars, chars.size)
            loadedBytes += initialRead
        }

        // 继续逐块读取剩余流
        if (!isTruncated) {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, targetCharset))
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
        return TextLoadResult(
            content = sb.toString(),
            charsetName = resolvedCharsetName,
            isBinary = false,
            isTruncated = isTruncated
        )
    }

    /** 兼容旧版调用的重载方法 */
    fun readStreamSafely(
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit
    ): String {
        return readStreamSafely(inputStream, totalBytes, null, onProgress).content
    }
}
