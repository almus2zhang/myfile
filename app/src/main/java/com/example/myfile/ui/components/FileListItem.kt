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
    thumbnailUrl: Any? = null,
    thumbnailAuth: String? = null,
    thumbnailKey: String? = null,
    videoProgress: Float? = null,
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
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                val cKey = thumbnailKey ?: "thumb_${entry.path}"
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(thumbnailUrl)
                        .memoryCacheKey(cKey)
                        .diskCacheKey(cKey)
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
                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(iconFor(entry.isDirectory, category)),
                    fallback = androidx.compose.ui.graphics.vector.rememberVectorPainter(iconFor(entry.isDirectory, category)),
                    modifier = Modifier.fillMaxSize()
                )
                if (category == "video" && videoProgress != null && videoProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { videoProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconFor(entry.isDirectory, category),
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                    else iconTint(category),
                    modifier = Modifier.size(32.dp)
                )
                if (category == "video" && videoProgress != null && videoProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { videoProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
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
            val progressText = if (category == "video" && videoProgress != null && videoProgress > 0f) {
                "  ·  已看 ${(videoProgress * 100).toInt()}%"
            } else ""
            Text(
                text = if (entry.isDirectory) "文件夹 · ${fmt.format(Date(entry.lastModified))}"
                else "${formatSize(entry.size)}$progressText  ·  ${fmt.format(Date(entry.lastModified))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Filled.CheckCircle, "已选", tint = MaterialTheme.colorScheme.primary)
        } else if (trailing != null) {
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
