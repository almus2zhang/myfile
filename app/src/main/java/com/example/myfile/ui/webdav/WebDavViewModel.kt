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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val message: String? = null,
    val showHiddenFiles: Boolean = false
) {
    /** 排序后的文件列表：目录始终在前，然后按选定字段排序（隐藏文件可选过滤） */
    val sortedFiles: List<FileEntry>
        get() = sortEntries(files)

    /**
     * 对任意条目列表应用当前的隐藏文件过滤 + 排序规则。
     * 供主列表与搜索结果共用，保证搜索结果的排序行为与主视图一致。
     */
    fun sortEntries(source: List<FileEntry>): List<FileEntry> {
        // 不显示隐藏文件时过滤掉以 . 开头的条目
        val visible = if (showHiddenFiles) source else source.filter { !it.name.startsWith(".") }
        val dirs = visible.filter { it.isDirectory }
        val fs = visible.filter { !it.isDirectory }
        val cmp = comparator()
        val ordered = if (sortAsc) cmp else cmp.reversed()
        return dirs.sortedWith(ordered) + fs.sortedWith(ordered)
    }

    private fun comparator(): Comparator<FileEntry> = when (sortMode) {
        SortMode.NAME -> compareBy { it.name.lowercase() }
        SortMode.SIZE -> compareBy { it.size }
        SortMode.MODIFIED -> compareBy { it.lastModified }
        SortMode.TYPE -> compareBy<FileEntry> { it.name.substringAfterLast('.', "").lowercase() }
            .thenBy { it.name.lowercase() }
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

    /** 当前目录的视图偏好（视图/缩略图/隐藏文件/间隔线），按目录路径记忆 */
    val currentFolderPrefs: StateFlow<com.example.myfile.data.prefs.FolderViewPrefs> =
        viewModeStore.currentFolderPrefs

    /** 视图模式（来自当前目录偏好） */
    val viewMode: StateFlow<ViewMode> = MutableStateFlow(
        viewModeStore.currentFolderPrefs.value.viewMode
    ).also { flow ->
        viewModelScope.launch {
            viewModeStore.currentFolderPrefs.collect { flow.value = it.viewMode }
        }
    }

    val showThumbnailsAndDuration: StateFlow<Boolean> = MutableStateFlow(
        viewModeStore.currentFolderPrefs.value.showThumbnails
    ).also { flow ->
        viewModelScope.launch {
            viewModeStore.currentFolderPrefs.collect { flow.value = it.showThumbnails }
        }
    }

    /** 加载指定账户+路径的视图偏好 */
    private fun loadFolderPrefs(accId: Long, path: String) {
        val folderKey = com.example.myfile.data.prefs.ViewModeStore.buildWebDavKey(accId, path)
        viewModeStore.loadFolder(folderKey)
    }

    fun setViewMode(mode: ViewMode) {
        viewModeStore.setViewMode(mode)
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
                val initialPath = if (cur != null && cur.rememberLastPath) pathStore.getLastPath(cur.id) else "/"
                val (mode, asc) = if (cur != null) getFolderSort(cur.id, initialPath) else (SortMode.NAME to true)
                // 加载该目录的视图偏好
                if (cur != null) loadFolderPrefs(cur.id, initialPath)
                _state.value = prev.copy(accounts = list, currentAccount = cur, currentPath = initialPath, sortMode = mode, sortAsc = asc)
                // 仅当从未加载过账户或账户列表发生变化时才自动 refresh，避免 init 死循环
                if (!initialized && cur != null) {
                    initialized = true
                    refresh()
                    checkAutoSyncIndex(cur)
                } else if (initialized && cur != null && prev.accounts != list) {
                    refresh()
                    checkAutoSyncIndex(cur)
                }
            }
        }
    }

    private fun checkAutoSyncIndex(account: WebDavAccount) {
        // 先从本地磁盘秒级预热索引
        com.example.myfile.core.WebDavIndex.warmupFromDisk(account.id)?.let {
            _indexTotal.value = it.size
        }
        // 若开启了自动下载且配置了索引路径，在后台自动检查更新
        if (account.autoDownloadIndex && account.indexPath.isNotBlank()) {
            syncIndex(account, forceRefresh = false)
        }
    }

    fun selectAccount(account: WebDavAccount) {
        // 保存当前账户路径（仅当该账户开启了「记住上次路径」）
        _state.value.currentAccount?.let {
            if (it.rememberLastPath) {
                pathStore.saveLastPath(it.id, _state.value.currentPath)
            }
        }
        // 恢复目标账户上次打开的路径（未开启则回到根目录）
        val targetPath = if (account.rememberLastPath) pathStore.getLastPath(account.id) else "/"
        val (mode, asc) = getFolderSort(account.id, targetPath)
        loadFolderPrefs(account.id, targetPath)
        // 先清空文件列表、停止loading（避免转圈残留），再触发新的刷新
        _state.value = _state.value.copy(
            currentAccount = account,
            currentPath = targetPath,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false,
            files = emptyList(),
            loading = false,
            error = null
        )
        refresh()
        checkAutoSyncIndex(account)
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            _state.value.currentAccount?.let {
                if (it.rememberLastPath) pathStore.saveLastPath(it.id, entry.path)
            }
            val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, entry.path) }
                ?: (SortMode.NAME to true)
            _state.value.currentAccount?.let { loadFolderPrefs(it.id, entry.path) }
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
            if (it.rememberLastPath) pathStore.saveLastPath(it.id, parent)
        }
        val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, parent) }
            ?: (SortMode.NAME to true)
        _state.value.currentAccount?.let { loadFolderPrefs(it.id, parent) }
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
            if (it.rememberLastPath) pathStore.saveLastPath(it.id, normalized)
        }
        val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, normalized) }
            ?: (SortMode.NAME to true)
        _state.value.currentAccount?.let { loadFolderPrefs(it.id, normalized) }
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
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit
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
                if (now - lastReportTime > 100) {
                    lastReportTime = now
                    onProgress(loadedBytes, total)
                }
                if (sb.length > maxChars) {
                    sb.append("\n\n--- [文件过大，已自动截断前 2MB 内容] ---")
                    break
                }
            }
        } finally {
            try { body.close() } catch (_: Exception) {}
        }
        onProgress(loadedBytes, total)
        sb.toString()
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

    /** 复制单个文件/文件夹到剪贴板（用于「更多」菜单的复制操作） */
    fun copyOne(entry: FileEntry) {
        val acc = _state.value.currentAccount ?: return
        com.example.myfile.core.TransferClipboard.copy(
            listOf(com.example.myfile.core.ClipboardEntry(entry = entry, account = acc))
        )
        _state.value = _state.value.copy(
            message = "已复制 \"${entry.name}\"，可到目标目录粘贴"
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
                // 区分直连失败和跳转地址不可达
                val errorMsg = if (acc.isDynamic && acc.resolvedUrl.isNotBlank()) {
                    "输入地址可达，但跳转地址 ${acc.resolvedUrl} 无法连接: $msg"
                } else {
                    "无法连接 ${acc.url}，请检查网络或配置: $msg"
                }
                _state.value = _state.value.copy(loading = false, error = errorMsg)
            }
        }
    }

    // ---------- WEBDAV 索引搜索 ----------

    /** 是否处于搜索模式（面包屑位置显示搜索框） */
    private val _searchMode = MutableStateFlow(false)
    val searchMode: StateFlow<Boolean> = _searchMode.asStateFlow()

    /** 搜索关键词 */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 搜索结果（复用主视图展示与操作） */
    private val _searchResults = MutableStateFlow<List<FileEntry>>(emptyList())
    val searchResults: StateFlow<List<FileEntry>> = _searchResults.asStateFlow()

    /** 搜索加载中 */
    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    /** 索引总条数（用于提示） */
    private val _indexTotal = MutableStateFlow(0)
    val indexTotal: StateFlow<Int> = _indexTotal.asStateFlow()

    /** 索引同步状态消息（如“正在下载索引文件 (1.2 MB)...”或“检查服务器更新...”） */
    private val _indexSyncMessage = MutableStateFlow<String?>(null)
    val indexSyncMessage: StateFlow<String?> = _indexSyncMessage.asStateFlow()

    /** 详细下载进度信息（包含百分比、字节数） */
    private val _indexProgress = MutableStateFlow(com.example.myfile.core.WebDavIndex.IndexProgress())
    val indexProgress: StateFlow<com.example.myfile.core.WebDavIndex.IndexProgress> = _indexProgress.asStateFlow()

    /** 用于弹窗或界面的瞬态提示信息 */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** 索引是否正在同步中 */
    private val _indexSyncing = MutableStateFlow(false)
    val indexSyncing: StateFlow<Boolean> = _indexSyncing.asStateFlow()

    /**
     * 从搜索结果进入文件夹时记录的起始路径（非 null 表示当前处于"搜索→文件夹"子导航状态）。
     * 当回退到此路径时，按返回键将恢复搜索结果而非继续向上导航。
     */
    private val _searchEntryPath = MutableStateFlow<String?>(null)
    val searchEntryPath: StateFlow<String?> = _searchEntryPath.asStateFlow()

    /** 进入搜索模式 */
    fun enterSearch() {
        _searchMode.value = true
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        val acc = _state.value.currentAccount ?: return
        // 先从本地磁盘秒级预热
        val cached = com.example.myfile.core.WebDavIndex.warmupFromDisk(acc.id)
        if (cached != null) {
            _indexTotal.value = cached.size
        } else if (acc.indexPath.isNotBlank()) {
            // 本地完全没有索引，且配置了索引路径，按需触发一次同步
            syncIndex(acc, forceRefresh = false)
        }
    }

    /** 退出搜索模式，恢复普通列表 */
    fun exitSearch() {
        _searchMode.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searchLoading.value = false
        _searchEntryPath.value = null
    }

    /** 同步索引文件（自动检查服务器更新；若 forceRefresh 为 true 则强制全量重新下载） */
    fun syncIndex(
        account: WebDavAccount,
        forceRefresh: Boolean = false,
        onComplete: ((com.example.myfile.core.WebDavIndex.SyncResult) -> Unit)? = null
    ) {
        if (account.indexPath.isBlank()) {
            _indexSyncMessage.value = "未配置索引文件路径"
            return
        }
        viewModelScope.launch {
            _indexSyncing.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    com.example.myfile.core.WebDavIndex.syncIndex(
                        client = MyApp.instance.okHttpClient,
                        account = account,
                        forceRefresh = forceRefresh,
                        onProgress = { prog ->
                            _indexProgress.value = prog
                            _indexSyncMessage.value = prog.message
                        }
                    )
                }
                when (result) {
                    is com.example.myfile.core.WebDavIndex.SyncResult.UpToDate -> {
                        _indexTotal.value = result.count
                        _indexSyncMessage.value = "索引已是最新 (共 ${result.count} 条)"
                    }
                    is com.example.myfile.core.WebDavIndex.SyncResult.Downloaded -> {
                        _indexTotal.value = result.count
                        _indexSyncMessage.value = "索引下载成功 (共 ${result.count} 条)"
                    }
                    is com.example.myfile.core.WebDavIndex.SyncResult.Error -> {
                        _indexSyncMessage.value = result.message
                    }
                }
                // 同步完成后如果当前正处于搜索模式且有关键词，自动重新检索
                if (_searchMode.value && _searchQuery.value.isNotBlank()) {
                    performSearch(_searchQuery.value)
                }
                onComplete?.invoke(result)
            } catch (e: Exception) {
                Log.e("WebDavVM", "syncIndex failed", e)
                _indexSyncMessage.value = "索引同步异常: ${e.message}"
            } finally {
                _indexSyncing.value = false
            }
        }
    }

    /**
     * 从搜索结果点击文件夹：切换到该文件夹的普通浏览模式，同时保留搜索关键词和搜索结果，
     * 以便之后可以通过 [returnToSearch] 恢复搜索列表。
     */
    fun openFromSearch(entry: FileEntry) {
        if (!entry.isDirectory) return
        _searchEntryPath.value = _state.value.currentPath  // 记录进入前的路径（作为返回锚点）
        _searchMode.value = false  // 隐藏搜索框，显示面包屑
        // 保留 _searchQuery 和 _searchResults，不清空
        _state.value.currentAccount?.let {
            if (it.rememberLastPath) pathStore.saveLastPath(it.id, entry.path)
        }
        val (mode, asc) = _state.value.currentAccount?.let { getFolderSort(it.id, entry.path) }
            ?: (SortMode.NAME to true)
        _state.value.currentAccount?.let { loadFolderPrefs(it.id, entry.path) }
        _state.value = _state.value.copy(
            currentPath = entry.path,
            sortMode = mode,
            sortAsc = asc,
            selected = emptySet(),
            multiSelectMode = false
        )
        refresh()
    }

    /**
     * 从文件夹返回搜索结果：恢复搜索模式，保留之前的搜索关键词和搜索结果。
     */
    fun returnToSearch() {
        _searchEntryPath.value = null
        _searchMode.value = true
        // _searchQuery 和 _searchResults 仍然保留，无需重新搜索
    }

    /** 更新关键词并执行搜索 */
    fun onSearchQueryChange(q: String) {
        _searchQuery.value = q
        performSearch(q)
    }

    private fun performSearch(q: String) {
        val acc = _state.value.currentAccount ?: return
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        val keywords = com.example.myfile.core.WebDavIndex.parseKeywords(q)
        viewModelScope.launch {
            _searchLoading.value = true
            try {
                // 优先从内存或本地磁盘取已有索引，无需等待网络
                var index = com.example.myfile.core.WebDavIndex.getAvailableIndex(acc.id)
                if (index.isEmpty()) {
                    // 本地完全没有索引，按需下载
                    withContext(Dispatchers.IO) {
                        com.example.myfile.core.WebDavIndex.syncIndex(
                            MyApp.instance.okHttpClient,
                            acc,
                            forceRefresh = false,
                            onProgress = { prog ->
                                _indexProgress.value = prog
                                _indexSyncMessage.value = prog.message
                            }
                        )
                    }
                    index = com.example.myfile.core.WebDavIndex.getAvailableIndex(acc.id)
                }
                _indexTotal.value = index.size
                val matched = withContext(Dispatchers.Default) {
                    com.example.myfile.core.WebDavIndex.search(index, keywords)
                }
                _searchResults.value = com.example.myfile.core.WebDavIndex.toFileEntries(
                    matched.take(2000)  // 限制上限，避免超大结果卡顿
                )
            } catch (e: Exception) {
                Log.e("WebDavVM", "search failed", e)
                _searchResults.value = emptyList()
            } finally {
                _searchLoading.value = false
            }
        }
    }

    /** 强制重新同步索引（忽略服务器未更新判断，全量拉取） */
    fun refreshIndex() {
        val acc = _state.value.currentAccount ?: return
        if (_indexSyncing.value) return
        syncIndex(acc, forceRefresh = true)
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
        val trimmed = newName.trim()
        // 安全校验：拒绝空名、含路径分隔符的名字（防止把文件移动到别的路径/父目录）
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            _state.value = _state.value.copy(error = "名称不合法")
            return
        }
        if (trimmed.contains('/') || trimmed.contains('\\')) {
            _state.value = _state.value.copy(error = "文件名不能包含 / 或 \\")
            return
        }
        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val dir = p.substringBeforeLast('/', "")
        val targetPath = if (dir.isEmpty()) "/$trimmed" else "$dir/$trimmed"
        if (targetPath == p) {
            _state.value = _state.value.copy(error = "名称未改变")
            return
        }
        // 重名校验：同目录下已存在同名条目则拒绝，避免 Overwrite 覆盖已有文件
        val conflict = _state.value.files.any {
            it.path != p && it.path.equals(targetPath, ignoreCase = true)
        }
        if (conflict) {
            _state.value = _state.value.copy(error = "已存在同名文件或文件夹")
            return
        }
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
