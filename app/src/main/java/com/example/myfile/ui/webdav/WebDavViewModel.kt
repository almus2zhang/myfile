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

import com.example.myfile.model.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val pathStore = MyApp.instance.accountPathStore
    private val viewModeStore = MyApp.instance.viewModeStore

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    val viewMode: StateFlow<ViewMode> = viewModeStore.webDavViewMode

    val showThumbnailsAndDuration: StateFlow<Boolean> = viewModeStore.showThumbnailsAndDuration

    fun setViewMode(mode: ViewMode) {
        viewModeStore.setWebDavViewMode(mode)
    }

    fun setShowThumbnailsAndDuration(show: Boolean) {
        viewModeStore.setShowThumbnailsAndDuration(show)
    }

    fun toggleShowThumbnailsAndDuration() {
        val current = viewModeStore.showThumbnailsAndDuration.value
        viewModeStore.setShowThumbnailsAndDuration(!current)
    }

    private val _durationRefreshTrigger = MutableStateFlow(0)
    val durationRefreshTrigger: StateFlow<Int> = _durationRefreshTrigger.asStateFlow()

    fun forceRefreshDurations() {
        if (!viewModeStore.showThumbnailsAndDuration.value) {
            viewModeStore.setShowThumbnailsAndDuration(true)
        }
        _durationRefreshTrigger.value += 1
    }

    private fun getFolderSort(accId: Long, path: String): Pair<SortMode, Boolean> {
        val folderKey = com.example.myfile.data.prefs.FolderSortStore.buildWebDavKey(accId, path)
        return MyApp.instance.folderSortStore.getSort(folderKey) ?: (SortMode.NAME to true)
    }

    init {
        viewModelScope.launch {
            var initialized = false
            accountStore.accounts.collect { list ->
                val cur = _state.value.currentAccount ?: list.firstOrNull()
                val prev = _state.value
                val initialPath = if (cur != null) pathStore.getLastPath(cur.id) else "/"
                val (mode, asc) = if (cur != null) getFolderSort(cur.id, initialPath) else (SortMode.NAME to true)
                _state.value = prev.copy(accounts = list, currentAccount = cur, currentPath = initialPath, sortMode = mode, sortAsc = asc)
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
        // 保存当前账户路径
        _state.value.currentAccount?.let {
            pathStore.saveLastPath(it.id, _state.value.currentPath)
        }
        // 恢复目标账户上次打开的路径
        val targetPath = pathStore.getLastPath(account.id)
        val (mode, asc) = getFolderSort(account.id, targetPath)
        _state.value = _state.value.copy(
            currentAccount = account,
            currentPath = targetPath,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            _state.value.currentAccount?.let {
                pathStore.saveLastPath(it.id, entry.path)
            }
            val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, entry.path) }
                ?: (SortMode.NAME to true)
            _state.value = _state.value.copy(
                currentPath = entry.path,
                sortMode = mode,
                sortAsc = asc,
                selected = emptySet(),
                multiSelectMode = false
            )
            refresh()
        }
    }

    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        scrollPositions[path] = index to offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? = scrollPositions[path]

    fun goUp() {
        val path = _state.value.currentPath.trimEnd('/')
        if (path.isEmpty() || path == "/") return
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        _state.value.currentAccount?.let {
            pathStore.saveLastPath(it.id, parent)
        }
        val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, parent) }
            ?: (SortMode.NAME to true)
        _state.value = _state.value.copy(
            currentPath = parent,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    /** 面包屑跳转到指定路径 */
    fun navigateTo(path: String) {
        val normalized = path.trim().ifEmpty { "/" }
        _state.value.currentAccount?.let {
            pathStore.saveLastPath(it.id, normalized)
        }
        val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, normalized) }
            ?: (SortMode.NAME to true)
        _state.value = _state.value.copy(
            currentPath = normalized,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    /** 流式读取文本文件 */
    suspend fun streamDownloadText(
        path: String,
        onProgress: (loadedBytes: Long, totalBytes: Long, partialText: String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val acc = _state.value.currentAccount ?: throw IllegalStateException("无有效账户")
        val resp = repo.download(acc, path)
        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}: ${resp.message}")
        val body = resp.body ?: throw java.io.IOException("响应体为空")
        val total = body.contentLength()
        val inputStream = body.byteStream()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8))
        val sb = StringBuilder()
        val buf = CharArray(16384)
        var readChars: Int
        var loadedBytes = 0L
        var lastReportTime = 0L
        val maxChars = 2_000_000 // 2MB 保护

        try {
            while (reader.read(buf).also { readChars = it } != -1) {
                sb.append(buf, 0, readChars)
                loadedBytes += readChars
                val now = System.currentTimeMillis()
                if (now - lastReportTime > 150) {
                    lastReportTime = now
                    onProgress(loadedBytes, total, sb.toString())
                }
                if (sb.length > maxChars) {
                    sb.append("\n\n--- [文件过大，已自动截断前 2MB 内容] ---")
                    break
                }
            }
        } finally {
            body.close()
        }
        val full = sb.toString()
        onProgress(loadedBytes, total, full)
        full
    }

    /** 保存文本文件到 WebDAV */
    suspend fun saveText(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val acc = _state.value.currentAccount ?: return@withContext false
        repo.upload(acc, path, content.toByteArray(Charsets.UTF_8))
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
            message = null
        )
    }

    fun cutSelected() {
        val acc = _state.value.currentAccount ?: return
        val curFiles = _state.value.files.associateBy { it.path }
        val items = _state.value.selected.mapNotNull { p ->
            curFiles[p]?.let { entry ->
                com.example.myfile.core.ClipboardEntry(entry = entry, account = acc)
            }
        }
        com.example.myfile.core.TransferClipboard.cut(items)
        _state.value = _state.value.copy(
            selected = emptySet(),
            multiSelectMode = false,
            message = null
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

    fun pasteHere(context: android.content.Context, onDone: (() -> Unit)? = null) {
        val acc = _state.value.currentAccount ?: return
        val items = com.example.myfile.core.TransferClipboard.items.value
        if (items.isEmpty()) return
        val isCut = com.example.myfile.core.TransferClipboard.isCut
        val targetPath = _state.value.currentPath
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            var count = 0
            try {
                count = com.example.myfile.core.TransferOps.pasteToWebDav(context, acc, targetPath, items, isCut = isCut)
            } catch (e: Exception) {
                android.util.Log.e("WebDavVM", "pasteHere error", e)
            } finally {
                com.example.myfile.core.TransferClipboard.clear()
                _state.value = _state.value.copy(
                    loading = false,
                    selected = emptySet(),
                    multiSelectMode = false,
                    message = if (isCut) "已移动 $count 项" else "已粘贴 $count 项"
                )
                onDone?.invoke()
                refresh()
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 直接设置排序字段与升降序并记忆当前目录的设置 */
    fun setSort(mode: SortMode, asc: Boolean) {
        val acc = _state.value.currentAccount
        if (acc != null) {
            val folderKey = com.example.myfile.data.prefs.FolderSortStore.buildWebDavKey(acc.id, _state.value.currentPath)
            MyApp.instance.folderSortStore.saveSort(folderKey, mode, asc)
        }
        _state.value = _state.value.copy(sortMode = mode, sortAsc = asc)
    }

    /** 切换排序字段；若点击同一字段则翻转方向，否则升序 */
    fun changeSort(mode: SortMode) {
        val cur = _state.value
        val newAsc = if (cur.sortMode == mode) !cur.sortAsc else true
        setSort(mode, newAsc)
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

    fun rename(entry: FileEntry, newName: String) {
        val acc = _state.value.currentAccount ?: return
        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val dir = p.substringBeforeLast('/', "")
        val targetPath = if (dir.isEmpty()) "/$newName" else "$dir/$newName"
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                val ok = repo.rename(acc, p, targetPath)
                if (ok) {
                    _state.value = _state.value.copy(loading = false, message = "重命名成功")
                    refresh()
                } else {
                    _state.value = _state.value.copy(loading = false, error = "重命名失败")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = "重命名失败: ${e.message}")
            }
        }
    }
}
