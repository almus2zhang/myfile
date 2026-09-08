package com.example.myfile.ui.webdav

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.MyApp
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.core.StreamProxy
import com.example.myfile.data.db.entity.VideoProgressEntity
import com.example.myfile.model.FileEntry
import com.example.myfile.ui.components.FileListItem
import com.example.myfile.ui.components.ImageViewerDialog
import com.example.myfile.ui.components.OpenWithDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
@Composable
fun WebDavScreen(vm: WebDavViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val clipboardItems by com.example.myfile.core.TransferClipboard.items.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAccountDialog by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    val refreshRotation = remember { Animatable(0f) }
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            val refreshJob = vm.refresh()
            refreshRotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 650, easing = LinearEasing)
            )
            refreshJob.join()
            pullRefreshState.endRefresh()
            refreshRotation.snapTo(0f)
        }
    }

    val listState = rememberLazyListState()
    var pendingScrollRatio by remember { mutableStateOf<Pair<Float, Int>?>(null) }

    LaunchedEffect(state.sortedFiles) {
        pendingScrollRatio?.let { (ratio, offset) ->
            pendingScrollRatio = null
            val newTotal = state.sortedFiles.size
            if (newTotal > 0) {
                val targetIndex = (ratio * newTotal).toInt().coerceIn(0, newTotal - 1)
                listState.scrollToItem(targetIndex, offset)
            }
        }
    }

    LaunchedEffect(state.currentPath) {
        snapshotFlow { state.sortedFiles }
            .filter { it.isNotEmpty() }
            .first()
        val saved = vm.getScrollPosition(state.currentPath)
        if (saved != null) {
            val (idx, off) = saved
            val target = idx.coerceIn(0, (state.sortedFiles.size - 1).coerceAtLeast(0))
            listState.scrollToItem(target, off)
        } else {
            listState.scrollToItem(0, 0)
        }
    }

    val progressList by MyApp.instance.db.videoProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressMap = remember(progressList) { progressList.associateBy { it.uriKey } }

    val imageEntries = remember(state.sortedFiles) {
        state.sortedFiles.filter { !it.isDirectory && FileOpener.fileCategory(it.name) == "image" }
    }
    var viewingImageIndex by remember { mutableStateOf<Int?>(null) }
    var currentWatchingVideoKey by remember { mutableStateOf<String?>(null) }

    // 「打开方式」选择对话框状态
    var openWithRequest by remember { mutableStateOf<OpenWithRequest?>(null) }
    val scope = rememberCoroutineScope()

    val externalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val key = currentWatchingVideoKey
        if (key != null && data != null) {
            val pos = when {
                data.hasExtra("position") -> {
                    val p = data.getIntExtra("position", -1)
                    if (p >= 0) p.toLong() else data.getLongExtra("position", -1L)
                }
                data.hasExtra("extra_position") -> {
                    data.getLongExtra("extra_position", -1L)
                }
                else -> -1L
            }
            val dur = when {
                data.hasExtra("duration") -> {
                    val d = data.getIntExtra("duration", -1)
                    if (d >= 0) d.toLong() else data.getLongExtra("duration", -1L)
                }
                else -> -1L
            }
            if (pos > 0L) {
                scope.launch {
                    MyApp.instance.db.videoProgressDao().save(
                        VideoProgressEntity(
                            uriKey = key,
                            positionMs = pos,
                            durationMs = dur.coerceAtLeast(0L),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // 系统返回键：如果处于多选模式则取消多选；否则回到上一层目录
    BackHandler(enabled = state.currentPath != "/" || state.multiSelectMode) {
        if (state.multiSelectMode) {
            vm.clearSelection()
        } else {
            vm.saveScrollPosition(state.currentPath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            vm.goUp()
        }
    }

    // 面包屑：/a/b/c -> [root, a, b, c]
    val crumbs = remember(state.currentPath) { buildCrumbs(state.currentPath) }
    // 顶部显示多级目录路径（对齐本地浏览规范）
    val displayPath = if (state.currentPath.isEmpty() || state.currentPath == "/") "/" else state.currentPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = displayPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            state.currentAccount == null -> "未选择账户"
                            state.currentAccount!!.isDynamic && state.currentAccount!!.resolvedUrl.isNotBlank() ->
                                "${state.currentAccount!!.name}  ·  动态连接: ${state.currentAccount!!.resolvedUrl}"
                            else -> state.currentAccount!!.name
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (state.currentPath != "/") {
                        IconButton(onClick = {
                            vm.saveScrollPosition(state.currentPath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            vm.goUp()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "上级")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Filled.Sort, "排序") }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            listOf(
                                Triple(SortMode.NAME, true, "名称 ↑"),
                                Triple(SortMode.NAME, false, "名称 ↓"),
                                Triple(SortMode.SIZE, true, "大小 ↑"),
                                Triple(SortMode.SIZE, false, "大小 ↓"),
                                Triple(SortMode.MODIFIED, true, "时间 ↑"),
                                Triple(SortMode.MODIFIED, false, "时间 ↓"),
                                Triple(SortMode.TYPE, true, "类型 ↑"),
                                Triple(SortMode.TYPE, false, "类型 ↓")
                            ).forEach { (mode, asc, label) ->
                                val isSelected = state.sortMode == mode && state.sortAsc == asc
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    } else null,
                                    onClick = {
                                        val curIndex = listState.firstVisibleItemIndex
                                        val curOffset = listState.firstVisibleItemScrollOffset
                                        val curTotal = listState.layoutInfo.totalItemsCount.coerceAtLeast(1)
                                        pendingScrollRatio = (curIndex.toFloat() / curTotal) to curOffset
                                        showSortMenu = false
                                        vm.setSort(mode, asc)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "刷新") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.currentAccount != null && !state.multiSelectMode) {
                FloatingActionButton(onClick = { showMkdir = true; mkdirName = "" }) {
                    Icon(Icons.Filled.CreateNewFolder, "新建文件夹")
                }
            }
        },
        bottomBar = {
            if (state.multiSelectMode) {
                BottomAppBar {
                    TextButton(onClick = { vm.selectAll() }) {
                        Text("全选")
                    }
                    Button(onClick = { vm.copySelected() }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制 (${state.selected.size})")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.deleteSelected() }) {
                        Icon(Icons.Filled.Delete, "删除")
                    }
                    TextButton(onClick = { vm.clearSelection() }) {
                        Text("取消")
                    }
                }
            } else if (clipboardItems.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "剪贴板: ${clipboardItems.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.pasteHere(context) }) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("粘贴到此处")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { com.example.myfile.core.TransferClipboard.clear() }) {
                        Text("清空")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 账户 Chip 列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.accounts.forEach { acc ->
                    var showAccMenu by remember(acc.id) { mutableStateOf(false) }
                    val selected = acc.id == state.currentAccount?.id
                    Box {
                        Surface(
                            modifier = Modifier.combinedClickable(
                                onClick = { vm.selectAccount(acc) },
                                onLongClick = { showAccMenu = true }
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (acc.isDynamic) {
                                    Icon(
                                        Icons.Filled.SyncAlt,
                                        contentDescription = "动态解析",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    acc.name,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showAccMenu,
                            onDismissRequest = { showAccMenu = false }
                        ) {
                            if (acc.isDynamic) {
                                DropdownMenuItem(
                                    text = { Text("重新获取真实地址") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)) },
                                    onClick = {
                                        showAccMenu = false
                                        vm.reResolveAccount(acc)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    showAccMenu = false
                                    editingAccount = acc
                                    showAccountDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    showAccMenu = false
                                    vm.deleteAccount(acc)
                                }
                            )
                        }
                    }
                }
                AssistChip(
                    onClick = { editingAccount = null; showAccountDialog = true },
                    label = { Text("+ 添加") },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }
                )
            }

            // 面包屑路径栏：可点击跳转，过长可左右拖动
            if (state.currentAccount != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    crumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Icon(
                                Icons.Filled.ArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = crumb.name,
                            color = if (index == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .clickable {
                                    vm.saveScrollPosition(state.currentPath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                    vm.navigateTo(crumb.path)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (state.currentAccount == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("点击上方「+ 添加」配置 WebDAV 账户", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (state.loading && state.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null && state.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.refresh() }) { Text("重试") }
                    }
                }
            } else {
                val acc = state.currentAccount
                val auth = acc?.let {
                    "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("${it.username}:${it.password}".toByteArray())
                }
                val base = acc?.connectionUrl()?.trimEnd('/') ?: ""
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(pullRefreshState.nestedScrollConnection)
                ) {
                    if (state.sortedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("此文件夹为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(state.sortedFiles, key = { it.path }) { entry: FileEntry ->
                        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
                        val fullUrl = base + p
                        val category = FileOpener.fileCategory(entry.name)
                        val videoKey = acc?.let { "acc_${it.id}$p" } ?: entry.path

                        // 打开文件：先尝试 myfile 记录的默认程序，无则弹「打开方式」对话框
                        fun openEntry(forceChooser: Boolean) {
                            if (acc == null) return
                            scope.launch {
                                val appCtx = context.applicationContext
                                var intent = if (FileOpener.isVideo(entry.name)) {
                                    val fakeAvi = com.example.myfile.MyApp.instance.currentSettings.value.streamFakeAvi
                                    FileOpener.buildVideoStreamIntent(
                                        client = com.example.myfile.MyApp.instance.okHttpClient,
                                        account = acc,
                                        remotePath = entry.path,
                                        fileName = entry.name,
                                        fakeAvi = fakeAvi
                                    )
                                } else {
                                    val tmp = FileOpener.downloadToCache(
                                        client = com.example.myfile.MyApp.instance.okHttpClient,
                                        authHeader = auth ?: "",
                                        url = fullUrl,
                                        fileName = entry.name
                                    ) ?: return@launch
                                    FileOpener.buildLocalViewIntent(appCtx, tmp)
                                }
                                if (intent == null) return@launch

                                // 视频流式意图若找不到可处理的播放器（http scheme 匹配太严），
                                // 回退为下载到缓存后用 content:// 打开
                                var candidates = FileOpener.resolveCandidates(appCtx, intent)
                                if (candidates.isEmpty() && FileOpener.isVideo(entry.name)) {
                                    val tmp = FileOpener.downloadToCache(
                                        client = com.example.myfile.MyApp.instance.okHttpClient,
                                        authHeader = auth ?: "",
                                        url = fullUrl,
                                        fileName = entry.name
                                    )
                                    if (tmp != null) {
                                        FileOpener.buildLocalViewIntent(appCtx, tmp)?.let {
                                            intent = it
                                            candidates = FileOpener.resolveCandidates(appCtx, it)
                                        }
                                    }
                                }

                                val finalIntent = intent ?: return@launch
                                val finalCandidates = candidates

                                if (category == "video") {
                                    val saved = MyApp.instance.db.videoProgressDao().get(videoKey)
                                    if (saved != null && saved.positionMs > 1000L) {
                                        finalIntent.putExtra("position", saved.positionMs.toInt())
                                        finalIntent.putExtra("position_ms", saved.positionMs)
                                        finalIntent.putExtra("extra_position", saved.positionMs)
                                        finalIntent.putExtra("time", (saved.positionMs / 1000).toInt())
                                        finalIntent.putExtra("from_start", false)
                                    }
                                    finalIntent.putExtra("return_result", true)
                                }

                                // 有默认程序且非「打开为」→ 直接用默认程序打开
                                val defaultApp = MyApp.instance.defaultAppStore.get(category)
                                if (!forceChooser && defaultApp != null) {
                                    val parts = defaultApp.split('/')
                                    if (parts.size == 2) {
                                        val explicit = Intent(finalIntent).apply {
                                            component = ComponentName(parts[0], parts[1])
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                        }
                                        currentWatchingVideoKey = if (category == "video") videoKey else null
                                        try {
                                            externalLauncher.launch(explicit)
                                            return@launch
                                        } catch (_: Exception) {}
                                    }
                                }

                                // 弹「打开方式」选择对话框
                                openWithRequest = OpenWithRequest(
                                    entry = entry,
                                    category = category,
                                    videoKey = videoKey,
                                    intent = finalIntent,
                                    candidates = finalCandidates
                                )
                            }
                        }
                        FileListItem(
                            entry = entry,
                            thumbnailUrl = if (!entry.isDirectory) {
                                if (category == "image" && acc != null) {
                                    com.example.myfile.core.WebDavThumbRequest(acc, entry)
                                } else {
                                    fullUrl
                                }
                            } else null,
                            thumbnailAuth = auth,
                            thumbnailKey = acc?.let { "thumb_${it.id}_${entry.path}" } ?: "thumb_${entry.path}",
                            videoProgress = progressMap[videoKey]?.let {
                                if (it.durationMs > 0L) it.positionMs.toFloat() / it.durationMs else null
                            },
                            isSelected = entry.path in state.selected,
                            onClick = {
                                if (state.multiSelectMode) {
                                    vm.toggleSelect(entry.path)
                                } else if (entry.isDirectory) {
                                    vm.saveScrollPosition(state.currentPath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                    vm.open(entry)
                                } else if (category == "image") {
                                    val idx = imageEntries.indexOfFirst { it.path == entry.path }
                                    if (idx >= 0) viewingImageIndex = idx
                                    else openEntry(forceChooser = false)
                                } else {
                                    openEntry(forceChooser = false)
                                }
                            },
                            onLongClick = { vm.toggleSelect(entry.path) },
                            trailing = {
                                if (!entry.isDirectory && !state.multiSelectMode) {
                                    var showMenu by remember { mutableStateOf(false) }
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Filled.MoreVert, "更多")
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("打开为…") },
                                            onClick = {
                                                showMenu = false
                                                openEntry(forceChooser = true)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("加速下载") },
                                            onClick = {
                                                showMenu = false
                                                state.currentAccount?.let { a ->
                                                    vm.downloadFile(a, entry)
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除") },
                                            onClick = { showMenu = false; vm.delete(entry) }
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (pullRefreshState.verticalOffset > 0.5f || pullRefreshState.isRefreshing) {
                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    indicator = { s ->
                        val rot = if (s.isRefreshing) {
                            refreshRotation.value
                        } else {
                            (s.verticalOffset * 2.5f) % 360f
                        }
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { rotationZ = rot },
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        }
    }
        }
    }

    if (showAccountDialog) {
        WebDavAccountDialog(
            initial = editingAccount,
            onDismiss = { showAccountDialog = false },
            onSave = { vm.saveAccount(it); showAccountDialog = false }
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(value = mkdirName, onValueChange = { mkdirName = it }, label = { Text("名称") })
            },
            confirmButton = {
                TextButton(onClick = { if (mkdirName.isNotBlank()) vm.mkdir(mkdirName); showMkdir = false }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("取消") } }
        )
    }

    // 「打开方式」选择对话框
    openWithRequest?.let { req ->
        val context = androidx.compose.ui.platform.LocalContext.current
        OpenWithDialog(
            title = "打开 \"${req.entry.name}\"",
            candidates = req.candidates,
            onDismiss = { openWithRequest = null },
            onSelect = { candidate, always ->
                scope.launch {
                    if (always) {
                        FileOpener.setDefault(req.category, candidate)
                    }
                    val explicit = Intent(req.intent).apply {
                        component = candidate.component
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                    }
                    currentWatchingVideoKey = if (req.category == "video") req.videoKey else null
                    try {
                        externalLauncher.launch(explicit)
                    } catch (e: Exception) {
                        FileOpener.openWith(context, req.intent, candidate)
                    }
                }
                openWithRequest = null
            },
            onSystemChooser = {
                val clean = Intent(req.intent).apply {
                    flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                }
                val chooser = Intent.createChooser(clean, "打开为").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                }
                currentWatchingVideoKey = if (req.category == "video") req.videoKey else null
                try {
                    externalLauncher.launch(chooser)
                } catch (e: Exception) {
                    FileOpener.openWithSystemChooser(context, req.intent)
                }
                openWithRequest = null
            }
        )
    }

    // 内置图片查看器（支持左右翻页）
    viewingImageIndex?.let { idx ->
        val acc = state.currentAccount
        val auth = acc?.let {
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("${it.username}:${it.password}".toByteArray())
        }
        val base = acc?.connectionUrl()?.trimEnd('/') ?: ""
        ImageViewerDialog(
            images = imageEntries,
            initialIndex = idx,
            baseUrl = base,
            authHeader = auth,
            onDismiss = { viewingImageIndex = null }
        )
    }
}

/** 「打开方式」对话框的请求数据 */
private data class OpenWithRequest(
    val entry: FileEntry,
    val category: String,
    val videoKey: String?,
    val intent: android.content.Intent,
    val candidates: List<AppCandidate>
)

/** 面包屑项 */
private data class Crumb(val name: String, val path: String)

/** 把 /a/b/c 拆成 [(根, /), (a, /a), (b, /a/b), (c, /a/b/c)] */
private fun buildCrumbs(currentPath: String): List<Crumb> {
    val path = currentPath.trimEnd('/')
    if (path.isEmpty() || path == "/") return listOf(Crumb("根目录", "/"))
    val segments = path.split('/').filter { it.isNotEmpty() }
    val crumbs = mutableListOf<Crumb>(Crumb("根目录", "/"))
    var acc = ""
    segments.forEach { seg ->
        acc = "$acc/$seg"
        crumbs.add(Crumb(seg, acc))
    }
    return crumbs
}
