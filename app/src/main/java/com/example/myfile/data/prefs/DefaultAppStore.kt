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

    /** 读取某个类别的默认程序（"packageName/activityName" 或 null） */
    suspend fun get(category: String): String? = map.first()[category]

    /** 设置某个类别的默认程序 */
    suspend fun set(category: String, packageName: String, activityName: String) {
        context.defaultAppStore.edit { p ->
            val current = p[key]?.let { decode(it) } ?: emptyMap()
            val updated = current + (category to "$packageName/$activityName")
            p[key] = encode(updated)
        }
    }

    /** 清除某个类别的默认程序 */
    suspend fun clear(category: String) {
        context.defaultAppStore.edit { p ->
            val current = p[key]?.let { decode(it) } ?: emptyMap()
            val updated = current - category
            p[key] = encode(updated)
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
