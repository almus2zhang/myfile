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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
    var showAccountDialog by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

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

    // 系统返回键：回到上一层目录
    BackHandler(enabled = state.currentPath != "/") { vm.goUp() }

    // 面包屑：/a/b/c -> [root, a, b, c]
    val crumbs = remember(state.currentPath) { buildCrumbs(state.currentPath) }
    // 顶部显示多级目录路径（对齐本地浏览规范）
    val displayPath = if (state.currentPath.isEmpty() || state.currentPath == "/") "/" else state.currentPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.currentAccount?.name ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (state.currentPath != "/") {
                        IconButton(onClick = { vm.goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "上级")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Filled.Sort, "排序") }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "刷新") }
                }
            )
        },
        floatingActionButton = {
            if (state.currentAccount != null) {
                FloatingActionButton(onClick = { showMkdir = true; mkdirName = "" }) {
                    Icon(Icons.Filled.CreateNewFolder, "新建文件夹")
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
                            Text(
                                acc.name,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        DropdownMenu(
                            expanded = showAccMenu,
                            onDismissRequest = { showAccMenu = false }
                        ) {
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
                                .clickable { vm.navigateTo(crumb.path) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.refresh() }) { Text("重试") }
                    }
                }
            } else if (state.currentAccount == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("点击上方「+ 添加」配置 WebDAV 账户", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val context = androidx.compose.ui.platform.LocalContext.current
                val acc = state.currentAccount
                val auth = acc?.let {
                    "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("${it.username}:${it.password}".toByteArray())
                }
                val base = acc?.url?.trimEnd('/') ?: ""
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sortedFiles, key = { it.path }) { entry: FileEntry ->
                        val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
                        val fullUrl = base + p
                        val category = FileOpener.fileCategory(entry.name)
                        val videoKey = acc?.let { "${it.url.trimEnd('/')}$p" } ?: entry.path

                        // 打开文件：先尝试 myfile 记录的默认程序，无则弹「打开方式」对话框
                        fun openEntry(forceChooser: Boolean) {
                            if (acc == null) return
                            scope.launch {
                                val appCtx = context.applicationContext
                                var intent = if (FileOpener.isVideo(entry.name)) {
                                    FileOpener.buildVideoStreamIntent(
                                        client = com.example.myfile.MyApp.instance.okHttpClient,
                                        account = acc,
                                        remotePath = entry.path,
                                        fileName = entry.name
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
                            videoProgress = progressMap[videoKey]?.let {
                                if (it.durationMs > 0L) it.positionMs.toFloat() / it.durationMs else null
                            },
                            onClick = {
                                if (entry.isDirectory) {
                                    vm.open(entry)
                                } else if (category == "image") {
                                    val idx = imageEntries.indexOfFirst { it.path == entry.path }
                                    if (idx >= 0) viewingImageIndex = idx
                                    else openEntry(forceChooser = false)
                                } else {
                                    openEntry(forceChooser = false)
                                }
                            },
                            trailing = {
                                if (!entry.isDirectory) {
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

    // 排序菜单
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false }
    ) {
        SortMode.entries.forEach { mode ->
            val isCurrent = state.sortMode == mode
            DropdownMenuItem(
                text = {
                    Text(
                        "${mode.label}${if (isCurrent) (if (state.sortAsc) " ↑" else " ↓") else ""}"
                    )
                },
                onClick = {
                    showSortMenu = false
                    vm.changeSort(mode)
                }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (state.sortAsc) "降序" else "升序") },
            onClick = {
                showSortMenu = false
                vm.changeSort(state.sortMode)
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
        val base = acc?.url?.trimEnd('/') ?: ""
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
