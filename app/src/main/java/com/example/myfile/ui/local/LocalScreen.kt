package com.example.myfile.ui.local

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
import com.example.myfile.ui.webdav.SortMode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.MyApp
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.model.FileEntry
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ComponentName
import android.content.Intent
import com.example.myfile.data.db.entity.VideoProgressEntity
import com.example.myfile.ui.components.FileListItem
import com.example.myfile.ui.components.ImageViewerDialog
import com.example.myfile.ui.components.OpenWithDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(vm: LocalViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val clipboardItems by com.example.myfile.core.TransferClipboard.items.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var openWithRequest by remember { mutableStateOf<LocalOpenWithRequest?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val refreshRotation = remember { Animatable(0f) }
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            val refreshJob = vm.refresh()
            do {
                refreshRotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 300, easing = LinearEasing)
                )
                refreshRotation.snapTo(0f)
            } while (refreshJob.isActive)
            kotlinx.coroutines.delay(180)
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

    LaunchedEffect(state.currentDir) {
        snapshotFlow { state.sortedFiles }
            .filter { it.isNotEmpty() }
            .first()
        val saved = vm.getScrollPosition(state.currentDir.absolutePath)
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

    val isRoot = vm.isAtRoot(state.currentDir)

    // 系统返回键：如果处于多选模式则取消多选；若非顶层目录则返回上一层；若已在内部存储顶层则放行（退出应用）
    BackHandler(enabled = !isRoot || state.multiSelectMode) {
        if (state.multiSelectMode) {
            vm.clearSelection()
        } else {
            vm.saveScrollPosition(state.currentDir.absolutePath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            vm.goUp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = if (isRoot) "内部存储" else {
                        val rel = state.currentDir.absolutePath
                            .removePrefix(vm.rootDir.absolutePath)
                            .trimStart(java.io.File.separatorChar, '/')
                        if (rel.isEmpty()) "内部存储" else rel
                    }
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (!isRoot) {
                        IconButton(onClick = {
                            vm.saveScrollPosition(state.currentDir.absolutePath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            vm.goUp()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, "排序")
                        }
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
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
                    if (!isRoot) {
                        IconButton(onClick = {
                            vm.saveScrollPosition(state.currentDir.absolutePath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            vm.goUp()
                        }) {
                            Icon(Icons.Filled.ArrowUpward, "上级")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewFolder = true; newName = "" }) {
                Icon(Icons.Filled.Add, "新建文件夹")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.sortedFiles, key = { it.path }) { entry: FileEntry ->
                fun openEntry(forceChooser: Boolean) {
                    val file = java.io.File(entry.path)
                    val intent = FileOpener.buildLocalViewIntent(context, file) ?: return
                    val category = FileOpener.fileCategory(entry.name)
                    scope.launch {
                        if (category == "video") {
                            val saved = MyApp.instance.db.videoProgressDao().get(entry.path)
                            if (saved != null && saved.positionMs > 1000L) {
                                intent.putExtra("position", saved.positionMs.toInt())
                                intent.putExtra("position_ms", saved.positionMs)
                                intent.putExtra("extra_position", saved.positionMs)
                                intent.putExtra("time", (saved.positionMs / 1000).toInt())
                                intent.putExtra("from_start", false)
                            }
                            intent.putExtra("return_result", true)

                            // 后台异步解析视频时长并记录，确保进度条比例准确
                            if (saved == null || saved.durationMs <= 0L) {
                                kotlinx.coroutines.Dispatchers.IO.let { ioDispatcher ->
                                    launch(ioDispatcher) {
                                        try {
                                            val mmr = android.media.MediaMetadataRetriever()
                                            mmr.setDataSource(entry.path)
                                            val dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                            mmr.release()
                                            if (dur > 0L) {
                                                MyApp.instance.db.videoProgressDao().save(
                                                    VideoProgressEntity(
                                                        uriKey = entry.path,
                                                        positionMs = saved?.positionMs ?: 0L,
                                                        durationMs = dur,
                                                        updatedAt = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }

                        val defaultApp = MyApp.instance.defaultAppStore.get(category)
                        if (!forceChooser && defaultApp != null) {
                            val parts = defaultApp.split('/')
                            if (parts.size == 2) {
                                val explicit = Intent(intent).apply {
                                    component = ComponentName(parts[0], parts[1])
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                }
                                currentWatchingVideoKey = if (category == "video") entry.path else null
                                try {
                                    externalLauncher.launch(explicit)
                                    return@launch
                                } catch (_: Exception) {}
                            }
                        }

                        val candidates = FileOpener.resolveCandidates(context.applicationContext, intent)
                        openWithRequest = LocalOpenWithRequest(
                            entry = entry,
                            category = category,
                            intent = intent,
                            candidates = candidates
                        )
                    }
                }
                val category = FileOpener.fileCategory(entry.name)
                FileListItem(
                    entry = entry,
                    thumbnailUrl = if (!entry.isDirectory) entry.path else null,
                    videoProgress = progressMap[entry.path]?.let {
                        if (it.durationMs > 0L) it.positionMs.toFloat() / it.durationMs else null
                    },
                    onClick = {
                        if (state.multiSelectMode) {
                            vm.toggleSelect(entry.path)
                        } else if (entry.isDirectory) {
                            vm.saveScrollPosition(state.currentDir.absolutePath, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
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
                    isSelected = entry.path in state.selected,
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
                                    text = { Text("删除") },
                                    onClick = { showMenu = false; vm.deleteOne(entry) }
                                )
                            }
                        }
                    }
                )
                HorizontalDivider()
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
                        (s.verticalOffset * 5f) % 360f
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

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("文件夹名") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) vm.newFolder(newName)
                    showNewFolder = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } }
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
                    currentWatchingVideoKey = if (req.category == "video") req.entry.path else null
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
                currentWatchingVideoKey = if (req.category == "video") req.entry.path else null
                try {
                    externalLauncher.launch(chooser)
                } catch (e: Exception) {
                    FileOpener.openWithSystemChooser(context, req.intent)
                }
                openWithRequest = null
            }
        )
    }

    // 内置图片查看器
    viewingImageIndex?.let { idx ->
        ImageViewerDialog(
            images = imageEntries,
            initialIndex = idx,
            onDismiss = { viewingImageIndex = null }
        )
    }
}

/** 本地文件「打开方式」对话框请求数据 */
private data class LocalOpenWithRequest(
    val entry: FileEntry,
    val category: String,
    val intent: android.content.Intent,
    val candidates: List<AppCandidate>
)
