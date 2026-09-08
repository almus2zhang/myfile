package com.example.myfile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myfile.model.FileEntry
import com.example.myfile.model.FileSource
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FileRenameDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialBaseName = if (entry.isDirectory) entry.name else entry.name.substringBeforeLast('.', entry.name)
    val textState = remember {
        mutableStateOf(
            TextFieldValue(
                text = entry.name,
                selection = TextRange(0, initialBaseName.length)
            )
        )
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("重命名${if (entry.isDirectory) "文件夹" else "文件"}")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = textState.value,
                    onValueChange = {
                        textState.value = it
                        errorMessage = null
                    },
                    label = { Text("新名称") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = {
                        if (errorMessage != null) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newName = textState.value.text.trim()
                    if (newName.isBlank()) {
                        errorMessage = "名称不能为空"
                    } else if (newName == entry.name) {
                        onDismiss()
                    } else if (newName.contains("/") || newName.contains("\\")) {
                        errorMessage = "名称不能包含斜杠"
                    } else {
                        onConfirm(newName)
                        onDismiss()
                    }
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun FilePropertiesDialog(
    entry: FileEntry,
    accountName: String? = null,
    videoDurationMs: Long? = null,
    videoPositionMs: Long? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val visualType = resolveVisualType(entry.isDirectory, entry.name)
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val dateStr = fmt.format(Date(entry.lastModified))
    val numFmt = NumberFormat.getNumberInstance(Locale.getDefault())

    val typeDesc = if (entry.isDirectory) {
        "文件夹"
    } else {
        val ext = entry.name.substringAfterLast('.', "").uppercase()
        when (visualType) {
            VisualType.VIDEO -> "视频文件 ($ext)"
            VisualType.IMAGE -> "图像文件 ($ext)"
            VisualType.AUDIO -> "音频文件 ($ext)"
            VisualType.APK -> "Android 安装包 (APK)"
            VisualType.PDF -> "PDF 文档"
            VisualType.WORD -> "Word 文档 ($ext)"
            VisualType.EXCEL -> "Excel 表格 ($ext)"
            VisualType.PPT -> "PowerPoint 演示文稿 ($ext)"
            VisualType.ARCHIVE -> "压缩文件 ($ext)"
            VisualType.CODE -> "源代码 / 文本 ($ext)"
            else -> if (ext.isNotBlank()) "$ext 文件" else "未知文件"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(visualType.tintColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = visualType.icon,
                        contentDescription = null,
                        tint = visualType.tintColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (entry.isDirectory) "文件夹属性" else "文件属性",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PropertyItem(label = "名称", value = entry.name)
                PropertyItem(label = "类型", value = typeDesc)
                if (!entry.isDirectory) {
                    PropertyItem(
                        label = "大小",
                        value = "${formatSize(entry.size)}  (${numFmt.format(entry.size)} 字节)"
                    )
                }
                PropertyItem(label = "修改时间", value = dateStr)

                if (visualType == VisualType.VIDEO && videoDurationMs != null && videoDurationMs > 0L) {
                    PropertyItem(label = "视频总时长", value = formatDuration(videoDurationMs))
                    if (videoPositionMs != null && videoPositionMs > 1000L) {
                        PropertyItem(
                            label = "播放进度",
                            value = "${formatDuration(videoPositionMs)} / ${formatDuration(videoDurationMs)}"
                        )
                    }
                }

                val locationDesc = when (entry.source) {
                    FileSource.LOCAL -> "本地存储"
                    FileSource.WEBDAV -> if (accountName != null) "WebDAV 网盘 ($accountName)" else "WebDAV 网盘"
                }
                PropertyItem(label = "存储位置", value = locationDesc)
                PropertyItem(label = "完整路径", value = entry.path, isPath = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("FilePath", entry.path))
                    Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("复制路径")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun PropertyItem(
    label: String,
    value: String,
    isPath: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = if (isPath) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = if (isPath) 17.sp else 20.sp
        )
    }
}

@Composable
fun DeleteConfirmDialog(
    title: String = "确认删除",
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("删除", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
