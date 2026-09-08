package com.example.myfile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsState()

    val chunkOptions = listOf(
        1L * 1024 * 1024 to "1 MB",
        2L * 1024 * 1024 to "2 MB",
        4L * 1024 * 1024 to "4 MB",
        8L * 1024 * 1024 to "8 MB",
        16L * 1024 * 1024 to "16 MB",
        32L * 1024 * 1024 to "32 MB",
        64L * 1024 * 1024 to "64 MB"
    )
    var customChunk by remember { mutableStateOf("") }
    var showTrafficDebug by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 改名下载（伪装视频）开关 —— 常驻最顶部
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("改名下载（伪装视频加速）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "下载文件前临时在服务器端将后缀改成 .avi（支持视频与各类文件），绕过运营商限速，下载完成后自动改回原名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.renameToVideoExt,
                        onCheckedChange = { vm.updateRenameToVideoExt(it) }
                    )
                }
            }

            // 视频播放伪装 .avi 后缀开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("视频播放伪装 .avi 后缀加速", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在线播放视频时临时将服务器端文件改名为 .avi，加速流媒体加载与播放，退出播放时自动恢复原名，格式交由播放器解析",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.streamFakeAvi,
                        onCheckedChange = { vm.updateStreamFakeAvi(it) }
                    )
                }
            }

            // WebDAV 远程视频缩略图开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("WebDAV 远程视频缩略图", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "开启后进入网盘文件夹时尝试拉取视频首帧缩略图。注意：提取视频帧需持续从网盘拉取视频数据，会消耗网络流量与带宽（默认关闭以防偷跑流量）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.loadRemoteVideoThumbnails,
                        onCheckedChange = { vm.updateLoadRemoteVideoThumbnails(it) }
                    )
                }
            }

            // 视频文件时长与进度展示开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示视频时长与播放进度", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在视频文件日期的后方展示总时长及播放进度点（例如：01:45:20 或 32:10 / 01:45:20）。本地视频与已播放视频直接本地秒级读取，0 额外网络消耗",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = s.showVideoDuration,
                        onCheckedChange = { vm.updateShowVideoDuration(it) }
                    )
                }
            }

            // 实时网络传输监视器入口
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("实时网络传输监控 (Debug)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "查看当前正在进行的网络传输、实时网速、传输来源及历史请求，支持手动终止活跃连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { showTrafficDebug = true }) {
                        Text("打开监控")
                    }
                }
            }

            // 分片大小
            Section(title = stringResource(R.string.settings_chunk_size)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chunkOptions.forEach { (bytes, label) ->
                        FilterChip(
                            selected = s.chunkSize == bytes,
                            onClick = { vm.updateChunkSize(bytes) },
                            label = { Text(label) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customChunk,
                        onValueChange = { customChunk = it.filter(Char::isDigit) },
                        label = { Text("自定义 (KB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        customChunk.toLongOrNull()?.let { kb ->
                            val bytes = kb * 1024
                            if (bytes in (512 * 1024)..(256L * 1024 * 1024)) vm.updateChunkSize(bytes)
                        }
                    }) { Text("应用") }
                }
                val chunks100 = (100L * 1024 * 1024 + s.chunkSize - 1) / s.chunkSize
                Text(
                    "100MB 文件将切分为 $chunks100 片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 并发连接数
            Section(title = "${stringResource(R.string.settings_connections)}：${s.maxConnections}") {
                Slider(
                    value = s.maxConnections.toFloat(),
                    onValueChange = { vm.updateConnections(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14
                )
            }

            // 重试次数
            Section(title = "${stringResource(R.string.settings_retries)}：${s.maxRetries}") {
                Slider(
                    value = s.maxRetries.toFloat(),
                    onValueChange = { vm.updateRetries(it.toInt()) },
                    valueRange = 0f..10f,
                    steps = 9
                )
            }

            // 连接超时
            Section(title = stringResource(R.string.settings_connect_timeout)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = s.connectTimeoutSec.toString(),
                        onValueChange = { it.toLongOrNull()?.let { vm.updateConnectTimeout(it) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 读取超时
            Section(title = stringResource(R.string.settings_read_timeout)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = s.readTimeoutSec.toString(),
                        onValueChange = { it.toLongOrNull()?.let { vm.updateReadTimeout(it) } },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 单连接模式开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("单连接下载模式", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "不用 Range 分片，用单连接完整下载（模拟 CX 文件浏览器，可能绕过限速）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.singleConnectionMode,
                    onCheckedChange = { vm.updateSingleConnection(it) }
                )
            }

            // 流式节奏模式开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("流式节奏模式", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "每下载 4MB 停顿 1.5 秒（模拟边播边拉），绕过运营商对持续下载的限速",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.streamPulseMode,
                    onCheckedChange = { vm.updateStreamPulse(it) }
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_hint),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showTrafficDebug) {
        com.example.myfile.ui.components.DebugTrafficDialog(
            onDismiss = { showTrafficDebug = false }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
