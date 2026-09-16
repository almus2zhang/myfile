package com.example.myfile.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.defaultAppStore: DataStore<Preferences> by preferencesDataStore(name = "default_apps")

/**
 * 应用内部的「文件类型 -> 默认打开程序」映射存储。
 * 与 Android 系统默认应用设置无关，由 myfile 自己记录（对齐 CX 浏览器行为）。
 *
 * key = 文件类别（video/image/audio/text/archive/doc/apk/other）
 * value = "packageName/activityName"
 */
class DefaultAppStore(private val context: Context) {

    private val key = stringPreferencesKey("default_app_map")

    /** 读取整个映射 */
    val map: Flow<Map<String, String>> = context.defaultAppStore.data.map { p ->
        p[key]?.let { decode(it) } ?: emptyMap()
    }

    /** 读取某个类别或后缀的默认程序（"packageName/activityName" 或 null） */
    suspend fun get(category: String, ext: String? = null): String? {
        val current = map.first()
        val cleanExt = ext?.trim()?.removePrefix(".")?.lowercase()
        val res = (if (!cleanExt.isNullOrEmpty()) current[cleanExt] else null) ?: current[category]
        com.example.myfile.core.TrafficMonitor.debug("DefaultAppStore.get: cat=$category, ext=$cleanExt -> $res")
        return res
    }

    /** 读取某个类别的默认程序（重载兼容） */
    suspend fun get(category: String): String? = get(category, null)

    /** 设置默认程序（同时记录 category 和具体的 ext） */
    suspend fun set(category: String, packageName: String, activityName: String, ext: String? = null) {
        val cleanExt = ext?.trim()?.removePrefix(".")?.lowercase()
        com.example.myfile.core.TrafficMonitor.debug("DefaultAppStore.set开始: cat=$category, ext=$cleanExt -> $packageName/$activityName")
        context.defaultAppStore.edit { p ->
            val current = p[key]?.let { decode(it) } ?: emptyMap()
            val mutable = current.toMutableMap()
            mutable[category] = "$packageName/$activityName"
            if (!cleanExt.isNullOrEmpty()) {
                mutable[cleanExt] = "$packageName/$activityName"
            }
            p[key] = encode(mutable)
        }
        com.example.myfile.core.TrafficMonitor.debug("DefaultAppStore.set完成: cat=$category, ext=$cleanExt")
    }

    /** 清除某个类别的默认程序 */
    suspend fun clear(category: String, ext: String? = null) {
        val cleanExt = ext?.trim()?.removePrefix(".")?.lowercase()
        context.defaultAppStore.edit { p ->
            val current = p[key]?.let { decode(it) } ?: emptyMap()
            val mutable = current.toMutableMap()
            mutable.remove(category)
            if (!cleanExt.isNullOrEmpty()) {
                mutable.remove(cleanExt)
            }
            p[key] = encode(mutable)
        }
    }

    private fun encode(map: Map<String, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decode(s: String): Map<String, String> {
        return s.split(';').filter { it.isNotBlank() }.mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) part.substring(0, idx) to part.substring(idx + 1) else null
        }.toMap()
    }
}
