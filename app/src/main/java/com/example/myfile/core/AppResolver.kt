package com.example.myfile.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

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
        // 优先按 data+type 严格匹配
        var infos: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        // 兜底：某些播放器只声明了 MIME 类型、不声明 http scheme，
        // 此时按纯 type（去掉 data）再查一次
        if (infos.isEmpty() && intent.type != null) {
            val typeOnly = Intent(Intent.ACTION_VIEW).apply {
                type = intent.type
            }
            infos = try {
                pm.queryIntentActivities(typeOnly, 0)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return infos.mapNotNull { info ->
            val ai = info.activityInfo ?: return@mapNotNull null
            AppCandidate(
                label = info.loadLabel(pm)?.toString() ?: ai.packageName,
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(explicit)
            true
        } catch (e: Exception) {
            android.util.Log.e("AppResolver", "openWith failed", e)
            false
        }
    }
}
