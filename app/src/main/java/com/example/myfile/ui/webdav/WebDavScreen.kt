package com.example.myfile.ui.webdav

import androidx.activity.compose.BackHandler
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
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.model.FileEntry
import kotlinx.coroutines.launch
import com.example.myfile.ui.components.FileListItem
import com.example.myfile.ui.components.OpenWithDialog
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

    // 「打开方式」选择对话框状态
    var openWithRequest by remember { mutableStateOf<OpenWithRequest?>(null) }
    val scope = rememberCoroutineScope()

    // 系统返回键：回到上一层目录
    BackHandler(enabled = state.currentPath != "/") { vm.goUp() }

    // 面包屑：/a/b/c -> [root, a, b, c]
    val crumbs = remember(state.currentPath) { buildCrumbs(state.currentPath) }
    // 当前目录名（本层，不显示完整路径）
    val currentDirName = crumbs.lastOrNull()?.name ?: "根目录"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(currentDirName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        // 打开文件：先尝试 myfile 记录的默认程序，无则弹「打开方式」对话框
                        fun openEntry(forceChooser: Boolean) {
                            if (acc == null) return
                            val category = FileOpener.fileCategory(entry.name)
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

                                // 有默认程序且非「打开为」→ 直接用默认程序打开
                                val finalIntent = intent ?: return@launch
                                val finalCandidates = candidates
                                if (!forceChooser &&
                                    FileOpener.openWithDefault(appCtx, category, finalIntent)
                                ) {
                                    return@launch
                                }
                                // 弹「打开方式」选择对话框
                                openWithRequest = OpenWithRequest(
                                    entry = entry,
                                    category = category,
                                    intent = finalIntent,
                                    candidates = finalCandidates
                                )
                            }
                        }
                        FileListItem(
                            entry = entry,
                            thumbnailUrl = if (!entry.isDirectory) fullUrl else null,
                            thumbnailAuth = auth,
                            onClick = {
                                if (entry.isDirectory) vm.open(entry)
                                else openEntry(forceChooser = false)
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
        OpenWithDialog(
            title = "打开 \"${req.entry.name}\"",
            candidates = req.candidates,
            onDismiss = { openWithRequest = null },
            onSelect = { candidate, always ->
                scope.launch {
                    if (always) {
                        FileOpener.setDefault(req.category, candidate)
                    }
                    FileOpener.openWith(
                        context = com.example.myfile.MyApp.instance,
                        intent = req.intent,
                        candidate = candidate
                    )
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
}

/** 「打开方式」对话框的请求数据 */
private data class OpenWithRequest(
    val entry: FileEntry,
    val category: String,
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
