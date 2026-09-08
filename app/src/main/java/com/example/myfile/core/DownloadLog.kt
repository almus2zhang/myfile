package com.example.myfile.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载日志：同时输出到 Logcat 和手机本地文件。
 * 文件路径：/sdcard/Download/myfile/myfile_download.log
 */
object DownloadLog {

    private const val TAG = "DownloadLog"
    private var logFile: File? = null

    fun ensureFile() {
        if (logFile != null) return
        try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "myfile")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "myfile_download.log")
            Log.d(TAG, "log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "cannot create log file", e)
        }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        ensureFile()
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} [$tag] $msg"
        Log.d(tag, msg)
        try {
            logFile?.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "write log failed", e)
        }
    }

    fun clear() {
        try {
            logFile?.delete()
            logFile = null
        } catch (e: Exception) { Log.e(TAG, "clear log failed", e) }
    }
}
