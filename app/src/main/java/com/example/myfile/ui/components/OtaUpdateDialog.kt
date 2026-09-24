package com.example.myfile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myfile.core.ApkInstaller
import com.example.myfile.core.ota.OtaManager
import com.example.myfile.core.ota.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun OtaUpdateDialog(
    updateInfo: UpdateInfo,
    onIgnoreVersion: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDownloading by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(updateInfo.apkSize) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    val cancelSignal = remember { AtomicBoolean(false) }

    val startDownload = {
        isDownloading = true
        errorMessage = null
        cancelSignal.set(false)
        scope.launch {
            val result = OtaManager.downloadApk(
                context = context,
                downloadUrl = updateInfo.downloadUrl,
                onProgress = { loaded, total ->
                    downloadedBytes = loaded
                    if (total > 0) totalBytes = total
                },
                isCanceled = cancelSignal
            )
            isDownloading = false
            result.onSuccess { file ->
                downloadedFile = file
                Toast.makeText(context, "下载完成，正在准备安装...", Toast.LENGTH_SHORT).show()
                ApkInstaller.install(context, file)
            }.onFailure { err ->
                if (!cancelSignal.get()) {
                    errorMessage = err.localizedMessage ?: "下载失败"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (isDownloading) cancelSignal.set(true)
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // 顶部图标与标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isDownloading) Icons.Filled.CloudDownload else Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "发现新版本",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "v${updateInfo.versionName} (Build ${updateInfo.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 版本信息元数据
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (updateInfo.apkSize > 0) {
                        Text(
                            text = "大小: ${formatSize(updateInfo.apkSize)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (updateInfo.releaseDate.isNotBlank()) {
                        Text(
                            text = "发布日期: ${updateInfo.releaseDate}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 更新日志展示区
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "更新内容：",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = updateInfo.changelog.ifBlank { "优化细节与修复已知问题" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 下载进度或错误信息
                if (isDownloading) {
                    val progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                    val percentage = (progress * 100).toInt()

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$percentage%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                errorMessage?.let { err ->
                    Text(
                        text = "更新失败: $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧：忽略此版本
                    if (onIgnoreVersion != null && !isDownloading && downloadedFile == null) {
                        TextButton(
                            onClick = {
                                onIgnoreVersion(updateInfo.versionCode)
                                Toast.makeText(context, "已忽略此版本更新", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = "忽略此版本",
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    // 右侧：稍后再说 / 取消下载 与 立即更新 / 立即安装
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                if (isDownloading) cancelSignal.set(true)
                                onDismiss()
                            }
                        ) {
                            Text(if (isDownloading) "取消下载" else "稍后再说")
                        }
                        Spacer(Modifier.width(8.dp))

                        if (downloadedFile != null && downloadedFile!!.exists()) {
                            Button(onClick = { ApkInstaller.install(context, downloadedFile!!) }) {
                                Text("立即安装")
                            }
                        } else if (isDownloading) {
                            // 正在下载中，无需再点更新
                        } else if (errorMessage != null) {
                            Button(onClick = { startDownload() }) {
                                Text("重试下载")
                            }
                        } else {
                            Button(onClick = { startDownload() }) {
                                Text("立即更新")
                            }
                        }
                    }
                }
            }
        }
    }
}
