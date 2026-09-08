package com.example.myfile.ui.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.model.TransferStatus
import com.example.myfile.model.TransferTask
import com.example.myfile.ui.components.ChunkProgressGrid
import com.example.myfile.ui.components.ProgressBar
import com.example.myfile.ui.components.formatSizeStatic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(vm: TransferViewModel = viewModel()) {
    val tasks by vm.tasks.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("进行中", "已完成", "已暂停")

    val filtered = when (tab) {
        0 -> tasks.filter {
            it.status == TransferStatus.DOWNLOADING || it.status == TransferStatus.QUEUED || it.status == TransferStatus.FAILED
        }
        1 -> tasks.filter { it.status == TransferStatus.COMPLETED }
        2 -> tasks.filter { it.status == TransferStatus.PAUSED }
        else -> tasks
    }

    Scaffold(topBar = { TopAppBar(title = { Text("传输管理") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { task -> TaskCard(task, vm) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TransferTask, vm: TransferViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress = if (task.totalBytes > 0) task.downloadedBytes.toFloat() / task.totalBytes else 0f
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressBar(progress = progress)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${formatSizeStatic(task.downloadedBytes)} / ${formatSizeStatic(task.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (task.localPath.isNotBlank()) {
                Text(
                    "保存位置: ${task.localPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            task.errorMessage?.let {
                Text("错误: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TransferStatus.DOWNLOADING, TransferStatus.QUEUED -> {
                        OutlinedButton(onClick = { vm.pause(task.id) }) {
                            Icon(Icons.Filled.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("暂停")
                        }
                        OutlinedButton(onClick = { vm.cancel(task.id) }) { Text("取消") }
                    }
                    TransferStatus.PAUSED, TransferStatus.FAILED -> {
                        OutlinedButton(onClick = { vm.resume(task.id) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("继续")
                        }
                        OutlinedButton(onClick = { vm.cancel(task.id) }) { Text("取消") }
                    }
                    TransferStatus.COMPLETED -> {
                        val isApk = task.fileName.endsWith(".apk", ignoreCase = true)
                        Button(onClick = {
                            val f = java.io.File(task.localPath)
                            if (f.exists()) {
                                if (isApk) {
                                    com.example.myfile.core.ApkInstaller.install(context, f)
                                } else {
                                    com.example.myfile.core.FileOpener.buildLocalViewIntent(context, f)?.let {
                                        try {
                                            context.startActivity(it)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }) {
                            Text(if (isApk) "安装" else "打开文件")
                        }
                    }
                    else -> {}
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.delete(task.id) }) {
                    Icon(Icons.Filled.Delete, "删除记录")
                }
            }
        }
    }
}
