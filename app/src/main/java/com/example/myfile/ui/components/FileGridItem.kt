package com.example.myfile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.myfile.model.FileEntry

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    entry: FileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    thumbnailUrl: Any? = null,
    thumbnailAuth: String? = null,
    thumbnailKey: String? = null,
    videoDurationMs: Long? = null,
    videoPositionMs: Long? = null,
    isLarge: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    val visualType = resolveVisualType(entry.isDirectory, entry.name)
    val iconBoxSize = if (isLarge) 64.dp else 42.dp
    val iconSize = if (isLarge) 36.dp else 24.dp
    val cardRadius = if (isLarge) 12.dp else 8.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cardRadius))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(cardRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = if (isLarge) 10.dp else 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标 / 缩略图区域
                val hasThumbnail = !entry.isDirectory && (
                    visualType == VisualType.IMAGE ||
                    visualType == VisualType.VIDEO ||
                    visualType == VisualType.APK
                )

                val isApk = visualType == VisualType.APK
                val cKey = thumbnailKey ?: "thumb_${entry.path}"
                var isLoaded by remember(thumbnailUrl, cKey) { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .size(iconBoxSize)
                        .clip(RoundedCornerShape(if (isApk) 10.dp else 8.dp))
                        .background(
                            if (isApk) {
                                if (isLoaded) Color.Transparent else visualType.tintColor.copy(alpha = 0.15f)
                            } else if (hasThumbnail && thumbnailUrl != null) MaterialTheme.colorScheme.surfaceVariant
                            else visualType.tintColor.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isLoaded) {
                        Icon(
                            imageVector = visualType.icon,
                            contentDescription = null,
                            tint = visualType.tintColor,
                            modifier = Modifier.size(iconSize)
                        )
                    }

                    if (hasThumbnail && thumbnailUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(thumbnailUrl)
                                .memoryCacheKey(cKey)
                                .diskCacheKey(cKey)
                                .apply {
                                    if (thumbnailAuth != null) addHeader("Authorization", thumbnailAuth)
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
                                .padding(if (isApk) 2.dp else 0.dp)
                        )
                    }

                    // 视频总时长微底衬标签
                    if (visualType == VisualType.VIDEO && videoDurationMs != null && videoDurationMs > 0L) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color.Black.copy(alpha = 0.70f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                        ) {
                            Text(
                                text = formatDuration(videoDurationMs),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 0.5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isLarge) 8.dp else 4.dp))

                // 文件名
                Text(
                    text = entry.name,
                    style = if (isLarge) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    lineHeight = if (isLarge) 16.sp else 14.sp
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 大小 / 项数信息
                val subText = if (entry.isDirectory) {
                    "文件夹"
                } else if (visualType == VisualType.VIDEO && videoPositionMs != null && videoPositionMs > 1000L) {
                    formatDuration(videoPositionMs)
                } else {
                    formatSize(entry.size)
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // 选中标记 或 更多操作
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopEnd)
                )
            } else if (trailing != null) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    trailing()
                }
            }
        }
    }
}
