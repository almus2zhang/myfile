package com.example.myfile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myfile.MyApp
import com.example.myfile.core.ApkInstaller
import com.example.myfile.core.FileOpener
import java.io.File
import java.util.Locale

@Composable
fun ApkDownloadDialog(
    taskId: Long,
    fileName: String,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val taskFlow = remember(taskId) {
        MyApp.instance.db.downloadTaskDao().observeById(taskId)
    }
    val task by taskFlow.collectAsState(initial = null)
    val settings by MyApp.instance.currentSettings.collectAsState()

    var lastBytes by remember { mutableLongStateOf(0L) }
    var lastTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var speedText by remember { mutableStateOf("0 B/s") }

    LaunchedEffect(task?.downloadedBytes) {
        val currentBytes = task?.downloadedBytes ?: 0L
        val currentTime = System.currentTimeMillis()
        val timeDiff = (currentTime - lastTime).coerceAtLeast(1)
        if (timeDiff >= 400 && currentBytes > lastBytes) {
            val bytesDiff = currentBytes - lastBytes
            val speedBps = (bytesDiff * 1000) / timeDiff
            speedText = formatSpeed(speedBps)
            lastBytes = currentBytes
            lastTime = currentTime
        }
    }

    // 监听完成并自动安装 / 打开
    var hasHandledCompletion by remember { mutableStateOf(false) }
    LaunchedEffect(task?.status, task?.downloadedBytes, task?.totalBytes) {
        val currentTask = task ?: return@LaunchedEffect
        val isCompleted = currentTask.status == "COMPLETED" ||
                (currentTask.totalBytes > 0 && currentTask.downloadedBytes >= currentTask.totalBytes)
        if (isCompleted && !hasHandledCompletion) {
            hasHandledCompletion = true
            val file = File(currentTask.localPath)
            if (file.exists()) {
                if (fileName.endsWith(".apk", ignoreCase = true)) {
                    Toast.makeText(context, "下载完成，正在调起安装器...", Toast.LENGTH_SHORT).show()
                    ApkInstaller.install(context, file)
                } else {
                    Toast.makeText(context, "下载完成，正在打开文件...", Toast.LENGTH_SHORT).show()
                    FileOpener.open(context, file)
                }
            }
            onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                val isApk = fileName.endsWith(".apk", ignoreCase = true)
                val titleText = if (isApk) "加速下载安装包" else "加速下载文件"
                val headerIcon = if (isApk) Icons.Filled.Android else Icons.Filled.Download
                val headerBg = if (isApk) Color(0xFF43A047).copy(alpha = 0.14f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                val headerTint = if (isApk) Color(0xFF43A047) else MaterialTheme.colorScheme.primary

                // 顶部：图标 + 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(headerBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = headerIcon,
                            contentDescription = null,
                            tint = headerTint,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = fileName,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 伪装加速标签
                if (settings.renameToVideoExt) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⚡ 伪装 .avi 加速通道已启用 (自动防限速)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // 进度条
                val downloaded = task?.downloadedBytes ?: 0L
                val total = task?.totalBytes ?: 0L
                val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                val percent = (progress * 100).toInt()

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                // 数据统计行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val downloadedStr = formatSize(downloaded)
                    val totalStr = if (total > 0) formatSize(total) else "计算中..."
                    Text(
                        text = "$downloadedStr / $totalStr ($percent%)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (task?.status == "DOWNLOADING") speedText else (task?.status ?: "准备中"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 失败提示
                if (task?.status == "FAILED") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载失败: ${task?.errorMessage ?: "网络异常"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text(
                            text = "取消",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDismissRequest) {
                        Text("后台下载")
                    }
                }
            }
        }
    }
}
