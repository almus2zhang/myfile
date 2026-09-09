package com.example.myfile.ui.webdav

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.MyApp
import com.example.myfile.core.ApkInstaller
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.core.StreamProxy
import com.example.myfile.data.db.entity.VideoProgressEntity
import com.example.myfile.model.FileEntry
import com.example.myfile.ui.components.FileListItem
import com.example.myfile.ui.components.ImageViewerDialog
import com.example.myfile.ui.components.OpenWithDialog
import com.example.myfile.ui.components.ApkDownloadDialog
import com.example.myfile.ui.components.AccountUnlockDialog
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
    var pendingUnlockAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    var pendingUnlockForEdit by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }

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

    val showThumbnailsAndDuration by vm.showThumbnailsAndDuration.collectAsState()
    val durationRefreshTrigger by vm.durationRefreshTrigger.collectAsState()
    var lastProcessedTrigger by remember { mutableStateOf(0) }

    // 缩略图与时长是否启用：简洁视图不显示；其余视图受 showThumbnailsAndDuration 控制
    val shouldLoadMedia = showThumbnailsAndDuration && (viewMode != ViewMode.COMPACT)

    // WebDAV 视频：通过 StreamProxy 后台异步探查视频时长（走 OkHttp 并在传输监视器中实时显示，零全量下载）
    LaunchedEffect(state.files, shouldLoadMedia, durationRefreshTrigger) {
        if (!shouldLoadMedia) return@LaunchedEffect
        val acc = state.currentAccount ?: return@LaunchedEffect
        val videoEntries = state.files.filter { !it.isDirectory && FileOpener.isVideo(it.name) }
        if (videoEntries.isNotEmpty()) {
            val isForce = (durationRefreshTrigger != lastProcessedTrigger)
            lastProcessedTrigger = durationRefreshTrigger
            withContext(Dispatchers.IO) {
                for (v in videoEntries) {
                    com.example.myfile.core.StreamProxy.probeDuration(
                        client = MyApp.instance.okHttpClient,
                        account = acc,
                        remotePath = v.path,
                        force = isForce
                    )
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
                    // 左侧：当前配置名按钮（无默认底色，点击时带淡淡波纹底色提示）
                    Box {
                        Surface(
                            onClick = { showAccountMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = state.currentAccount?.name ?: "选择配置",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
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
                                            if (acc.isEncrypted) {
                                                Icon(
                                                    Icons.Filled.Lock,
                                                    contentDescription = "已加密",
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
                                                    if (acc.isEncrypted && acc.id != state.currentAccount?.id) {
                                                        pendingUnlockForEdit = acc
                                                    } else {
                                                        editingAccount = acc
                                                        showAccountDialog = true
                                                    }
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
                                        if (acc.isEncrypted && acc.id != state.currentAccount?.id) {
                                            pendingUnlockAccount = acc
                                        } else {
                                            vm.selectAccount(acc)
                                        }
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

                    Spacer(Modifier.width(4.dp))

                    // 在配置列表右边是常用路径列表
                    var showFavoritesMenu by remember { mutableStateOf(false) }
                    val currentAcc = state.currentAccount
                    val favoriteKey = remember(currentAcc?.id) {
                        currentAcc?.let { com.example.myfile.data.prefs.FavoritePathStore.buildWebDavKey(it.id) }
                    }
                    var favoritesList by remember { mutableStateOf(emptyList<String>()) }
                    LaunchedEffect(favoriteKey, showFavoritesMenu) {
                        if (favoriteKey != null) {
                            favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                        } else {
                            favoritesList = emptyList()
                        }
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
                                    if (favoriteKey != null) {
                                        MyApp.instance.favoritePathStore.addFavorite(favoriteKey, state.currentPath)
                                        favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已保存当前路径到常用路径")
                                        }
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
                                    val isCurrent = favPath == state.currentPath
                                    val displayName = if (favPath == "/") "根目录 (/)" else favPath
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
                                                    if (favoriteKey != null) {
                                                        MyApp.instance.favoritePathStore.removeFavorite(favoriteKey, favPath)
                                                        favoritesList = MyApp.instance.favoritePathStore.getFavorites(favoriteKey)
                                                    }
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
                                            vm.navigateTo(favPath)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 速度显示在四个图标前面
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
                                Icon(
                                    Icons.Filled.Speed,
                                    contentDescription = "网速",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
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

                    // 右侧四个操作按钮（稍微放大至 40dp，图标 24dp）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 视图模式切换按钮
                        Box {
                            IconButton(
                                onClick = { showViewModeMenu = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = viewMode.icon,
                                    contentDescription = "切换视图",
                                    modifier = Modifier.size(24.dp)
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
                                                modifier = Modifier.size(22.dp)
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

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 是否显示缩略图和时长开关
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "显示缩略图和时长",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Image,
                                            contentDescription = null,
                                            tint = if (showThumbnailsAndDuration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = showThumbnailsAndDuration,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        vm.toggleShowThumbnailsAndDuration()
                                    }
                                )

                                // 强制重新获取时长
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "强制重新获取时长",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    onClick = {
                                        showViewModeMenu = false
                                        android.widget.Toast.makeText(context, "正在重新获取当前目录视频时长...", android.widget.Toast.LENGTH_SHORT).show()
                                        vm.forceRefreshDurations()
                                    }
                                )
                            }
                        }

                        // 2. 排序按钮
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.Sort, "排序", modifier = Modifier.size(24.dp))
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
                            modifier = Modifier.size(40.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (activeTransfers.isNotEmpty()) {
                                        Badge { Text("${activeTransfers.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Speed, "网络传输监控", modifier = Modifier.size(24.dp))
                            }
                        }

                        // 4. 刷新按钮
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(24.dp))
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
            // 文件选择后底部菜单：按钮下加文字，不要高亮。全选，复制，粘贴，删除，取消
            if (state.multiSelectMode || clipboardItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 全选
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = state.sortedFiles.isNotEmpty()) { vm.selectAll() }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SelectAll,
                                contentDescription = "全选",
                                tint = if (state.sortedFiles.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "全选",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (state.sortedFiles.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // 2. 复制
                        val canCopy = state.selected.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = canCopy) { vm.copySelected() }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "复制",
                                tint = if (canCopy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (canCopy) "复制(${state.selected.size})" else "复制",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (canCopy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // 3. 剪切
                        val canCut = state.selected.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = canCut) { vm.cutSelected() }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCut,
                                contentDescription = "剪切",
                                tint = if (canCut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (canCut) "剪切(${state.selected.size})" else "剪切",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (canCut) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // 3. 粘贴
                        val canPaste = clipboardItems.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = canPaste) {
                                    vm.pasteHere(context) {
                                        com.example.myfile.core.TransferClipboard.clear()
                                        vm.clearSelection()
                                    }
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentPaste,
                                contentDescription = "粘贴",
                                tint = if (canPaste) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (canPaste) "粘贴(${clipboardItems.size})" else "粘贴",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (canPaste) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // 4. 删除
                        val canDelete = state.selected.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = canDelete) { showBatchDeleteConfirm = true }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }

                        // 5. 取消
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    vm.clearSelection()
                                    com.example.myfile.core.TransferClipboard.clear()
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "取消",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "取消",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 第二行：路径面包屑栏，采用微胶囊风格与平滑横向滚动，方便逐级点击跳转
            val crumbs = remember(state.currentPath) { buildCrumbs(state.currentPath) }
            if (state.currentAccount != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Text(
                                    text = "›",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                            val isCurrent = index == crumbs.lastIndex
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (!isCurrent) {
                                            vm.saveScrollPosition(state.currentPath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                            vm.navigateTo(crumb.path)
                                        }
                                    }
                            ) {
                                Text(
                                    text = crumb.name,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
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
                            ViewMode.DETAILS -> GridCells.Adaptive(minSize = 340.dp)
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
                                        com.example.myfile.core.TrafficMonitor.debug("openEntry: ${entry.name}, isVid=$isVid, cat=$category, force=$forceChooser")
                                        val fakeAvi = acc.streamFakeAvi
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
                                            val dir = File(
                                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                                "myfile"
                                            )
                                            val downloadedFile = File(dir, entry.name)
                                            val fileToOpen = if (downloadedFile.exists() && (entry.size <= 0 || downloadedFile.length() == entry.size)) {
                                                downloadedFile
                                            } else {
                                                Toast.makeText(context, "正在下载 ${entry.name}...", Toast.LENGTH_SHORT).show()
                                                val downloaded = FileOpener.downloadToCache(
                                                     client = com.example.myfile.MyApp.instance.okHttpClient,
                                                     authHeader = auth ?: "",
                                                     url = fullUrl,
                                                     fileName = entry.name
                                                )
                                                if (downloaded == null) {
                                                    Toast.makeText(context, "下载失败，请检查网络", Toast.LENGTH_SHORT).show()
                                                    return@launch
                                                }
                                                downloaded
                                            }
                                            if (category == "apk") {
                                                ApkInstaller.install(context, fileToOpen)
                                                return@launch
                                            }
                                            FileOpener.buildLocalViewIntent(appCtx, fileToOpen)
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
                                        com.example.myfile.core.TrafficMonitor.debug("默认应用检查: cat=$category, app=$defaultApp")
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
                                                    com.example.myfile.core.TrafficMonitor.debug("启动默认应用: ${parts[0]}/${parts[1]}")
                                                    externalLauncher.launch(explicit)
                                                    return@launch
                                                } catch (e: Exception) {
                                                    com.example.myfile.core.TrafficMonitor.debug("启动默认异常: ${e.message}")
                                                    currentWatchingVideoKey = null
                                                }
                                            }
                                        }

                                        if (!forceChooser && finalCandidates.size == 1) {
                                            val explicit = Intent(finalIntent).apply {
                                                component = finalCandidates[0].component
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                                            }
                                            currentWatchingVideoKey = if (category == "video") videoKey else null
                                            try {
                                                externalLauncher.launch(explicit)
                                                return@launch
                                            } catch (_: Exception) {
                                            }
                                        }

                                        com.example.myfile.core.TrafficMonitor.debug("弹出选择器: cat=$category, 候选=${finalCandidates.size}")
                                        openWithRequest = OpenWithRequest(
                                            entry = entry,
                                            category = category,
                                            videoKey = videoKey,
                                            intent = finalIntent,
                                            candidates = finalCandidates
                                        )
                                    }
                                }

                                fun startAcceleratedDownload(entryToDownload: FileEntry, forceRename: Boolean = false) {
                                    if (acc == null) return
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
                                                entryToDownload.path,
                                                entryToDownload.name,
                                                dir,
                                                knownSize = entryToDownload.size,
                                                forceRename = forceRename
                                            )
                                            downloadingApkFileName = entryToDownload.name
                                            downloadingApkTaskId = taskId
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("启动加速下载失败: ${e.message}")
                                        }
                                    }
                                }

                                val onItemClick = {
                                    if (state.multiSelectMode) {
                                        vm.toggleSelect(entry.path)
                                    } else if (entry.isDirectory) {
                                        vm.saveScrollPosition(state.currentPath, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                                        vm.open(entry)
                                    } else if (category == "image") {
                                        val idx = imageEntries.indexOfFirst { it.path == entry.path }
                                        if (idx >= 0) viewingImageIndex = idx
                                        else openEntry(forceChooser = false)
                                    } else if (FileOpener.isText(entry.name)) {
                                        // 内置文本浏览和编辑器
                                        editingTextEntry = entry
                                    } else if (category == "video") {
                                        com.example.myfile.core.TrafficMonitor.debug("点击视频: ${entry.name}")
                                        openEntry(forceChooser = false)
                                    } else if ((acc?.renameToVideoExt == true) && (category == "apk" || entry.size > 5 * 1024 * 1024L)) {
                                        // 配置开启改名加速下载时：apk 或大于 5M 的其他文件采用加速下载方式
                                        val localDownloaded = File(
                                            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "myfile"),
                                            entry.name
                                        )
                                        if (localDownloaded.exists() && (entry.size <= 0 || localDownloaded.length() == entry.size)) {
                                            if (category == "apk") {
                                                ApkInstaller.install(context, localDownloaded)
                                            } else {
                                                openEntry(forceChooser = false)
                                            }
                                        } else {
                                            startAcceleratedDownload(entry, forceRename = false)
                                        }
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
                                                    // 三个点点击后的加速下载永远生效
                                                    DropdownMenuItem(
                                                        text = { Text("加速下载") },
                                                        leadingIcon = { Icon(Icons.Filled.Download, null) },
                                                        onClick = {
                                                            showMenu = false
                                                            startAcceleratedDownload(entry, forceRename = true)
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
                                    ViewMode.DETAILS -> {
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

    pendingUnlockAccount?.let { accToUnlock ->
        AccountUnlockDialog(
            account = accToUnlock,
            onUnlockSuccess = {
                pendingUnlockAccount = null
                vm.selectAccount(accToUnlock)
            },
            onDismiss = { pendingUnlockAccount = null }
        )
    }

    pendingUnlockForEdit?.let { accToUnlock ->
        AccountUnlockDialog(
            account = accToUnlock,
            onUnlockSuccess = {
                pendingUnlockForEdit = null
                editingAccount = accToUnlock
                showAccountDialog = true
            },
            onDismiss = { pendingUnlockForEdit = null }
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
                com.example.myfile.core.TrafficMonitor.debug("选择应用: ${candidate.packageName}/${candidate.activityName}, always=$always")
                if (always) {
                    MyApp.instance.appScope.launch {
                        com.example.myfile.core.TrafficMonitor.debug("写入默认开始: ${req.category}")
                        FileOpener.setDefault(req.category, candidate)
                        com.example.myfile.core.TrafficMonitor.debug("写入默认完成: ${req.category}")
                    }
                }
                scope.launch {
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
        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
        val videoKey = state.currentAccount?.let { "acc_${it.id}$p" } ?: entry.path
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
