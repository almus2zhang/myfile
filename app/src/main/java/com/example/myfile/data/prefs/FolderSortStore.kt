package com.example.myfile.data.prefs

import android.content.Context
import com.example.myfile.ui.webdav.SortMode

/**
 * 记忆并持久化每个文件夹各自的排序方式（字段 + 升序/降序）
 */
class FolderSortStore(context: Context) {
    private val prefs = context.getSharedPreferences("folder_sort_prefs", Context.MODE_PRIVATE)

    /**
     * 获取指定目录保存的排序设置。若未单独设置过，则返回 null。
     */
    fun getSort(folderKey: String): Pair<SortMode, Boolean>? {
        val raw = prefs.getString(folderKey, null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val mode = try { SortMode.valueOf(parts[0]) } catch (e: Exception) { return null }
        val asc = parts[1].toBooleanStrictOrNull() ?: return null
        return mode to asc
    }

    /**
     * 保存指定目录的排序设置
     */
    fun saveSort(folderKey: String, mode: SortMode, asc: Boolean) {
        prefs.edit().putString(folderKey, "${mode.name}:$asc").apply()
    }

    companion object {
        /**
         * 针对 WebDav 账户的路径键值，隔离不同账户相同路径的排序设置
         */
        fun buildWebDavKey(accountId: Long, path: String): String {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return "webdav_${accountId}_$normalized"
        }

        /**
         * 针对本地文件夹的绝对路径键值
         */
        fun buildLocalKey(path: String): String {
            return "local_$path"
        }
    }
}
