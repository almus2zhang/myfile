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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.example.myfile.model.ViewMode
import com.example.myfile.ui.components.FileGridItem
import com.example.myfile.ui.components.FileCompactItem
import com.example.myfile.ui.components.TextEditorDialog
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.myfile.ui.components.ApkDownloadDialog
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
    var downloadingApkTaskId by remember { mutableStateOf<Long?>(null) }
    var downloadingApkFileName by remember { mutableStateOf("") }

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

    val gridState = rememberLazyGridState()
    var pendingScrollRatio by remember { mutableStateOf<Pair<Float, Int>?>(null) }

    val viewMode by vm.viewMode.collectAsState()
    var showViewModeMenu by remember { mutableStateOf(false) }
    var showAccountMenu by remember { mutableStateOf(false) }
    var editingTextEntry by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(state.sortedFiles) {
        pendingScrollRatio?.let { (ratio, offset) ->
            pendingScrollRatio = null
            val newTotal = state.sortedFiles.size
            if (newTotal > 0) {
                val targetIndex = (ratio * newTotal).toInt().coerceIn(0, newTotal - 1)
                gridState.scrollToItem(targetIndex, offset)
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
            gridState.scrollToItem(target, off)
        } else {
            gridState.scrollToItem(0, 0)
        }
    }

    val progressList by MyApp.instance.db.videoProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressMap = remember(progressList) { progressList.associateBy { it.uriKey } }
    val currentSettings by MyApp.instance.currentSettings.collectAsState()

    val imageEntries = remember(state.sortedFiles) {
        state.sortedFiles.filter { !it.isDirectory && FileOpener.fileCategory(it.name) == "image" }
    }
    var viewingImageIndex by remember { mutableStateOf<Int?>(null) }
    var currentWatchingVideoKey by remember { mutableStateOf<String?>(null) }

    // 「打开方式」选择对话框状态
    var openWithRequest by remember { mutableStateOf<OpenWithRequest?>(null) }
    var renamingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showTrafficDebug by remember { mutableStateOf(false) }
    val activeTransfers by com.example.myfile.core.TrafficMonitor.activeTransfers.collectAsState()
    val totalSpeed by com.example.myfile.core.TrafficMonitor.totalDownloadSpeed.collectAsState()
    val scope = rememberCoroutineScope()

    // 方案B：详细视图(含缩略图和时长) 与 宫格视图 才探查时长与加载缩略图；普通详细视图与简洁视图不探查0流量
    val shouldLoadMedia = (viewMode == ViewMode.DETAILS_WITH_MEDIA || viewMode == ViewMode.GRID_LARGE || viewMode == ViewMode.GRID_SMALL)

    // WebDAV 视频：后台异步探查视频时长（基于轻量 HTTP Range 读取文件头与 moov 索引，0 全量下载）
    LaunchedEffect(state.files, shouldLoadMedia) {
        if (!shouldLoadMedia) return@LaunchedEffect
        val acc = state.currentAccount ?: return@LaunchedEffect
        val videoEntries = state.files.filter { !it.isDirectory && FileOpener.isVideo(it.name) }
        if (videoEntries.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val auth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("${acc.username}:${acc.password}".toByteArray())
                for (v in videoEntries) {
                    val videoKey = "${acc.id}_${v.path}"
                    val saved = MyApp.instance.db.videoProgressDao().get(videoKey)
                    if (saved == null || saved.durationMs <= 0L) {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            val p = if (v.path.startsWith("/")) v.path else "/${v.path}"
                            val fullUrl = acc.connectionUrl().trimEnd('/') + p
                            val headers = HashMap<String, String>()
                            headers["Authorization"] = auth
                            headers["User-Agent"] = "myfile/1.0 (Android; WebDAV)"
                            mmr.setDataSource(fullUrl, headers)
                            val dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            if (dur > 0L) {
                                MyApp.instance.db.videoProgressDao().save(
                                    com.example.myfile.data.db.entity.VideoProgressEntity(
                                        uriKey = videoKey,
                                        positionMs = saved?.positionMs ?: 0L,
                                        durationMs = dur,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { mmr.release() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    val externalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            MyApp.instance.downloadManager.finishAllStreamingRenames()
        }
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

    DisposableEffect(Unit) {
        onDispose {
            MyApp.instance.appScope.launch {
                MyApp.instance.downloadManager.finishAllStreamingRenames()
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // 系统返回键：如果处于多选模式则取消多选；否则回到上一层目录
    BackHandler(enabled = state.currentPath != "/" || state.multiSelectMode) {
        if (state.multiSelectMode) {
            vm.clearSelection()
        } else {
            vm.saveScrollPosition(state.currentPath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            vm.goUp()
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：当前配置名胶囊按钮（限制宽度约两个半按钮 ~110dp，点击弹出配置下拉菜单）
                    Box {
                        Surface(
                            onClick = { showAccountMenu = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = state.currentAccount?.name ?: "选择配置",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showAccountMenu,
                            onDismissRequest = { showAccountMenu = false }
                        ) {
                            state.accounts.forEach { acc ->
                                val isSelected = acc.id == state.currentAccount?.id
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (acc.isDynamic) {
                                                Icon(
                                                    Icons.Filled.SyncAlt,
                                                    contentDescription = "动态解析",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = acc.name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    } else null,
                                    trailingIcon = {
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    showAccountMenu = false
                                                    editingAccount = acc
                                                    showAccountDialog = true
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.Edit, "编辑", modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    showAccountMenu = false
                                                    vm.deleteAccount(acc)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    },
                                    onClick = {
                                        showAccountMenu = false
                                        vm.selectAccount(acc)
                                    }
                                )
                            }

                            HorizontalDivider()

                            // 配置名下拉列表最下面是添加配置
                            DropdownMenuItem(
                                text = { Text("+ 添加配置", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showAccountMenu = false
                                    editingAccount = null
                                    showAccountDialog = true
                                }
                            )
                        }
                    }

                    // 中间：若非根目录，紧凑展示当前子路径（点击可直接回退上级）
                    if (state.currentPath != "/") {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = state.currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable {
                                    vm.saveScrollPosition(state.currentPath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                    vm.goUp()
                                }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // 右侧紧凑操作按钮（高度统一 36dp 紧凑排列）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 视图模式切换按钮
                        Box {
                            IconButton(
                                onClick = { showViewModeMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = viewMode.icon,
                                    contentDescription = "切换视图",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showViewModeMenu,
                                onDismissRequest = { showViewModeMenu = false }
                            ) {
                                ViewMode.values().forEach { mode ->
                                    val isSelected = mode == viewMode
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = mode.title,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = mode.icon,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        trailingIcon = if (isSelected) {
                                            { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                        } else null,
                                        onClick = {
                                            showViewModeMenu = false
                                            vm.setViewMode(mode)
                                        }
                                    )
                                }
                            }
                        }

                        // 2. 排序按钮
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.Sort, "排序", modifier = Modifier.size(20.dp))
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
                                            val curIndex = gridState.firstVisibleItemIndex
                                            val curOffset = gridState.firstVisibleItemScrollOffset
                                            val curTotal = gridState.layoutInfo.totalItemsCount.coerceAtLeast(1)
                                            pendingScrollRatio = (curIndex.toFloat() / curTotal) to curOffset
                                            showSortMenu = false
                                            vm.setSort(mode, asc)
                                        }
                                    )
                                }
                            }
                        }

                        // 3. 网络传输监控
                        IconButton(
                            onClick = { showTrafficDebug = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (activeTransfers.isNotEmpty()) {
                                        Badge { Text("${activeTransfers.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Speed, "网络传输监控", modifier = Modifier.size(20.dp))
                            }
                        }

                        // 4. 刷新按钮
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
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
                    IconButton(onClick = { showBatchDeleteConfirm = true }) {
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
            if (state.currentAccount == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("点击左上方「选择配置」添加或选择 WebDAV 账户", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        // 响应式宫格布局：详细视图与简洁视图窄屏 1 列，宽屏自适应多列；宫格视图多列排列
                        val gridCells = when (viewMode) {
                            ViewMode.DETAILS_NO_MEDIA, ViewMode.DETAILS_WITH_MEDIA -> GridCells.Adaptive(minSize = 340.dp)
                            ViewMode.GRID_LARGE -> GridCells.Adaptive(minSize = 105.dp)
                            ViewMode.GRID_SMALL -> GridCells.Adaptive(minSize = 80.dp)
                            ViewMode.COMPACT -> GridCells.Adaptive(minSize = 300.dp)
                        }

                        LazyVerticalGrid(
                            columns = gridCells,
                            state = gridState,
                            contentPadding = when (viewMode) {
                                ViewMode.GRID_LARGE, ViewMode.GRID_SMALL -> PaddingValues(8.dp)
                                ViewMode.COMPACT -> PaddingValues(vertical = 4.dp)
                                else -> PaddingValues(0.dp)
                            },
                            horizontalArrangement = when (viewMode) {
                                ViewMode.GRID_LARGE, ViewMode.GRID_SMALL -> Arrangement.spacedBy(8.dp)
                                else -> Arrangement.spacedBy(0.dp)
                            },
                            verticalArrangement = when (viewMode) {
                                ViewMode.GRID_LARGE, ViewMode.GRID_SMALL -> Arrangement.spacedBy(8.dp)
                                else -> Arrangement.spacedBy(0.dp)
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.sortedFiles, key = { it.path }) { entry: FileEntry ->
                                val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
                                val fullUrl = base + p
                                val category = FileOpener.fileCategory(entry.name)
                                val videoKey = acc?.let { "acc_${it.id}$p" } ?: entry.path
                                val vProg = progressMap[videoKey]

                                // 方案B：详细视图(含缩略图和时长) 和 宫格视图 加载缩略图；其他模式不加载节约流量
                                val thumbUrl = if (!entry.isDirectory && shouldLoadMedia) {
                                    if ((category == "image" || category == "apk") && acc != null) {
                                        com.example.myfile.core.WebDavThumbRequest(acc, entry)
                                    } else if (category == "video" && acc != null) {
                                        com.example.myfile.core.WebDavThumbRequest(acc, entry)
                                    } else {
                                        fullUrl
                                    }
                                } else null

                                val durMs = if (shouldLoadMedia) vProg?.durationMs else null
                                val posMs = if (shouldLoadMedia) vProg?.positionMs else null

                                // 打开文件逻辑
                                fun openEntry(forceChooser: Boolean) {
                                    if (acc == null) return
                                    scope.launch {
                                        val appCtx = context.applicationContext
                                        val isVid = FileOpener.isVideo(entry.name)
                                        val fakeAvi = com.example.myfile.MyApp.instance.currentSettings.value.streamFakeAvi
                                        val ext = entry.name.substringAfterLast('.', "").lowercase()

                                        val streamRemotePath = if (isVid && fakeAvi && ext != "avi") {
                                            MyApp.instance.downloadManager.startStreamingRename(acc, entry.path) ?: entry.path
                                        } else {
                                            entry.path
                                        }

                                        var intent = if (isVid) {
                                            FileOpener.buildVideoStreamIntent(
                                                client = com.example.myfile.MyApp.instance.okHttpClient,
                                                account = acc,
                                                remotePath = streamRemotePath,
                                                fileName = entry.name,
                                                fakeAvi = fakeAvi,
                                                originalPath = entry.path
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
                                        if (intent == null) {
                                            if (isVid && fakeAvi && streamRemotePath != entry.path) {
                                                MyApp.instance.downloadManager.finishStreamingRename(entry.path)
                                            }
                                            return@launch
                                        }

                                        var candidates = FileOpener.resolveCandidates(appCtx, intent)
                                        if (candidates.isEmpty() && isVid) {
                                            if (fakeAvi && streamRemotePath != entry.path) {
                                                MyApp.instance.downloadManager.finishStreamingRename(entry.path)
                                            }
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
                                                } catch (_: Exception) {
                                                    if (isVid && fakeAvi && streamRemotePath != entry.path) {
                                                        MyApp.instance.downloadManager.finishStreamingRename(entry.path)
                                                    }
                                                }
                                            }
                                        }

                                        openWithRequest = OpenWithRequest(
                                            entry = entry,
                                            category = category,
                                            videoKey = videoKey,
                                            intent = finalIntent,
                                            candidates = finalCandidates
                                        )
                                    }
                                }

                                val onItemClick = {
                                    if (state.multiSelectMode) {
                                        vm.toggleSelect(entry.path)
                                    } else if (entry.isDirectory) {
                                        vm.saveScrollPosition(state.currentPath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                        vm.open(entry)
                                    } else if (category == "apk") {
                                        if (acc != null) {
                                            scope.launch {
                                                try {
                                                    com.example.myfile.core.download.DownloadService.start(MyApp.instance)
                                                    val dir = File(
                                                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                                        "myfile"
                                                    )
                                                    if (!dir.exists()) dir.mkdirs()
                                                    val taskId = MyApp.instance.downloadManager.startDownload(
                                                        acc,
                                                        entry.path,
                                                        entry.name,
                                                        dir,
                                                        knownSize = entry.size
                                                    )
                                                    downloadingApkFileName = entry.name
                                                    downloadingApkTaskId = taskId
                                                } catch (e: Exception) {
                                                    snackbarHostState.showSnackbar("启动加速下载失败: ${e.message}")
                                                }
                                            }
                                        }
                                    } else if (category == "image") {
                                        val idx = imageEntries.indexOfFirst { it.path == entry.path }
                                        if (idx >= 0) viewingImageIndex = idx
                                        else openEntry(forceChooser = false)
                                    } else if (FileOpener.isText(entry.name)) {
                                        // 内置文本浏览和编辑器
                                        editingTextEntry = entry
                                    } else {
                                        openEntry(forceChooser = false)
                                    }
                                }

                                val onItemLongClick = {
                                    vm.toggleSelect(entry.path)
                                }

                                // 更多操作下拉菜单
                                val trailingMenu: @Composable () -> Unit = {
                                    if (!state.multiSelectMode) {
                                        var showMenu by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { showMenu = true },
                                                modifier = Modifier.size(if (viewMode == ViewMode.COMPACT) 26.dp else 36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.MoreVert,
                                                    contentDescription = "更多",
                                                    modifier = Modifier.size(if (viewMode == ViewMode.COMPACT) 18.dp else 22.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showMenu,
                                                onDismissRequest = { showMenu = false }
                                            ) {
                                                if (!entry.isDirectory) {
                                                    if (FileOpener.isText(entry.name)) {
                                                        DropdownMenuItem(
                                                            text = { Text("编辑文本") },
                                                            leadingIcon = { Icon(Icons.Filled.EditNote, null) },
                                                            onClick = {
                                                                showMenu = false
                                                                editingTextEntry = entry
                                                            }
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = { Text("打开为…") },
                                                        leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                                                        onClick = {
                                                            showMenu = false
                                                            openEntry(forceChooser = true)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("加速下载") },
                                                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                                                        onClick = {
                                                            showMenu = false
                                                            state.currentAccount?.let { a ->
                                                                vm.downloadFile(a, entry)
                                                            }
                                                        }
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("重命名") },
                                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                                    onClick = {
                                                        showMenu = false
                                                        renamingEntry = entry
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("属性") },
                                                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                                                    onClick = {
                                                        showMenu = false
                                                        propertiesEntry = entry
                                                    }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        showMenu = false
                                                        deletingEntry = entry
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                when (viewMode) {
                                    ViewMode.DETAILS_NO_MEDIA, ViewMode.DETAILS_WITH_MEDIA -> {
                                        Column {
                                            FileListItem(
                                                entry = entry,
                                                thumbnailUrl = thumbUrl,
                                                thumbnailAuth = auth,
                                                thumbnailKey = acc?.let { "thumb_${it.id}_${entry.path}" } ?: "thumb_${entry.path}",
                                                videoProgress = vProg?.let {
                                                    if (it.durationMs > 0L) it.positionMs.toFloat() / it.durationMs else null
                                                },
                                                videoDurationMs = durMs,
                                                videoPositionMs = posMs,
                                                isSelected = entry.path in state.selected,
                                                onClick = onItemClick,
                                                onLongClick = onItemLongClick,
                                                trailing = trailingMenu
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 74.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                            )
                                        }
                                    }
                                    ViewMode.GRID_LARGE -> {
                                        FileGridItem(
                                            entry = entry,
                                            onClick = onItemClick,
                                            onLongClick = onItemLongClick,
                                            isSelected = entry.path in state.selected,
                                            thumbnailUrl = thumbUrl,
                                            thumbnailAuth = auth,
                                            thumbnailKey = acc?.let { "thumb_${it.id}_${entry.path}" } ?: "thumb_${entry.path}",
                                            videoDurationMs = durMs,
                                            videoPositionMs = posMs,
                                            isLarge = true,
                                            trailing = trailingMenu
                                        )
                                    }
                                    ViewMode.GRID_SMALL -> {
                                        FileGridItem(
                                            entry = entry,
                                            onClick = onItemClick,
                                            onLongClick = onItemLongClick,
                                            isSelected = entry.path in state.selected,
                                            thumbnailUrl = thumbUrl,
                                            thumbnailAuth = auth,
                                            thumbnailKey = acc?.let { "thumb_${it.id}_${entry.path}" } ?: "thumb_${entry.path}",
                                            videoDurationMs = durMs,
                                            videoPositionMs = posMs,
                                            isLarge = false,
                                            trailing = trailingMenu
                                        )
                                    }
                                    ViewMode.COMPACT -> {
                                        FileCompactItem(
                                            entry = entry,
                                            onClick = onItemClick,
                                            onLongClick = onItemLongClick,
                                            isSelected = entry.path in state.selected,
                                            trailing = trailingMenu
                                        )
                                    }
                                }
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
            onDismiss = {
                scope.launch { MyApp.instance.downloadManager.finishStreamingRename(req.entry.path) }
                openWithRequest = null
            },
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
                        MyApp.instance.downloadManager.finishStreamingRename(req.entry.path)
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
                    scope.launch { MyApp.instance.downloadManager.finishStreamingRename(req.entry.path) }
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

    downloadingApkTaskId?.let { taskId ->
        ApkDownloadDialog(
            taskId = taskId,
            fileName = downloadingApkFileName,
            onDismissRequest = { downloadingApkTaskId = null },
            onCancel = {
                val idToCancel = taskId
                downloadingApkTaskId = null
                scope.launch {
                    MyApp.instance.downloadManager.cancel(idToCancel)
                }
            }
        )
    }

    if (showTrafficDebug) {
        com.example.myfile.ui.components.DebugTrafficDialog(
            onDismiss = { showTrafficDebug = false }
        )
    }

    // 重命名对话框
    renamingEntry?.let { entry ->
        com.example.myfile.ui.components.FileRenameDialog(
            entry = entry,
            onDismiss = { renamingEntry = null },
            onConfirm = { newName ->
                vm.rename(entry, newName)
            }
        )
    }

    // 属性对话框
    propertiesEntry?.let { entry ->
        val videoKey = state.currentAccount?.let { "${it.id}_${entry.path}" } ?: entry.path
        val vProg = progressMap[videoKey]
        com.example.myfile.ui.components.FilePropertiesDialog(
            entry = entry,
            accountName = state.currentAccount?.name,
            videoDurationMs = vProg?.durationMs,
            videoPositionMs = vProg?.positionMs,
            onDismiss = { propertiesEntry = null }
        )
    }

    // 单项删除确认对话框
    deletingEntry?.let { entry ->
        com.example.myfile.ui.components.DeleteConfirmDialog(
            title = "确认删除",
            message = "确定要删除${if (entry.isDirectory) "文件夹" else "文件"} \"${entry.name}\" 吗？此操作无法撤销。",
            onDismiss = { deletingEntry = null },
            onConfirm = {
                vm.delete(entry)
            }
        )
    }

    // 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        com.example.myfile.ui.components.DeleteConfirmDialog(
            title = "确认批量删除",
            message = "确定要删除选中的 ${state.selected.size} 个项目吗？此操作无法撤销。",
            onDismiss = { showBatchDeleteConfirm = false },
            onConfirm = {
                vm.deleteSelected()
            }
        )
    }

    // 内置文本浏览和编辑器
    editingTextEntry?.let { entry ->
        TextEditorDialog(
            fileName = entry.name,
            filePath = entry.path,
            onLoad = { onProgress ->
                vm.streamDownloadText(entry.path, onProgress)
            },
            onSave = { newText ->
                vm.saveText(entry.path, newText)
            },
            onDismiss = { editingTextEntry = null }
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
