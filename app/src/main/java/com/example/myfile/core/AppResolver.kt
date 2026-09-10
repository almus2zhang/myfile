package com.example.myfile.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

/** 可打开某文件的候选应用 */
data class AppCandidate(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable?
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

/**
 * 查询能处理某个 MIME 类型 / ACTION_VIEW 的应用列表，
 * 以及用显式 ComponentName 启动指定应用（绕过系统默认应用）。
 */
object AppResolver {

    /** 查询能处理该 Intent 的所有应用（去重、按 label 排序） */
    fun resolveCandidates(context: Context, intent: Intent): List<AppCandidate> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }

        fun query(targetIntent: Intent): List<ResolveInfo> {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(targetIntent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(targetIntent, flags)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        val allInfos = mutableListOf<ResolveInfo>()

        // 1. 原始 Intent (带 uri 和 具体 MIME，如 video/mp4)
        allInfos.addAll(query(intent))

        // 2. 主类型通配 (例如 video/*)
        val mime = intent.type
        if (mime != null && mime.contains('/')) {
            val mainType = mime.substringBefore('/') + "/*"
            if (mainType != mime) {
                val broadIntent = Intent(intent).apply {
                    if (data != null) setDataAndType(data, mainType) else type = mainType
                }
                allInfos.addAll(query(broadIntent))
            }
        }

        // 3. 兜底：某些播放器只声明了 MIME 类型、不声明 http/content scheme，按纯 type 再查一次
        if (intent.type != null) {
            val typeOnly = Intent(Intent.ACTION_VIEW).apply {
                type = intent.type
            }
            allInfos.addAll(query(typeOnly))
        }

        // 4. 通配 (*/*)
        val anyIntent = Intent(intent).apply {
            if (data != null) setDataAndType(data, "*/*") else type = "*/*"
        }
        allInfos.addAll(query(anyIntent))

        return allInfos.mapNotNull { info ->
            val ai = info.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            AppCandidate(
                label = info.loadLabel(pm)?.toString()?.ifBlank { null } ?: ai.packageName,
                packageName = ai.packageName,
                activityName = ai.name,
                icon = info.loadIcon(pm)
            )
        }.distinctBy { it.packageName + "/" + it.activityName }
            .sortedBy { it.label }
    }

    /** 用显式 ComponentName 启动指定应用打开该 Intent */
    fun openWith(context: Context, intent: Intent, candidate: AppCandidate): Boolean {
        return try {
            val explicit = Intent(intent).apply {
                component = candidate.component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 关键：让目标应用在独立任务中打开，而不是复用/嵌入 myfile 的任务，
                // 这样最近任务列表里 myfile 和目标应用各自独立。
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(explicit)
            true
        } catch (e: Exception) {
            android.util.Log.e("AppResolver", "openWith failed", e)
            false
        }
    }

    /** 使用系统选择器打开 */
    fun openWithSystemChooser(context: Context, intent: Intent, title: String = "打开为") {
        try {
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("AppResolver", "openWithSystemChooser failed", e)
        }
    }
}
