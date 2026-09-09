package com.example.myfile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class FavoritePathStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("favorite_paths", Context.MODE_PRIVATE)

    /** 获取指定键（账户或本地）的常用路径列表 */
    fun getFavorites(key: String): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 添加常用路径（置顶且去重） */
    fun addFavorite(key: String, path: String) {
        val p = if (path.isBlank()) "/" else path
        val current = getFavorites(key).toMutableList()
        current.remove(p)
        current.add(0, p)
        saveList(key, current)
    }

    /** 移除指定常用路径 */
    fun removeFavorite(key: String, path: String) {
        val current = getFavorites(key).toMutableList()
        current.remove(path)
        saveList(key, current)
    }

    private fun saveList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        fun buildWebDavKey(accountId: Long): String = "webdav_$accountId"
        const val LOCAL_STORAGE_KEY = "local_storage"
    }
}
