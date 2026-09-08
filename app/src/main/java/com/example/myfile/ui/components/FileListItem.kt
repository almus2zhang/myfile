package com.example.myfile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.myfile.core.FileOpener
import com.example.myfile.model.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    entry: FileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    thumbnailUrl: String? = null,
    thumbnailAuth: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标 / 缩略图
        val category = FileOpener.fileCategory(entry.name)
        if (!entry.isDirectory && (category == "image" || category == "video") && thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(thumbnailUrl)
                    .apply {
                        if (thumbnailAuth != null) {
                            addHeader("Authorization", thumbnailAuth)
                        }
                        if (category == "video") videoFrameMillis(1000)
                    }
                    .crossfade(true)
                    .build(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Icon(
                imageVector = iconFor(entry.isDirectory, category),
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                else iconTint(category),
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            Text(
                text = if (entry.isDirectory) "文件夹 · ${fmt.format(Date(entry.lastModified))}"
                else "${formatSize(entry.size)}  ·  ${fmt.format(Date(entry.lastModified))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 根据类型返回图标 */
private fun iconFor(isDirectory: Boolean, category: String): ImageVector {
    if (isDirectory) return Icons.Filled.Folder
    return when (category) {
        "video" -> Icons.Filled.Movie
        "image" -> Icons.Filled.Image
        "audio" -> Icons.Filled.AudioFile
        "text" -> Icons.Filled.Article
        "archive" -> Icons.Filled.FolderZip
        "doc" -> Icons.Filled.Description
        "apk" -> Icons.Filled.Android
        else -> Icons.Filled.InsertDriveFile
    }
}

/** 图标着色 */
private fun iconTint(category: String): Color {
    return when (category) {
        "video" -> Color(0xFF7B1FA2)      // 紫
        "image" -> Color(0xFF388E3C)      // 绿
        "audio" -> Color(0xFFE65100)      // 橙
        "text" -> Color(0xFF1565C0)       // 蓝
        "archive" -> Color(0xFF6D4C41)    // 棕
        "doc" -> Color(0xFF0277BD)        // 深蓝
        "apk" -> Color(0xFF4CAF50)        // 绿
        else -> Color(0xFF757575)         // 灰
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatSizeStatic(bytes: Long): String = formatSize(bytes)
fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec) + "/s"
