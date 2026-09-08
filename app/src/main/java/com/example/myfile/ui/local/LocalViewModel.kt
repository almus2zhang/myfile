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
    val selected: Set<String> = emptySet(),
    val multiSelectMode: Boolean = false,
    val message: String? = null
)

class LocalViewModel : ViewModel() {
    private val repo = MyApp.instance.localRepo
    val rootDir: File = Environment.getExternalStorageDirectory()
    private val _state = MutableStateFlow(LocalUiState(currentDir = rootDir))
    val state: StateFlow<LocalUiState> = _state.asStateFlow()

    init { refresh() }

    fun isAtRoot(dir: File = _state.value.currentDir): Boolean {
        val rootCanonical = try { rootDir.canonicalPath } catch (e: Exception) { rootDir.absolutePath }
        val dirCanonical = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
        return dirCanonical == rootCanonical || !dirCanonical.startsWith(rootCanonical) || dir.parentFile == null
    }

    fun refresh() {
        val dir = _state.value.currentDir
        _state.value = _state.value.copy(files = repo.list(dir), currentDir = dir)
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            _state.value = _state.value.copy(
                currentDir = File(entry.path),
                selected = emptySet(),
                multiSelectMode = false
            )
            refresh()
        }
    }

    fun goUp() {
        if (isAtRoot()) return
        val parent = _state.value.currentDir.parentFile ?: return
        _state.value = _state.value.copy(
            currentDir = parent,
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
