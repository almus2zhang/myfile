package com.example.myfile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /**
     * 直接调起系统安装器安装指定的本地 APK 文件
     */
    fun install(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || !apkFile.isFile) {
                Toast.makeText(context, "安装包不存在", Toast.LENGTH_SHORT).show()
                return
            }

            // Android 8.0 (API 26) 及以上需检查未知应用安装权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canInstall = context.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    Toast.makeText(context, "请先允许“安装未知应用”权限", Toast.LENGTH_LONG).show()
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "无法启动安装程序: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
