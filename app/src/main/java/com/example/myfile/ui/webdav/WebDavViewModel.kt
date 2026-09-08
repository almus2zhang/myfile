package com.example.myfile.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.example.myfile.MyApp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.WebDavAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WebDavUiState(
    val accounts: List<WebDavAccount> = emptyList(),
    val currentAccount: WebDavAccount? = null,
    val currentPath: String = "/",
    val files: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sortMode: SortMode = SortMode.NAME,
    val sortAsc: Boolean = true,
    val selected: Set<String> = emptySet(),
    val multiSelectMode: Boolean = false,
    val message: String? = null
) {
    /** 排序后的文件列表：目录始终在前，然后按选定字段排序 */
    val sortedFiles: List<FileEntry>
        get() {
            val dirs = files.filter { it.isDirectory }
            val fs = files.filter { !it.isDirectory }
            val cmp: Comparator<FileEntry> = when (sortMode) {
                SortMode.NAME -> compareBy { it.name.lowercase() }
                SortMode.SIZE -> compareBy { it.size }
                SortMode.MODIFIED -> compareBy { it.lastModified }
                SortMode.TYPE -> compareBy<FileEntry> { it.name.substringAfterLast('.', "").lowercase() }
                    .thenBy { it.name.lowercase() }
            }
            val ordered = if (sortAsc) cmp else cmp.reversed()
            return dirs.sortedWith(ordered) + fs.sortedWith(ordered)
        }
}

enum class SortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    MODIFIED("时间"),
    TYPE("类型")
}

class WebDavViewModel : ViewModel() {
    private val accountStore = MyApp.instance.accountStore
    private val repo = MyApp.instance.webDavRepository
    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var initialized = false
            accountStore.accounts.collect { list ->
                val cur = _state.value.currentAccount ?: list.firstOrNull()
                val prev = _state.value
                _state.value = prev.copy(accounts = list, currentAccount = cur)
                // 仅当从未加载过账户或账户列表发生变化时才自动 refresh，避免 init 死循环
                if (!initialized && cur != null) {
                    initialized = true
                    refresh()
                } else if (initialized && cur != null && prev.accounts != list) {
                    refresh()
                }
            }
        }
    }

    fun selectAccount(account: WebDavAccount) {
        _state.value = _state.value.copy(
            currentAccount = account,
            currentPath = "/",
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            _state.value = _state.value.copy(
                currentPath = entry.path,
                selected = emptySet(),
                multiSelectMode = false
            )
            refresh()
        }
    }

    fun goUp() {
        val path = _state.value.currentPath.trimEnd('/')
        if (path.isEmpty() || path == "/") return
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        _state.value = _state.value.copy(
            currentPath = parent,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    /** 面包屑跳转到指定路径 */
    fun navigateTo(path: String) {
        val normalized = path.trim().ifEmpty { "/" }
        _state.value = _state.value.copy(
            currentPath = normalized,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
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

    fun clearSelection() {
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false
        )
    }

    fun copySelected() {
        val acc = _state.value.currentAccount ?: return
        val curFiles = _state.value.files.associateBy { it.path }
        val items = _state.value.selected.mapNotNull { p ->
            curFiles[p]?.let { entry ->
                com.example.myfile.core.ClipboardEntry(entry = entry, account = acc)
            }
        }
        com.example.myfile.core.TransferClipboard.copy(items)
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false,
            message = "已复制 ${items.size} 项，可在任意目录粘贴"
        )
    }

    fun deleteSelected() {
        val acc = _state.value.currentAccount ?: return
        val paths = _state.value.selected.toList()
        viewModelScope.launch {
            for (p in paths) {
                try { repo.delete(acc, p) } catch (_: Exception) {}
            }
            _state.value = _state.value.copy(selected = emptySet(), multiSelectMode = false)
            refresh()
        }
    }

    fun pasteHere(context: android.content.Context) {
        val acc = _state.value.currentAccount ?: return
        val items = com.example.myfile.core.TransferClipboard.items.value
        if (items.isEmpty()) return
        val targetPath = _state.value.currentPath
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val count = com.example.myfile.core.TransferOps.pasteToWebDav(context, acc, targetPath, items)
            _state.value = _state.value.copy(loading = false, message = "已粘贴 $count 项")
            refresh()
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 直接设置排序字段与升降序 */
    fun setSort(mode: SortMode, asc: Boolean) {
        _state.value = _state.value.copy(sortMode = mode, sortAsc = asc)
    }

    /** 切换排序字段；若点击同一字段则翻转方向，否则升序 */
    fun changeSort(mode: SortMode) {
        val cur = _state.value
        if (cur.sortMode == mode) {
            _state.value = cur.copy(sortAsc = !cur.sortAsc)
        } else {
            _state.value = cur.copy(sortMode = mode, sortAsc = true)
        }
    }

    fun refresh(): kotlinx.coroutines.Job {
        val acc = _state.value.currentAccount ?: return viewModelScope.launch {}
        val path = _state.value.currentPath
        return viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val files = repo.list(acc, path)
                _state.value = _state.value.copy(files = files, loading = false)
            } catch (e: Exception) {
                Log.e("WebDavVM", "refresh failed", e)
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _state.value = _state.value.copy(loading = false, error = "连接失败: $msg")
            }
        }
    }

    fun saveAccount(account: WebDavAccount) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // 先保存（不论测试结果），保证配置持久化
            val list = _state.value.accounts.toMutableList()
            var saved: WebDavAccount = if (account.id == 0L) {
                val newId = (list.maxOfOrNull { it.id } ?: 0) + 1
                account.copy(id = newId).also { list.add(it) }
            } else {
                val idx = list.indexOfFirst { it.id == account.id }
                if (idx >= 0) { list[idx] = account; account } else { list.add(account); account }
            }
            accountStore.save(list)
            _state.value = _state.value.copy(
                currentAccount = saved, currentPath = "/", accounts = list, loading = false
            )
            // 然后异步测试连接
            val testRes = repo.testConnection(saved)
            if (!testRes.ok) {
                _state.value = _state.value.copy(error = "保存成功，但连接失败: ${testRes.message}")
            } else {
                // 若探测到更准确端点：如果是动态类别，更新 resolvedUrl（保留原始 url）；普通类别才修正 url
                val detected = Regex("""探测到正确路径:\s*(\S+)""").find(testRes.message)
                val newEndpoint = if (detected != null) {
                    detected.groupValues[1]
                } else if (testRes.resolvedUrl.isNotBlank()) {
                    testRes.resolvedUrl
                } else null

                if (newEndpoint != null) {
                    val idx = list.indexOfFirst { it.id == saved.id }
                    if (idx >= 0) {
                        if (saved.isDynamic) {
                            list[idx] = saved.copy(resolvedUrl = newEndpoint)
                        } else if (newEndpoint != saved.url) {
                            list[idx] = saved.copy(url = newEndpoint)
                        }
                        accountStore.save(list)
                        saved = list[idx]
                        _state.value = _state.value.copy(currentAccount = saved, accounts = list)
                    }
                }
                refresh()
            }
        }
    }

    fun reResolveAccount(account: WebDavAccount) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val fresh = repo.reResolveAndSave(account)
            if (fresh.isNotBlank()) {
                _state.value = _state.value.copy(message = "已重新获取端点: $fresh")
                refresh()
            } else {
                _state.value = _state.value.copy(loading = false, error = "重新获取端点失败，请检查原始网址")
            }
        }
    }

    fun deleteAccount(account: WebDavAccount) {
        viewModelScope.launch {
            val list = _state.value.accounts.filter { it.id != account.id }
            accountStore.save(list)
        }
    }

    fun mkdir(name: String) {
        val acc = _state.value.currentAccount ?: return
        val path = _state.value.currentPath.trimEnd('/') + "/" + name
        viewModelScope.launch {
            try {
                repo.mkdir(acc, path)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun delete(entry: FileEntry) {
        val acc = _state.value.currentAccount ?: return
        viewModelScope.launch {
            try {
                repo.delete(acc, entry.path)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun downloadFile(account: WebDavAccount, entry: FileEntry) {
        viewModelScope.launch {
            try {
                com.example.myfile.core.DownloadLog.clear()
                com.example.myfile.core.DownloadLog.log("Download", "=== 开始下载 ${entry.name}, size=${entry.size}, path=${entry.path} ===")
                val mgr = MyApp.instance.downloadManager
                com.example.myfile.core.download.DownloadService.start(MyApp.instance)
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "myfile"
                )
                if (!dir.exists()) dir.mkdirs()
                mgr.startDownload(account, entry.path, entry.name, dir, knownSize = entry.size)
            } catch (e: Exception) {
                Log.e("WebDavVM", "downloadFile failed", e)
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _state.value = _state.value.copy(error = "下载启动失败: $msg")
            }
        }
    }
}
