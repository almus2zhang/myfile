package com.example.myfile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

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
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
