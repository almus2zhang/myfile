package com.example.myfile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.myfile.model.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 精美文件列表条目组件：
 * - 44dp 柔和圆角卡片底衬徽章图标
 * - 细分格式色彩与高质感分类
 * - 规范化双行排版与清晰元数据层级
 * - 视频观看进度独立微胶囊 Badge
 */
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
    videoDurationMs: Long? = null,
    videoPositionMs: Long? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val visualType = resolveVisualType(entry.isDirectory, entry.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标 / 缩略图区域
        val hasThumbnail = !entry.isDirectory && (
            visualType == VisualType.IMAGE ||
            visualType == VisualType.VIDEO ||
            visualType == VisualType.APK
        )
        if (hasThumbnail && thumbnailUrl != null) {
            val isApk = visualType == VisualType.APK
            val cKey = thumbnailKey ?: "thumb_${entry.path}"
            var isLoaded by remember(thumbnailUrl, cKey) { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(if (isApk) 12.dp else 10.dp))
                    .background(
                        if (isApk) {
                            if (isLoaded) Color.Transparent else visualType.tintColor.copy(alpha = 0.14f)
                        } else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 仅在未加载成功时显示底层默认彩色类别图标（加载中或失败时显示，避免与真实图标重叠）
                if (!isLoaded) {
                    Icon(
                        imageVector = visualType.icon,
                        contentDescription = null,
                        tint = visualType.tintColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // 顶层：Coil 异步加载图片、视频与 APK 真实缩略图
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(thumbnailUrl)
                        .memoryCacheKey(cKey)
                        .diskCacheKey(cKey)
                        .apply {
                            if (thumbnailAuth != null) {
                                addHeader("Authorization", thumbnailAuth)
                            }
                            if (visualType == VisualType.VIDEO) videoFrameMillis(1000)
                        }
                        .crossfade(true)
                        .build(),
                    contentDescription = entry.name,
                    contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop,
                    onSuccess = { isLoaded = true },
                    onError = { isLoaded = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (isApk) 12.dp else 10.dp))
                        .padding(if (isApk) 2.dp else 0.dp)
                )

                // 柔和微边框，增强在浅色/深色背景下的视觉边界感
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (isApk) 12.dp else 10.dp))
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(if (isApk) 12.dp else 10.dp)
                        )
                )

                if (visualType == VisualType.VIDEO && videoProgress != null && videoProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { videoProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .align(Alignment.BottomCenter),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Black.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            // 质感卡片底衬徽章
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(visualType.tintColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visualType.icon,
                    contentDescription = null,
                    tint = visualType.tintColor,
                    modifier = Modifier.size(26.dp)
                )
                if (visualType == VisualType.VIDEO && videoProgress != null && videoProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { videoProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = visualType.tintColor,
                        trackColor = visualType.tintColor.copy(alpha = 0.2f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 文本信息区域
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val cal = java.util.Calendar.getInstance().apply { timeInMillis = entry.lastModified }
            val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val dateFmt = if (cal.get(java.util.Calendar.YEAR) == curYear) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            } else {
                SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())
            }
            val dateStr = dateFmt.format(Date(entry.lastModified))

            val timeInfo = if (visualType == VisualType.VIDEO) {
                if (videoDurationMs != null && videoDurationMs > 0L) {
                    if (videoPositionMs != null && videoPositionMs > 1000L) {
                        "${formatDuration(videoPositionMs)} / ${formatDuration(videoDurationMs)}"
                    } else {
                        formatDuration(videoDurationMs)
                    }
                } else if (videoPositionMs != null && videoPositionMs > 1000L) {
                    formatDuration(videoPositionMs)
                } else null
            } else null

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val metaText = if (entry.isDirectory) {
                    "文件夹 · $dateStr"
                } else if (timeInfo != null) {
                    "${formatSize(entry.size)} · $dateStr · $timeInfo"
                } else {
                    "${formatSize(entry.size)} · $dateStr"
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        } else if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            trailing()
        }
    }
}

/**
 * 文件视觉类型定义
 */
enum class VisualType(
    val icon: ImageVector,
    val tintColor: Color
) {
    FOLDER(Icons.Filled.Folder, Color(0xFFFFA000)),         // 暖金琥珀
    VIDEO(Icons.Filled.Movie, Color(0xFF7C4DFF)),          // 优雅紫罗兰
    IMAGE(Icons.Filled.Image, Color(0xFF00897B)),          // 清新翡翠绿
    AUDIO(Icons.Filled.AudioFile, Color(0xFFFF5722)),      // 活力珊瑚橙
    PDF(Icons.Filled.PictureAsPdf, Color(0xFFE53935)),     // 专业朱砂红
    WORD(Icons.Filled.Description, Color(0xFF1E88E5)),     // 商务湛蓝
    EXCEL(Icons.Filled.TableChart, Color(0xFF2E7D32)),     // 办公森林绿
    PPT(Icons.Filled.Slideshow, Color(0xFFFB8C00)),        // 演示暖橙
    CODE(Icons.Filled.Code, Color(0xFF0097A7)),            // 科技深青
    ARCHIVE(Icons.Filled.FolderZip, Color(0xFF6D4C41)),    // 复古质感棕
    APK(Icons.Filled.Android, Color(0xFF43A047)),          // Android 质感绿
    OTHER(Icons.Filled.InsertDriveFile, Color(0xFF78909C)) // 现代板岩灰
}

/**
 * 根据目录与扩展名解析出细致的视觉分类
 */
fun resolveVisualType(isDirectory: Boolean, fileName: String): VisualType {
    if (isDirectory) return VisualType.FOLDER
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v",
        "mpg", "mpeg", "3gp", "rmvb", "rm", "vob", "m2ts", "iso" -> VisualType.VIDEO

        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "ico", "raw", "dng" -> VisualType.IMAGE

        "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "ape", "alac", "mid", "midi" -> VisualType.AUDIO

        "pdf" -> VisualType.PDF

        "doc", "docx", "dot", "dotx", "rtf", "odt", "pages", "wps" -> VisualType.WORD

        "xls", "xlsx", "xlt", "xltx", "csv", "tsv", "ods", "numbers", "et" -> VisualType.EXCEL

        "ppt", "pptx", "pot", "potx", "pps", "odp", "key", "keynote", "dps" -> VisualType.PPT

        "txt", "log", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "ts",
        "jsx", "tsx", "kt", "kts", "java", "py", "c", "cpp", "h", "hpp", "go", "rs",
        "sh", "bash", "zsh", "yaml", "yml", "ini", "conf", "properties", "sql", "gradle" -> VisualType.CODE

        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "z", "cab", "dmg", "7-zip" -> VisualType.ARCHIVE

        "apk", "xapk", "apks", "aab" -> VisualType.APK

        else -> VisualType.OTHER
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatSizeStatic(bytes: Long): String = formatSize(bytes)
fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec) + "/s"

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hours = totalSec / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, min, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", min, sec)
    }
}

