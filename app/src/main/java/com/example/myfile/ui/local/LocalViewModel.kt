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
    val isRefreshing: Boolean = false
) {
    val sortedFiles: List<FileEntry>
        get() {
            val dirs = files.filter { it.isDirectory }
            val fs = files.filter { !it.isDirectory }
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
    val rootDir: File = Environment.getExternalStorageDirectory()
    private val _state = MutableStateFlow(LocalUiState(currentDir = rootDir))
    val state: StateFlow<LocalUiState> = _state.asStateFlow()

    private fun getFolderSort(path: String): Pair<com.example.myfile.ui.webdav.SortMode, Boolean> {
        val folderKey = com.example.myfile.data.prefs.FolderSortStore.buildLocalKey(path)
        return MyApp.instance.folderSortStore.getSort(folderKey) ?: (com.example.myfile.ui.webdav.SortMode.NAME to true)
    }

    init {
        val (mode, asc) = getFolderSort(rootDir.absolutePath)
        _state.value = _state.value.copy(sortMode = mode, sortAsc = asc)
        refresh()
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
        return viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _state.value = _state.value.copy(isRefreshing = true)
            val list = repo.list(dir)
            _state.value = _state.value.copy(files = list, currentDir = dir, isRefreshing = false)
        }
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            val (mode, asc) = getFolderSort(entry.path)
            _state.value = _state.value.copy(
                currentDir = File(entry.path),
                sortMode = mode,
                sortAsc = asc,
                selected = emptySet(),
                multiSelectMode = false
            )
            refresh()
        }
    }

    fun goUp() {
        if (isAtRoot()) return
        val parent = _state.value.currentDir.parentFile ?: return
        val (mode, asc) = getFolderSort(parent.absolutePath)
        _state.value = _state.value.copy(
            currentDir = parent,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false
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
            message = "已复制 ${items.size} 项，可在任意目录粘贴"
        )
    }

    fun pasteHere(context: android.content.Context) {
        val items = com.example.myfile.core.TransferClipboard.items.value
        if (items.isEmpty()) return
        val targetDir = _state.value.currentDir
        viewModelScope.launch {
            val count = com.example.myfile.core.TransferOps.pasteToLocal(context, targetDir, items)
            _state.value = _state.value.copy(message = "已粘贴 $count 项")
            refresh()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun newFolder(name: String) {
        viewModelScope.launch {
            val ok = repo.mkdir(_state.value.currentDir, name)
            _state.value = _state.value.copy(message = if (ok) "已创建" else "创建失败")
            refresh()
        }
    }

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
            repo.rename(File(entry.path), newName)
            refresh()
        }
    }
}
