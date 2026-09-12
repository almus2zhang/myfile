package com.example.myfile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.myfile.model.ViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 每个文件夹各自的视图偏好（与排序一样按目录路径记忆）
 *
 * 默认值：详细视图 / 不显示缩略图 / 不显示隐藏文件
 */
data class FolderViewPrefs(
    val viewMode: ViewMode = ViewMode.DETAILS,
    val showThumbnails: Boolean = false,
    val showHiddenFiles: Boolean = false
) {
    companion object {
        fun default() = FolderViewPrefs()

        /** 从 "MODE|0|1" 格式解析 */
        fun parse(raw: String?): FolderViewPrefs {
            if (raw.isNullOrBlank()) return default()
            val parts = raw.split("|")
            val mode = parts.getOrNull(0)?.let {
                try { ViewMode.valueOf(it) } catch (_: Exception) { null }
            } ?: ViewMode.DETAILS
            val thumbs = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
            val hidden = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
            return FolderViewPrefs(mode, thumbs, hidden)
        }
    }

    fun serialize(): String =
        "${viewMode.name}|$showThumbnails|$showHiddenFiles"
}

class ViewModeStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("view_mode_prefs", Context.MODE_PRIVATE)

    /** 当前目录的视图偏好（切换目录时由 ViewModel 调用 loadFolder 更新） */
    private val _currentFolderPrefs = MutableStateFlow(FolderViewPrefs.default())
    val currentFolderPrefs: StateFlow<FolderViewPrefs> = _currentFolderPrefs.asStateFlow()

    /** 上一次加载的目录 key */
    private var currentKey: String? = null

    /**
     * 加载指定目录的视图偏好；若该目录未单独设置过，返回默认值。
     */
    fun loadFolder(folderKey: String) {
        currentKey = folderKey
        _currentFolderPrefs.value = getFolderPrefs(folderKey)
    }

    /**
     * 获取指定目录保存的视图偏好（不切换当前目录）
     */
    fun getFolderPrefs(folderKey: String): FolderViewPrefs {
        val raw = prefs.getString(key(folderKey), null)
        return FolderViewPrefs.parse(raw)
    }

    /**
     * 保存指定目录的视图偏好，并刷新当前 flow（若正是当前目录）
     */
    fun saveFolderPrefs(folderKey: String, value: FolderViewPrefs) {
        prefs.edit().putString(key(folderKey), value.serialize()).apply()
        if (currentKey == folderKey) {
            _currentFolderPrefs.value = value
        }
    }

    /** 修改当前目录的某一项（基于已加载的 prefs） */
    private fun updateCurrent(transform: (FolderViewPrefs) -> FolderViewPrefs) {
        val key = currentKey ?: return
        val updated = transform(_currentFolderPrefs.value)
        saveFolderPrefs(key, updated)
    }

    fun setViewMode(mode: ViewMode) =
        updateCurrent { it.copy(viewMode = mode) }

    fun setShowThumbnails(show: Boolean) =
        updateCurrent { it.copy(showThumbnails = show) }

    fun toggleShowThumbnails() =
        updateCurrent { it.copy(showThumbnails = !it.showThumbnails) }

    fun setShowHiddenFiles(show: Boolean) =
        updateCurrent { it.copy(showHiddenFiles = show) }

    fun toggleShowHiddenFiles() =
        updateCurrent { it.copy(showHiddenFiles = !it.showHiddenFiles) }

    /**
     * 清除指定目录的偏好（恢复默认）
     */
    fun clearFolder(folderKey: String) {
        prefs.edit().remove(key(folderKey)).apply()
        if (currentKey == folderKey) {
            _currentFolderPrefs.value = FolderViewPrefs.default()
        }
    }

    private fun key(folderKey: String) = "view_$folderKey"

    companion object {
        /** 本地文件夹的键值 */
        fun buildLocalKey(path: String): String = "local_$path"

        /** WebDAV 账户 + 路径的键值（隔离不同账户相同路径） */
        fun buildWebDavKey(accountId: Long, path: String): String {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return "webdav_${accountId}_$normalized"
        }
    }
}
