package com.example.myfile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 隐藏文件夹与文件持久化存储
 *
 * 支持为 WebDAV 账户（按 accountId 分离）和本地存储分别记录隐藏路径。
 * 支持递归检查祖先目录隐藏状态，以及按目录前缀批量解除当前目录及其子目录下的所有隐藏。
 */
class HiddenItemStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hidden_items", Context.MODE_PRIVATE)

    /** 获取指定命名空间（账户或本地存储）的隐藏路径列表 */
    fun getHiddenPaths(key: String): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val p = normalizePath(arr.getString(i))
                if (p.isNotBlank()) set.add(p)
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 隐藏指定文件或文件夹路径 */
    fun hideItem(key: String, path: String) {
        val p = normalizePath(path)
        if (p.isBlank() || p == "/") return
        val current = getHiddenPaths(key).toMutableSet()
        current.add(p)
        saveSet(key, current)
    }

    /** 取消指定路径的隐藏 */
    fun unhideItem(key: String, path: String) {
        val p = normalizePath(path)
        val current = getHiddenPaths(key).toMutableSet()
        if (current.remove(p)) {
            saveSet(key, current)
        }
    }

    /**
     * 取消指定目录以及该目录下所有子目录中全部项目的隐藏
     * @param key 账户标识或本地标识
     * @param dirPath 目标目录路径
     * @return 被成功取消隐藏的项目总数
     */
    fun unhideUnder(key: String, dirPath: String): Int {
        val targetDir = normalizePath(dirPath)
        val current = getHiddenPaths(key).toMutableSet()
        if (current.isEmpty()) return 0

        val toRemove = current.filter { p ->
            if (targetDir == "/") {
                true // 根目录下取消全部隐藏
            } else {
                p == targetDir || p.startsWith("$targetDir/")
            }
        }.toSet()

        if (toRemove.isNotEmpty()) {
            current.removeAll(toRemove)
            saveSet(key, current)
        }
        return toRemove.size
    }

    private fun saveSet(key: String, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        fun buildWebDavKey(accountId: Long): String = "webdav_$accountId"
        const val LOCAL_STORAGE_KEY = "local_storage"

        /**
         * 规范化路径：统一使用斜杠 / 分隔，去除多余首尾斜杠（根目录保留单个 /）
         */
        fun normalizePath(path: String): String {
            var p = path.trim().replace('\\', '/')
            if (!p.startsWith("/")) p = "/$p"
            while (p.length > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length - 1)
            }
            return p
        }

        /**
         * 判断路径是否处于隐藏状态（自身被隐藏，或其任意层级的父目录被隐藏）
         * @param currentDir 当前正在浏览的目录路径。若提供了当前目录，且某个隐藏的祖先目录正是当前目录本身或当前目录的上级目录
         *                   （说明用户是通过输入路径等方式主动进入了该隐藏目录内部），则不因该祖先目录隐藏而过滤掉当前目录下的内容。
         */
        fun isHidden(path: String, hiddenSet: Set<String>, currentDir: String? = null): Boolean {
            if (hiddenSet.isEmpty()) return false
            val p = normalizePath(path)
            val cur = currentDir?.let { normalizePath(it) }

            // 1. 如果条目本身直接在 hiddenSet 中：
            if (hiddenSet.contains(p)) {
                // 如果恰好 p == cur，即检查的是当前目录自身，不应视为隐藏
                if (cur != null && p == cur) {
                    // entry 本身是当前目录
                } else {
                    return true
                }
            }

            // 2. 检查祖先目录：
            var curr = p
            while (true) {
                val idx = curr.lastIndexOf('/')
                if (idx <= 0) break
                curr = curr.substring(0, idx)
                if (hiddenSet.contains(curr)) {
                    // 如果被隐藏的祖先目录是当前目录自身，或是当前目录的上级祖先，
                    // 说明用户已经身处于该隐藏目录内部（例如通过地址栏输入进入），
                    // 此时不应因上级被隐藏而过滤当前目录内的文件！
                    if (cur != null && (cur == curr || cur.startsWith("$curr/"))) {
                        // 忽略此祖先的隐藏限制，继续向上检查
                        continue
                    }
                    return true
                }
            }
            return false
        }
    }
}
