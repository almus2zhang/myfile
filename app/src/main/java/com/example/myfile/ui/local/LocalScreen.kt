package com.example.myfile.ui.local

import android.content.ComponentName
import android.content.Intent
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.MyApp
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.data.db.entity.VideoProgressEntity
import com.example.myfile.model.FileEntry
import com.example.myfile.model.ViewMode
import com.example.myfile.ui.components.*
import com.example.myfile.ui.webdav.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(vm: LocalViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val clipboardItems by com.example.myfile.core.TransferClipboard.items.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val viewMode by vm.viewMode.collectAsState()
    val showThumbnailsAndDuration by vm.showThumbnailsAndDuration.collectAsState()
    val durationRefreshTrigger by vm.durationRefreshTrigger.collectAsState()
    var lastProcessedTrigger by remember { mutableStateOf(0) }

    val shouldLoadMedia = showThumbnailsAndDuration && (viewMode != ViewMode.COMPACT)

    val activeTransfers by com.example.myfile.core.TrafficMonitor.activeTransfers.collectAsState()
    val totalSpeed by com.example.myfile.core.TrafficMonitor.totalDownloadSpeed.collectAsState()

    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var renamingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var propertiesEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deletingEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var editingTextEntry by remember { mutableStateOf<FileEntry?>(null) }
    var viewingImageIndex by remember { mutableStateOf<Int?>(null) }
    var openWithRequest by remember { mutableStateOf<LocalOpenWithRequest?>(null) }
    var showTrafficDebug by remember { mutableStateOf(false) }

    var showViewModeMenu by remember { mutableStateOf(false) }
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

    LaunchedEffect(state.currentDir.absolutePath) {
        val saved = vm.getScrollPosition(state.currentDir.absolutePath)
        if (saved != null) {
            gridState.scrollToItem(saved.first, saved.second)
        } else {
            gridState.scrollToItem(0, 0)
        }
    }

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

    val progressList by MyApp.instance.db.videoProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressMap = remember(progressList) { progressList.associateBy { it.uriKey } }

    val imageEntries = remember(state.sortedFiles) {
        state.sortedFiles.filter { !it.isDirectory && FileOpener.fileCategory(it.name) == "image" }
    }

    LaunchedEffect(state.files, shouldLoadMedia, durationRefreshTrigger) {
        if (!shouldLoadMedia) return@LaunchedEffect
        val videoEntries = state.files.filter { !it.isDirectory && FileOpener.isVideo(it.name) }
        if (videoEntries.isNotEmpty()) {
            val isForce = (durationRefreshTrigger != lastProcessedTrigger)
            lastProcessedTrigger = durationRefreshTrigger
            withContext(Dispatchers.IO) {
                for (v in videoEntries) {
                    val saved = MyApp.instance.db.videoProgressDao().get(v.path)
                    if (isForce || saved == null || saved.durationMs <= 0L) {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            if (v.path.startsWith("content://")) {
                                mmr.setDataSource(context, android.net.Uri.parse(v.path))
                            } else {
                                mmr.setDataSource(v.path)
                            }
                            val dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            if (dur > 0L) {
                                MyApp.instance.db.videoProgressDao().save(
                                    VideoProgressEntity(
                                        uriKey = v.path,
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

    var currentWatchingVideoKey by remember { mutableStateOf<String?>(null) }
    val externalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null && currentWatchingVideoKey != null) {
            val videoKey = currentWatchingVideoKey!!
            val pos = data.getIntExtra("position", -1).takeIf { it >= 0 }?.toLong()
                ?: data.getLongExtra("position", -1L).takeIf { it >= 0 }
                ?: data.getLongExtra("position_ms", -1L).takeIf { it >= 0 }
                ?: data.getIntExtra("extra_position", -1).takeIf { it >= 0 }?.toLong()
                ?: (data.getIntExtra("time", -1).takeIf { it >= 0 }?.toLong()?.let { it * 1000 })
            val dur = data.getIntExtra("duration", -1).takeIf { it > 0 }?.toLong()
                ?: data.getLongExtra("duration", -1L).takeIf { it > 0 }
                ?: data.getLongExtra("duration_ms", -1L).takeIf { it > 0 }
                ?: 0L
            if (pos != null && pos > 0L) {
                scope.launch(Dispatchers.IO) {
                    val saved = MyApp.instance.db.videoProgressDao().get(videoKey)
                    MyApp.instance.db.videoProgressDao().save(
                        VideoProgressEntity(
                            uriKey = videoKey,
                            positionMs = pos,
                            durationMs = if (dur > 0) dur else (saved?.durationMs ?: 0L),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        currentWatchingVideoKey = null
    }

    val isRoot = vm.isAtRoot(state.currentDir)
    val localCrumbs = remember(state.currentDir) { buildLocalCrumbs(vm.rootDir, state.currentDir) }

    BackHandler(enabled = !isRoot || state.multiSelectMode) {
        if (state.multiSelectMode) {
            vm.clearSelection()
        } else {
            vm.saveScrollPosition(state.currentDir.absolutePath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            vm.goUp()
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "内部存储",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )

                    Spacer(Modifier.width(4.dp))

                    // 常用路径按钮
                    var showFavoritesMenu by remember { mutableStateOf(false) }
                    val favoriteKey = com.example.myfile.data.prefs.FavoritePathStore.LOCAL_STORAGE_KEY
                    var favoritesList by remember { mutableStateOf(emptyList<String>()) }
                    LaunchedEffect(showFavoritesMenu) {
                        favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                    }

                    Box {
                        Surface(
                            onClick = { showFavoritesMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.BookmarkBorder,
                                    contentDescription = "常用路径",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "常用路径",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showFavoritesMenu,
                            onDismissRequest = { showFavoritesMenu = false }
                        ) {
                            // 列表最上方为保存当前路径到常用路径
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "★ 保存当前路径到常用路径",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = {
                                    showFavoritesMenu = false
                                    MyApp.instance.favoritePathStore.addFavorite(favoriteKey, state.currentDir.absolutePath)
                                    favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("已保存当前路径到常用路径")
                                    }
                                }
                            )

                            HorizontalDivider()

                            if (favoritesList.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无常用路径", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { }
                                )
                            } else {
                                favoritesList.forEach { favPath ->
                                    val isCurrent = favPath == state.currentDir.absolutePath
                                    val displayName = try {
                                        val rel = java.io.File(favPath).relativeToOrNull(vm.rootDir)?.path
                                        if (rel.isNullOrBlank()) "根目录" else rel
                                    } catch (_: Exception) { favPath }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = displayName,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Folder,
                                                null,
                                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    MyApp.instance.favoritePathStore.removeFavorite(favoriteKey, favPath)
                                                    favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "删除",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            showFavoritesMenu = false
                                            val target = java.io.File(favPath)
                                            if (target.exists() && target.isDirectory) {
                                                vm.navigateTo(target)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    if (totalSpeed > 0L || activeTransfers.isNotEmpty()) {
                        Surface(
                            onClick = { showTrafficDebug = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp)
                            ) {
                                Icon(Icons.Filled.Speed, contentDescription = "网速", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = com.example.myfile.ui.components.formatSpeed(totalSpeed),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            IconButton(onClick = { showViewModeMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = viewMode.icon, contentDescription = "切换视图", modifier = Modifier.size(24.dp))
                            }
                            DropdownMenu(expanded = showViewModeMenu, onDismissRequest = { showViewModeMenu = false }) {
                                ViewMode.values().forEach { mode ->
                                    val isSelected = mode == viewMode
                                    DropdownMenuItem(
                                        text = { Text(text = mode.title, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        leadingIcon = { Icon(imageVector = mode.icon, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                                        trailingIcon = if (isSelected) { { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } } else null,
                                        onClick = { showViewModeMenu = false; vm.setViewMode(mode) }
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(text = "显示缩略图和时长", style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(imageVector = Icons.Filled.Image, contentDescription = null, tint = if (showThumbnailsAndDuration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                                    trailingIcon = { Checkbox(checked = showThumbnailsAndDuration, onCheckedChange = null) },
                                    onClick = { vm.toggleShowThumbnailsAndDuration() }
                                )
                                DropdownMenuItem(
                                    text = { Text(text = "强制重新获取时长", style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                                    onClick = { showViewModeMenu = false; android.widget.Toast.makeText(context, "正在重新获取当前目录视频时长...", android.widget.Toast.LENGTH_SHORT).show(); vm.forceRefreshDurations() }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.Sort, "排序", modifier = Modifier.size(24.dp))
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                listOf(
                                    Triple(SortMode.NAME, true, "名称 ↑"), Triple(SortMode.NAME, false, "名称 ↓"),
                                    Triple(SortMode.SIZE, true, "大小 ↑"), Triple(SortMode.SIZE, false, "大小 ↓"),
                                    Triple(SortMode.MODIFIED, true, "时间 ↑"), Triple(SortMode.MODIFIED, false, "时间 ↓"),
                                    Triple(SortMode.TYPE, true, "类型 ↑"), Triple(SortMode.TYPE, false, "类型 ↓")
                                ).forEach { (mode, asc, label) ->
                                    val isSelected = state.sortMode == mode && state.sortAsc == asc
                                    DropdownMenuItem(
                                        text = { Text(text = label, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        leadingIcon = if (isSelected) { { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) } } else null,
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
                        IconButton(onClick = { showTrafficDebug = true }, modifier = Modifier.size(40.dp)) {
                            BadgedBox(badge = { if (activeTransfers.isNotEmpty()) Badge { Text("${activeTransfers.size}") } }) {
                                Icon(Icons.Filled.Speed, "网络传输监控", modifier = Modifier.size(24.dp))
                            }
                        }
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.multiSelectMode) {
                FloatingActionButton(onClick = { showMkdir = true; mkdirName = "" }) {
                    Icon(Icons.Filled.CreateNewFolder, "新建文件夹")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.multiSelectMode || clipboardItems.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable(enabled = state.sortedFiles.isNotEmpty()) { vm.selectAll() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.SelectAll, contentDescription = "全选", tint = if (state.sortedFiles.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = "全选", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (state.sortedFiles.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        val canCopy = state.selected.isNotEmpty()
                        Column(modifier = Modifier.weight(1f).clickable(enabled = canCopy) { vm.copySelected() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = "复制", tint = if (canCopy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = if (canCopy) "复制(${state.selected.size})" else "复制", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (canCopy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        val canCut = state.selected.isNotEmpty()
                        Column(modifier = Modifier.weight(1f).clickable(enabled = canCut) { vm.cutSelected() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.ContentCut, contentDescription = "剪切", tint = if (canCut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = if (canCut) "剪切(${state.selected.size})" else "剪切", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (canCut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        val canPaste = clipboardItems.isNotEmpty()
                        Column(modifier = Modifier.weight(1f).clickable(enabled = canPaste) { vm.pasteHere(context) { com.example.myfile.core.TransferClipboard.clear(); vm.clearSelection() } }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.ContentPaste, contentDescription = "粘贴", tint = if (canPaste) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = if (canPaste) "粘贴(${clipboardItems.size})" else "粘贴", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (canPaste) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        val canDelete = state.selected.isNotEmpty()
                        Column(modifier = Modifier.weight(1f).clickable(enabled = canDelete) { showBatchDeleteConfirm = true }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.Delete, contentDescription = "删除", tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = "删除", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                        }
                        Column(modifier = Modifier.weight(1f).clickable { vm.clearSelection(); com.example.myfile.core.TransferClipboard.clear() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "取消", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp))
                            Text(text = "取消", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    localCrumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Text(text = "›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 2.dp))
                        }
                        val isCurrent = index == localCrumbs.lastIndex
                        Surface(shape = RoundedCornerShape(6.dp), color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { if (!isCurrent) { vm.saveScrollPosition(state.currentDir.absolutePath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset); vm.navigateTo(crumb.file) } }) {
                            Text(text = crumb.name, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize().nestedScroll(pullRefreshState.nestedScrollConnection)) {
                if (state.sortedFiles.isEmpty() && !state.isRefreshing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "文件夹为空", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = when (viewMode) {
                            ViewMode.GRID_LARGE -> GridCells.Adaptive(minSize = 130.dp)
                            ViewMode.GRID_SMALL -> GridCells.Adaptive(minSize = 90.dp)
                            ViewMode.DETAILS, ViewMode.COMPACT -> GridCells.Adaptive(minSize = 340.dp)
                        },
                        contentPadding = when (viewMode) {
                            ViewMode.GRID_LARGE, ViewMode.GRID_SMALL -> PaddingValues(8.dp)
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
                            val category = FileOpener.fileCategory(entry.name)
                            val vProg = progressMap[entry.path]
                            val thumbUrl = if (!entry.isDirectory && shouldLoadMedia) {
                                if (category == "image" || category == "video" || category == "apk") {
                                    File(entry.path)
                                } else null
                            } else null
                            val durMs = if (shouldLoadMedia) vProg?.durationMs else null
                            val posMs = if (shouldLoadMedia) vProg?.positionMs else null
                            fun openEntry(forceChooser: Boolean) {
                                scope.launch {
                                    val appCtx = context.applicationContext
                                    val f = File(entry.path)
                                    val intent = FileOpener.buildLocalViewIntent(appCtx, f) ?: return@launch
                                    val candidates = FileOpener.resolveCandidates(appCtx, intent)
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
                                            try { externalLauncher.launch(explicit); return@launch } catch (_: Exception) {}
                                        }
                                    }
                                    if (!forceChooser && candidates.size == 1) {
                                        val explicit = Intent(intent).apply {
                                            component = candidates[0].component
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                        }
                                        currentWatchingVideoKey = if (category == "video") entry.path else null
                                        try { externalLauncher.launch(explicit); return@launch } catch (_: Exception) {}
                                    }
                                    openWithRequest = LocalOpenWithRequest(entry = entry, category = category, intent = intent, candidates = candidates)
                                }
                            }
                            val onItemClick = {
                                if (state.multiSelectMode) {
                                    vm.toggleSelect(entry.path)
                                } else if (entry.isDirectory) {
                                    vm.saveScrollPosition(state.currentDir.absolutePath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                    vm.open(entry)
                                } else if (FileOpener.fileCategory(entry.name) == "image") {
                                    val idx = imageEntries.indexOfFirst { it.path == entry.path }
                                    if (idx >= 0) viewingImageIndex = idx else openEntry(forceChooser = false)
                                } else if (FileOpener.isText(entry.name)) {
                                    editingTextEntry = entry
                                } else {
                                    openEntry(forceChooser = false)
                                }
                            }
                            val onItemLongClick = { vm.toggleSelect(entry.path) }
                            val trailingMenu: @Composable () -> Unit = {
                                if (!state.multiSelectMode) {
                                    var showMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(if (viewMode == ViewMode.COMPACT) 26.dp else 36.dp)) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(if (viewMode == ViewMode.COMPACT) 18.dp else 22.dp))
                                        }
                                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                            if (!entry.isDirectory) {
                                                if (FileOpener.isText(entry.name)) {
                                                    DropdownMenuItem(text = { Text("编辑文本") }, leadingIcon = { Icon(Icons.Filled.EditNote, null) }, onClick = { showMenu = false; editingTextEntry = entry })
                                                }
                                                DropdownMenuItem(text = { Text("打开为…") }, leadingIcon = { Icon(Icons.Filled.OpenInNew, null) }, onClick = { showMenu = false; openEntry(forceChooser = true) })
                                            }
                                            DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { showMenu = false; renamingEntry = entry })
                                            DropdownMenuItem(text = { Text("属性") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { showMenu = false; propertiesEntry = entry })
                                            HorizontalDivider()
                                            DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; deletingEntry = entry })
                                        }
                                    }
                                }
                            }
                            when (viewMode) {
                                ViewMode.DETAILS -> {
                                    Column {
                                        FileListItem(entry = entry, thumbnailUrl = thumbUrl, thumbnailKey = "local_${entry.path}", videoProgress = vProg?.let { if (it.durationMs > 0L) it.positionMs.toFloat() / it.durationMs else null }, videoDurationMs = durMs, videoPositionMs = posMs, isSelected = entry.path in state.selected, onClick = onItemClick, onLongClick = onItemLongClick, trailing = trailingMenu)
                                        HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                    }
                                }
                                ViewMode.GRID_LARGE -> { FileGridItem(entry = entry, onClick = onItemClick, onLongClick = onItemLongClick, isSelected = entry.path in state.selected, thumbnailUrl = thumbUrl, thumbnailKey = "local_${entry.path}", videoDurationMs = durMs, videoPositionMs = posMs, isLarge = true, trailing = trailingMenu) }
                                ViewMode.GRID_SMALL -> { FileGridItem(entry = entry, onClick = onItemClick, onLongClick = onItemLongClick, isSelected = entry.path in state.selected, thumbnailUrl = thumbUrl, thumbnailKey = "local_${entry.path}", videoDurationMs = durMs, videoPositionMs = posMs, isLarge = false, trailing = trailingMenu) }
                                ViewMode.COMPACT -> { FileCompactItem(entry = entry, onClick = onItemClick, onLongClick = onItemLongClick, isSelected = entry.path in state.selected, trailing = trailingMenu) }
                            }
                        }
                    }
                }
                if (pullRefreshState.verticalOffset > 0.5f || pullRefreshState.isRefreshing) {
                    PullToRefreshContainer(
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        indicator = { s ->
                            val rot = if (s.isRefreshing) refreshRotation.value else (s.verticalOffset * 5f) % 360f
                            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rot }, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
    }
    if (showMkdir) {
        AlertDialog(onDismissRequest = { showMkdir = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(value = mkdirName, onValueChange = { mkdirName = it }, label = { Text("名称") }) }, confirmButton = { TextButton(onClick = { if (mkdirName.isNotBlank()) vm.mkdir(mkdirName); showMkdir = false }) { Text("创建") } }, dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("取消") } })
    }
    openWithRequest?.let { req ->
        val dialogContext = androidx.compose.ui.platform.LocalContext.current
        OpenWithDialog(
            title = "打开 \"${req.entry.name}\"",
            candidates = req.candidates,
            onDismiss = { openWithRequest = null },
            onSelect = { candidate, always ->
                scope.launch {
                    if (always) FileOpener.setDefault(req.category, candidate)
                    val explicit = Intent(req.intent).apply { component = candidate.component; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
                    currentWatchingVideoKey = if (req.category == "video") req.entry.path else null
                    try { externalLauncher.launch(explicit) } catch (e: Exception) { FileOpener.openWith(dialogContext, req.intent, candidate) }
                }
                openWithRequest = null
            },
            onSystemChooser = {
                val clean = Intent(req.intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
                val chooser = Intent.createChooser(clean, "打开为").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
                currentWatchingVideoKey = if (req.category == "video") req.entry.path else null
                try { externalLauncher.launch(chooser) } catch (e: Exception) { FileOpener.openWithSystemChooser(dialogContext, req.intent) }
                openWithRequest = null
            }
        )
    }
    viewingImageIndex?.let { idx ->
        ImageViewerDialog(images = imageEntries, initialIndex = idx, baseUrl = null, authHeader = null, onDismiss = { viewingImageIndex = null })
    }
    if (showTrafficDebug) {
        com.example.myfile.ui.components.DebugTrafficDialog(onDismiss = { showTrafficDebug = false })
    }
    renamingEntry?.let { entry ->
        com.example.myfile.ui.components.FileRenameDialog(entry = entry, onDismiss = { renamingEntry = null }, onConfirm = { newName -> vm.rename(entry, newName) })
    }
    propertiesEntry?.let { entry ->
        val vProg = progressMap[entry.path]
        com.example.myfile.ui.components.FilePropertiesDialog(entry = entry, accountName = null, videoDurationMs = vProg?.durationMs, videoPositionMs = vProg?.positionMs, onDismiss = { propertiesEntry = null })
    }
    // 单项删除确认对话框
    deletingEntry?.let { entry ->
        com.example.myfile.ui.components.DeleteConfirmDialog(
            title = "确认删除",
            message = "确定要删除${if (entry.isDirectory) "文件夹" else "文件"} \"${entry.name}\" 吗？此操作无法撤销。",
            onDismiss = { deletingEntry = null },
            onConfirm = {
                vm.deleteOne(entry)
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
                withContext(Dispatchers.IO) {
                    val file = File(entry.path)
                    val text = file.readText(Charsets.UTF_8)
                    onProgress(file.length(), file.length(), text)
                    text
                }
            },
            onSave = { newText ->
                withContext(Dispatchers.IO) {
                    try {
                        File(entry.path).writeText(newText, Charsets.UTF_8)
                        vm.refresh()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            },
            onDismiss = { editingTextEntry = null }
        )
    }
}

private data class LocalOpenWithRequest(
    val entry: FileEntry,
    val category: String,
    val intent: Intent,
    val candidates: List<AppCandidate>
)

private data class LocalCrumb(val name: String, val file: File)

private fun buildLocalCrumbs(rootDir: File, currentDir: File): List<LocalCrumb> {
    val rootCanonical = try { rootDir.canonicalFile } catch (_: Exception) { rootDir }
    val curCanonical = try { currentDir.canonicalFile } catch (_: Exception) { currentDir }
    val crumbs = mutableListOf(LocalCrumb("内部存储", rootCanonical))
    if (curCanonical != rootCanonical && curCanonical.absolutePath.startsWith(rootCanonical.absolutePath)) {
        val rel = curCanonical.absolutePath.removePrefix(rootCanonical.absolutePath).trimStart(File.separatorChar, '/')
        var accum = rootCanonical
        rel.split(File.separatorChar).filter { it.isNotEmpty() }.forEach { part ->
            accum = File(accum, part)
            crumbs.add(LocalCrumb(part, accum))
        }
    }
    return crumbs
}
