package com.example.myfile.ui.local

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var openWithRequest by remember { mutableStateOf<LocalOpenWithRequest?>(null) }
    val scope = rememberCoroutineScope()

    val progressList by MyApp.instance.db.videoProgressDao().observeAll().collectAsState(initial = emptyList())
    val progressMap = remember(progressList) { progressList.associateBy { it.uriKey } }

    val imageEntries = remember(state.files) {
        state.files.filter { !it.isDirectory && FileOpener.fileCategory(it.name) == "image" }
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
                        IconButton(onClick = { vm.goUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                actions = {
                    if (!isRoot) {
                        IconButton(onClick = { vm.goUp() }) {
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
        bottomBar = {
            if (state.multiSelectMode) {
                BottomAppBar {
                    IconButton(onClick = { vm.deleteSelected() }) {
                        Icon(Icons.Filled.Delete, "删除")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("已选 ${state.selected.size} 项", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    ) { padding ->
        val context = androidx.compose.ui.platform.LocalContext.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(state.files, key = { it.path }) { entry: FileEntry ->
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
                val chooser = Intent.createChooser(req.intent, "打开为").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
