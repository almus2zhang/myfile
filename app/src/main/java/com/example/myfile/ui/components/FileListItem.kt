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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
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
            val painter = rememberAsyncImagePainter(
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
                    .build()
            )
            val isSuccess = painter.state is AsyncImagePainter.State.Success

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(if (isApk && !isSuccess) 12.dp else 10.dp))
                    .background(
                        if (isSuccess) {
                            if (isApk) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            visualType.tintColor.copy(alpha = 0.14f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSuccess) {
                    Image(
                        painter = painter,
                        contentDescription = entry.name,
                        contentScale = if (isApk) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isApk) 4.dp else 0.dp)
                    )
                } else {
                    Icon(
                        imageVector = visualType.icon,
                        contentDescription = null,
                        tint = visualType.tintColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // 柔和微边框，增强在浅色/深色背景下的视觉边界感
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (isApk && !isSuccess) 12.dp else 10.dp))
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(if (isApk && !isSuccess) 12.dp else 10.dp)
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

            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val dateStr = fmt.format(Date(entry.lastModified))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val metaText = if (entry.isDirectory) {
                    "文件夹  ·  $dateStr"
                } else {
                    "${formatSize(entry.size)}  ·  $dateStr"
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 观看进度独立微胶囊 Badge
                if (visualType == VisualType.VIDEO && videoProgress != null && videoProgress > 0f) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 2.dp)
                    ) {
                        Text(
                            text = "已看 ${(videoProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
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

