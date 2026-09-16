package com.example.myfile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.io.File
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
    localFile: File? = null,
    downloadHint: String? = null,
    onDownload: (() -> Unit)? = null,
    onUpload: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val visualType = resolveVisualType(entry.isDirectory, entry.name)
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val numFmt = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }

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

    val isWebDavFile = entry.source == FileSource.WEBDAV && !entry.isDirectory
    val resolvedLocalFile = if (isWebDavFile) {
        localFile ?: remember(entry.name) {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "myfile"
            ).let { File(it, entry.name) }
        }
    } else null

    val localExists = resolvedLocalFile?.exists() == true
    val localSize = if (localExists) (resolvedLocalFile?.length() ?: -1L) else -1L
    val localTime = if (localExists) (resolvedLocalFile?.lastModified() ?: -1L) else -1L

    val sameSize = localExists && (entry.size >= 0L && localSize == entry.size)
    val sameTime = if (localExists && entry.lastModified > 0L && localTime > 0L) {
        Math.abs(localTime - entry.lastModified) < 2000L
    } else {
        localExists && entry.size > 0L && localSize == entry.size
    }
    val isLocalNewer = localExists && entry.lastModified > 0L && localTime > entry.lastModified + 2000L
    val isRemoteNewer = localExists && localTime > 0L && entry.lastModified > localTime + 2000L

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
                    text = if (entry.isDirectory) "文件夹属性" else if (isWebDavFile) "文件属性与缓存对比" else "文件属性",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 基本信息
                PropertyItem(label = "文件名称", value = entry.name)
                PropertyItem(label = "文件类型", value = typeDesc)

                if (visualType == VisualType.VIDEO && videoDurationMs != null && videoDurationMs > 0L) {
                    PropertyItem(label = "视频总时长", value = formatDuration(videoDurationMs))
                    if (videoPositionMs != null && videoPositionMs > 1000L) {
                        PropertyItem(
                            label = "播放进度",
                            value = "${formatDuration(videoPositionMs)} / ${formatDuration(videoDurationMs)}"
                        )
                    }
                }

                if (isWebDavFile && resolvedLocalFile != null) {
                    // 对比状态总览 Banner
                    if (!localExists) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "本地无缓存文件，点击下方「下载」可缓存到本地",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (sameSize && sameTime) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF4CAF50).copy(alpha = 0.12f),
                            border = BorderStroke(0.8.dp, Color(0xFF4CAF50).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "本地已缓存（大小与修改时间完全一致）",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color(0xFF1B5E20)
                                )
                            }
                        }
                    } else {
                        val diffDesc = buildList {
                            if (!sameSize) add("大小不一致")
                            if (isLocalNewer) add("本地更新")
                            else if (isRemoteNewer) add("远程更新")
                            else if (!sameTime) add("时间不一致")
                        }.joinToString("，")

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "本地缓存存在差异 ($diffDesc)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // 远程属性卡片
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Cloud,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (accountName != null) "远程属性 ($accountName)" else "远程属性",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                            PropertyItem(
                                label = "文件大小",
                                value = "${formatSize(entry.size)} (${numFmt.format(entry.size)} 字节)"
                            )
                            PropertyItem(
                                label = "修改时间",
                                value = fmt.format(Date(entry.lastModified))
                            )
                            PropertyItem(
                                label = "远程路径",
                                value = entry.path,
                                isPath = true
                            )
                        }
                    }

                    // 本地缓存属性卡片
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Save,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "本地缓存属性",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (localExists) {
                                        if (sameSize && sameTime) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                ) {
                                    Text(
                                        text = if (!localExists) "未缓存" else if (sameSize && sameTime) "完全一致" else "有差异",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (!localExists) MaterialTheme.colorScheme.onSurfaceVariant
                                                else if (sameSize && sameTime) Color(0xFF2E7D32)
                                                else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                            if (localExists) {
                                val sizeBadgeText = if (sameSize) "大小一致" else "大小不一致"
                                val sizeBadgeColor = if (sameSize) Color(0xFF2E7D32) else Color(0xFFE65100)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        PropertyItem(
                                            label = "文件大小",
                                            value = "${formatSize(localSize)} (${numFmt.format(localSize)} 字节)"
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = sizeBadgeColor.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = sizeBadgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = sizeBadgeColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val timeBadgeText = if (sameTime) "时间相同" else if (isLocalNewer) "本地较新" else if (isRemoteNewer) "远程较新" else "时间不同"
                                val timeBadgeColor = if (sameTime) Color(0xFF2E7D32) else if (isLocalNewer) MaterialTheme.colorScheme.primary else Color(0xFFE65100)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        PropertyItem(
                                            label = "修改时间",
                                            value = fmt.format(Date(localTime))
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = timeBadgeColor.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = timeBadgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = timeBadgeColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                PropertyItem(
                                    label = "本地缓存路径",
                                    value = resolvedLocalFile.absolutePath,
                                    isPath = true
                                )
                            } else {
                                Text(
                                    text = "本地尚未下载此文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                PropertyItem(
                                    label = "默认缓存路径",
                                    value = resolvedLocalFile.absolutePath,
                                    isPath = true
                                )
                            }
                        }
                    }
                } else {
                    // 常规非对比属性展示（文件夹 或 本地存储条目）
                    if (!entry.isDirectory) {
                        PropertyItem(
                            label = "大小",
                            value = "${formatSize(entry.size)} (${numFmt.format(entry.size)} 字节)"
                        )
                    }
                    PropertyItem(label = "修改时间", value = fmt.format(Date(entry.lastModified)))

                    val locationDesc = when (entry.source) {
                        FileSource.LOCAL -> "本地存储"
                        FileSource.WEBDAV -> if (accountName != null) "WebDAV 网盘 ($accountName)" else "WebDAV 网盘"
                    }
                    PropertyItem(label = "存储位置", value = locationDesc)
                    PropertyItem(label = "完整路径", value = entry.path, isPath = true)
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onUpload != null) {
                    OutlinedButton(
                        onClick = onUpload,
                        enabled = localExists
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("上传")
                    }
                }
                if (onDownload != null) {
                    Button(
                        onClick = onDownload
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (!downloadHint.isNullOrBlank()) "下载 ($downloadHint)" else "下载")
                    }
                }
                if (onDownload == null && onUpload == null) {
                    Button(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        },
        dismissButton = {
            if (onDownload != null || onUpload != null) {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            } else {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("FilePath", entry.path))
                        Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制路径")
                }
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
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (isPath) {
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("FilePath", value))
                        Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制路径",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
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
    sizeText: String? = null,
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
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                if (!sizeText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "占用空间: ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = sizeText,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
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
