package com.example.myfile.ui.local

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class LocalUiState(
    val currentDir: File = File(Environment.getExternalStorageDirectory().absolutePath),
    val files: List<FileEntry> = emptyList(),
    val sortMode: com.example.myfile.ui.webdav.SortMode = com.example.myfile.ui.webdav.SortMode.NAME,
    val sortAsc: Boolean = true,
    val selected: Set<String> = emptySet(),
    val multiSelectMode: Boolean = false,
    val message: String? = null,
    val isRefreshing: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val hiddenPaths: Set<String> = emptySet()
) {
    val sortedFiles: List<FileEntry>
        get() {
            // 不显示隐藏文件时过滤掉以 . 开头的条目，以及通过连击隐藏的项目
            val visible = files
                .filter { if (showHiddenFiles) true else !it.name.startsWith(".") }
                .filter { !com.example.myfile.data.prefs.HiddenItemStore.isHidden(it.path, hiddenPaths) }
            val dirs = visible.filter { it.isDirectory }
            val fs = visible.filter { !it.isDirectory }
            val cmp: Comparator<FileEntry> = when (sortMode) {
                com.example.myfile.ui.webdav.SortMode.NAME -> compareBy { it.name.lowercase() }
                com.example.myfile.ui.webdav.SortMode.SIZE -> compareBy { it.size }
                com.example.myfile.ui.webdav.SortMode.MODIFIED -> compareBy { it.lastModified }
                com.example.myfile.ui.webdav.SortMode.TYPE -> compareBy<FileEntry> { it.name.substringAfterLast('.', "").lowercase() }
                    .thenBy { it.name.lowercase() }
            }
            val ordered = if (sortAsc) cmp else cmp.reversed()
            return dirs.sortedWith(ordered) + fs.sortedWith(ordered)
        }
}

class LocalViewModel : ViewModel() {
    private val repo = MyApp.instance.localRepo
    private val hiddenItemStore = MyApp.instance.hiddenItemStore
    val rootDir: File = Environment.getExternalStorageDirectory()
    private val _state = MutableStateFlow(LocalUiState(currentDir = rootDir))
    val state: StateFlow<LocalUiState> = _state.asStateFlow()

    private var refreshJob: kotlinx.coroutines.Job? = null

    private val viewModeStore = MyApp.instance.viewModeStore

    /** 当前目录的视图偏好（视图/缩略图/隐藏文件/间隔线），按目录路径记忆 */
    val currentFolderPrefs: StateFlow<com.example.myfile.data.prefs.FolderViewPrefs> =
        viewModeStore.currentFolderPrefs

    val viewMode: StateFlow<com.example.myfile.model.ViewMode> = MutableStateFlow(
        viewModeStore.currentFolderPrefs.value.viewMode
    ).also { flow ->
        viewModelScope.launch {
            viewModeStore.currentFolderPrefs.collect { flow.value = it.viewMode }
        }
    }

    fun setViewMode(mode: com.example.myfile.model.ViewMode) {
        viewModeStore.setViewMode(mode)
    }

    val showThumbnailsAndDuration: StateFlow<Boolean> = MutableStateFlow(
        viewModeStore.currentFolderPrefs.value.showThumbnails
    ).also { flow ->
        viewModelScope.launch {
            viewModeStore.currentFolderPrefs.collect { flow.value = it.showThumbnails }
        }
    }

    fun setShowThumbnailsAndDuration(show: Boolean) {
        viewModeStore.setShowThumbnails(show)
    }

    fun toggleShowThumbnailsAndDuration() {
        viewModeStore.toggleShowThumbnails()
    }

    val showHiddenFilesFlow: StateFlow<Boolean> = MutableStateFlow(
        viewModeStore.currentFolderPrefs.value.showHiddenFiles
    ).also { flow ->
        viewModelScope.launch {
            viewModeStore.currentFolderPrefs.collect {
                flow.value = it.showHiddenFiles
                if (_state.value.showHiddenFiles != it.showHiddenFiles) {
                    _state.value = _state.value.copy(showHiddenFiles = it.showHiddenFiles)
                }
            }
        }
    }

    fun setShowHiddenFiles(show: Boolean) {
        viewModeStore.setShowHiddenFiles(show)
        _state.value = _state.value.copy(showHiddenFiles = show)
    }

    fun toggleShowHiddenFiles() {
        viewModeStore.toggleShowHiddenFiles()
    }

    private val _durationRefreshTrigger = MutableStateFlow(0)
    val durationRefreshTrigger: StateFlow<Int> = _durationRefreshTrigger.asStateFlow()

    fun forceRefreshDurations() {
        if (!viewModeStore.currentFolderPrefs.value.showThumbnails) {
            viewModeStore.setShowThumbnails(true)
        }
        _durationRefreshTrigger.value += 1
    }

    /** 加载指定目录的视图偏好 */
    private fun loadFolderPrefs(path: String) {
        val folderKey = com.example.myfile.data.prefs.ViewModeStore.buildLocalKey(path)
        viewModeStore.loadFolder(folderKey)
    }

    private fun getFolderSort(path: String): Pair<com.example.myfile.ui.webdav.SortMode, Boolean> {
        val folderKey = com.example.myfile.data.prefs.FolderSortStore.buildLocalKey(path)
        return MyApp.instance.folderSortStore.getSort(folderKey) ?: (com.example.myfile.ui.webdav.SortMode.NAME to true)
    }

    init {
        val (mode, asc) = getFolderSort(rootDir.absolutePath)
        val hiddenSet = hiddenItemStore.getHiddenPaths(com.example.myfile.data.prefs.HiddenItemStore.LOCAL_STORAGE_KEY)
        _state.value = _state.value.copy(sortMode = mode, sortAsc = asc, hiddenPaths = hiddenSet)
        // 加载根目录的视图偏好
        loadFolderPrefs(rootDir.absolutePath)
        _state.value = _state.value.copy(
            showHiddenFiles = viewModeStore.currentFolderPrefs.value.showHiddenFiles
        )
        refresh()
    }

    /** 隐藏单个路径（文件夹或文件） */
    fun hideItem(path: String) {
        val key = com.example.myfile.data.prefs.HiddenItemStore.LOCAL_STORAGE_KEY
        hiddenItemStore.hideItem(key, path)
        _state.value = _state.value.copy(hiddenPaths = hiddenItemStore.getHiddenPaths(key))
    }

    /** 取消当前目录及其所有子目录下全部隐藏项目 */
    fun unhideUnder(dirPath: String): Int {
        val key = com.example.myfile.data.prefs.HiddenItemStore.LOCAL_STORAGE_KEY
        val count = hiddenItemStore.unhideUnder(key, dirPath)
        if (count > 0) {
            _state.value = _state.value.copy(hiddenPaths = hiddenItemStore.getHiddenPaths(key))
        }
        return count
    }

    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        scrollPositions[path] = index to offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? = scrollPositions[path]

    fun setSort(mode: com.example.myfile.ui.webdav.SortMode, asc: Boolean) {
        val folderKey = com.example.myfile.data.prefs.FolderSortStore.buildLocalKey(_state.value.currentDir.absolutePath)
        MyApp.instance.folderSortStore.saveSort(folderKey, mode, asc)
        _state.value = _state.value.copy(sortMode = mode, sortAsc = asc)
    }

    fun changeSort(mode: com.example.myfile.ui.webdav.SortMode) {
        val cur = _state.value
        val newAsc = if (cur.sortMode == mode) !cur.sortAsc else true
        setSort(mode, newAsc)
    }

    fun isAtRoot(dir: File = _state.value.currentDir): Boolean {
        val rootCanonical = try { rootDir.canonicalPath } catch (e: Exception) { rootDir.absolutePath }
        val dirCanonical = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
        return dirCanonical == rootCanonical || !dirCanonical.startsWith(rootCanonical) || dir.parentFile == null
    }

    fun refresh(): kotlinx.coroutines.Job {
        val dir = _state.value.currentDir
        refreshJob?.cancel()
        val job = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _state.value = _state.value.copy(isRefreshing = true)
            try {
                val list = repo.list(dir)
                if (_state.value.currentDir == dir) {
                    _state.value = _state.value.copy(files = list, currentDir = dir, isRefreshing = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_state.value.currentDir == dir) {
                    _state.value = _state.value.copy(isRefreshing = false)
                }
            }
        }
        refreshJob = job
        return job
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            refreshJob?.cancel()
            val (mode, asc) = getFolderSort(entry.path)
            loadFolderPrefs(entry.path)
            _state.value = _state.value.copy(
                currentDir = File(entry.path),
                sortMode = mode,
                sortAsc = asc,
                selected = emptySet(),
                multiSelectMode = false,
                files = emptyList(),
                isRefreshing = true
            )
            refresh()
        }
    }

    fun navigateTo(dir: File) {
        refreshJob?.cancel()
        val (mode, asc) = getFolderSort(dir.absolutePath)
        loadFolderPrefs(dir.absolutePath)
        _state.value = _state.value.copy(
            currentDir = dir,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false,
            files = emptyList(),
            isRefreshing = true
        )
        refresh()
    }

    fun goUp() {
        if (isAtRoot()) return
        val parent = _state.value.currentDir.parentFile ?: return
        refreshJob?.cancel()
        val (mode, asc) = getFolderSort(parent.absolutePath)
        loadFolderPrefs(parent.absolutePath)
        _state.value = _state.value.copy(
            currentDir = parent,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false,
            files = emptyList(),
            isRefreshing = true
        )
        refresh()
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false
        )
    }

    fun toggleSelect(path: String) {
        val cur = _state.value
        val sel = if (path in cur.selected) cur.selected - path else cur.selected + path
        _state.value = cur.copy(selected = sel, multiSelectMode = sel.isNotEmpty())
    }

    fun selectAll() {
        val allPaths = _state.value.files.map { it.path }.toSet()
        _state.value = _state.value.copy(
            selected = allPaths,
            multiSelectMode = allPaths.isNotEmpty()
        )
    }

    fun copySelected() {
        val curFiles = _state.value.files.associateBy { it.path }
        val items = _state.value.selected.mapNotNull { p ->
            curFiles[p]?.let { entry ->
                com.example.myfile.core.ClipboardEntry(entry = entry, account = null)
            }
        }
        com.example.myfile.core.TransferClipboard.copy(items)
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false,
            message = null
        )
    }

    fun cutSelected() {
        val curFiles = _state.value.files.associateBy { it.path }
        val items = _state.value.selected.mapNotNull { p ->
            curFiles[p]?.let { entry ->
                com.example.myfile.core.ClipboardEntry(entry = entry, account = null)
            }
        }
        com.example.myfile.core.TransferClipboard.cut(items)
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false,
            message = null
        )
    }

    fun pasteHere(context: android.content.Context, onDone: (() -> Unit)? = null) {
        val items = com.example.myfile.core.TransferClipboard.items.value
        if (items.isEmpty()) return
        val isCut = com.example.myfile.core.TransferClipboard.isCut
        val targetDir = _state.value.currentDir
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            var count = 0
            try {
                count = com.example.myfile.core.TransferOps.pasteToLocal(context, targetDir, items, isCut = isCut)
            } catch (e: Exception) {
                android.util.Log.e("LocalVM", "pasteHere error", e)
            } finally {
                com.example.myfile.core.TransferClipboard.clear()
                _state.value = _state.value.copy(
                    selected = emptySet(),
                    multiSelectMode = false,
                    isRefreshing = false,
                    message = if (count > 0) (if (isCut) "已移动 $count 项" else "已粘贴 $count 项") else "粘贴失败或未移动任何文件"
                )
                onDone?.invoke()
                refresh()
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun mkdir(name: String) {
        viewModelScope.launch {
            val ok = repo.mkdir(_state.value.currentDir, name)
            _state.value = _state.value.copy(message = if (ok) "已创建" else "创建失败")
            refresh()
        }
    }

    fun newFolder(name: String) = mkdir(name)

    fun deleteSelected() {
        viewModelScope.launch {
            _state.value.selected.forEach { p -> repo.delete(File(p)) }
            _state.value = _state.value.copy(selected = emptySet(), multiSelectMode = false)
            refresh()
        }
    }

    fun deleteOne(entry: FileEntry) {
        viewModelScope.launch {
            repo.delete(File(entry.path))
            refresh()
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        viewModelScope.launch {
            if (entry.path.startsWith("content://")) {
                try {
                    val uri = android.net.Uri.parse(entry.path)
                    val df = androidx.documentfile.provider.DocumentFile.fromSingleUri(MyApp.instance, uri)
                        ?: androidx.documentfile.provider.DocumentFile.fromTreeUri(MyApp.instance, uri)
                    df?.renameTo(newName)
                } catch (_: Exception) {}
            } else {
                repo.rename(File(entry.path), newName)
            }
            refresh()
        }
    }

    /** 创建本地文件/文件夹副本 */
    fun createDuplicate(entry: FileEntry) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val srcFile = File(entry.path)
                if (!srcFile.exists()) return@launch
                val parentDir = srcFile.parentFile ?: return@launch
                val existingNames = parentDir.list()?.toSet() ?: emptySet()
                val duplicateName = com.example.myfile.core.DuplicateNameHelper.generate(entry.name, existingNames)
                val destFile = File(parentDir, duplicateName)
                if (srcFile.isDirectory) {
                    srcFile.copyRecursively(destFile, overwrite = false)
                } else {
                    srcFile.copyTo(destFile, overwrite = false)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _state.value = _state.value.copy(message = "已创建副本：$duplicateName")
                    refresh()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _state.value = _state.value.copy(message = "创建副本失败：${e.message}")
                }
            }
        }
    }
}
