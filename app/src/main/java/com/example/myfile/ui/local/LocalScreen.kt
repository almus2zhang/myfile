package com.example.myfile.ui.local

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
import com.example.myfile.core.AppCandidate
import com.example.myfile.core.FileOpener
import com.example.myfile.model.FileEntry
import com.example.myfile.ui.components.FileListItem
import com.example.myfile.ui.components.OpenWithDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(vm: LocalViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var openWithRequest by remember { mutableStateOf<LocalOpenWithRequest?>(null) }
    val scope = rememberCoroutineScope()

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
                        if (!forceChooser &&
                            FileOpener.openWithDefault(context.applicationContext, category, intent)
                        ) {
                            return@launch
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
                FileListItem(
                    entry = entry,
                    onClick = {
                        if (state.multiSelectMode) {
                            vm.toggleSelect(entry.path)
                        } else if (entry.isDirectory) {
                            vm.open(entry)
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
                    FileOpener.openWith(
                        context = com.example.myfile.MyApp.instance,
                        intent = req.intent,
                        candidate = candidate
                    )
                }
                openWithRequest = null
            },
            onSystemChooser = {
                FileOpener.openWithSystemChooser(context, req.intent)
                openWithRequest = null
            }
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
