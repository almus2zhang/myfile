package com.example.myfile.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.myfile.model.FileEntry

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerDialog(
    images: List<FileEntry>,
    initialIndex: Int,
    baseUrl: String? = null,
    authHeader: String? = null,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        val safeInitial = initialIndex.coerceIn(0, images.size - 1)
        val pagerState = rememberPagerState(initialPage = safeInitial) { images.size }
        var showChrome by remember { mutableStateOf(true) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 1
            ) { page ->
                val entry = images[page]
                val imgUrl = if (baseUrl != null) {
                    val p = if (entry.path.startsWith("/")) entry.path else "/${entry.path}"
                    baseUrl.trimEnd('/') + p
                } else {
                    entry.path
                }

                ZoomableImage(
                    imageUrl = imgUrl,
                    authHeader = authHeader,
                    contentDescription = entry.name,
                    pagerState = pagerState,
                    pageIndex = page,
                    totalPages = images.size,
                    onTap = { showChrome = !showChrome }
                )
            }

            // 顶部工具栏
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }

                    val currentEntry = images.getOrNull(pagerState.currentPage)
                    Text(
                        text = currentEntry?.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    imageUrl: String,
    authHeader: String?,
    contentDescription: String,
    pagerState: androidx.compose.foundation.pager.PagerState,
    pageIndex: Int,
    totalPages: Int,
    onTap: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 离开当前页时重置缩放和偏移为原始状态
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != pageIndex && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxHeightPx = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset.Zero
                            }
                        },
                        onTap = { onTap() }
                    )
                }
                .pointerInput(pageIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var prevPinchDist = -1f
                        var isPaging = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }
                            if (activePointers.isEmpty()) break

                            val pointerCount = activePointers.size
                            if (pointerCount >= 2) {
                                // 【双指操作】：永远是放大缩小，不滑动！
                                val p1 = activePointers[0].position
                                val p2 = activePointers[1].position
                                val currentDist = (p1 - p2).getDistance()

                                if (prevPinchDist > 0f && currentDist > 0f) {
                                    val zoomFactor = currentDist / prevPinchDist
                                    val newScale = (scale * zoomFactor).coerceIn(1f, 4.5f)
                                    if (newScale <= 1.02f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = newScale
                                        val maxOffsetX = (maxWidthPx * (newScale - 1f)) / 2f
                                        val maxOffsetY = (maxHeightPx * (newScale - 1f)) / 2f
                                        // 双指缩放时只约束在合理边界内，绝对不滑动
                                        offset = Offset(
                                            x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                                            y = offset.y.coerceIn(-maxOffsetY, maxOffsetY)
                                        )
                                    }
                                }
                                prevPinchDist = currentDist
                                event.changes.forEach { it.consume() }

                            } else if (pointerCount == 1) {
                                // 【单指操作】：始终是滑动！
                                prevPinchDist = -1f
                                val change = activePointers[0]
                                val pan = change.position - change.previousPosition

                                if (scale > 1.05f) {
                                    val maxOffsetX = (maxWidthPx * (scale - 1f)) / 2f
                                    val maxOffsetY = (maxHeightPx * (scale - 1f)) / 2f

                                    val curX = offset.x
                                    val curY = offset.y
                                    val dx = pan.x
                                    val dy = pan.y

                                var consumedX = 0f
                                var excessX = 0f

                                if (dx > 0) { // 向右滑动（查看左部）
                                    val room = maxOffsetX - curX
                                    if (room > 0f) {
                                        consumedX = minOf(room, dx)
                                        excessX = dx - consumedX
                                    } else {
                                        excessX = dx
                                    }
                                } else if (dx < 0) { // 向左滑动（查看右部）
                                    val room = curX - (-maxOffsetX)
                                    if (room > 0f) {
                                        consumedX = maxOf(-room, dx)
                                        excessX = dx - consumedX
                                    } else {
                                        excessX = dx
                                    }
                                }

                                offset = Offset(
                                    x = (curX + consumedX).coerceIn(-maxOffsetX, maxOffsetX),
                                    y = (curY + dy).coerceIn(-maxOffsetY, maxOffsetY)
                                )

                                // 如果滑到了左右边界的头，并且继续向该方向滑动，直接联动 Pager 翻下一张或上一张
                                if (excessX != 0f) {
                                    isPaging = true
                                    pagerState.dispatchRawDelta(excessX)
                                }
                                change.consume()
                            }
                            // 若 scale <= 1.05f（未放大），不消费单指滑动，完全放行给 HorizontalPager 的原生翻页手势
                        }
                    }

                    // 手指抬起，若触发了翻页吸附，自动平滑翻到下一张或上一张
                    if (isPaging) {
                        val fraction = pagerState.currentPageOffsetFraction
                        val targetPage = when {
                            fraction > 0.2f -> (pagerState.currentPage + 1).coerceAtMost(totalPages - 1)
                            fraction < -0.2f -> (pagerState.currentPage - 1).coerceAtLeast(0)
                            else -> pagerState.currentPage
                        }
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val context = LocalContext.current
        val imageRequest = remember(imageUrl, authHeader) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (authHeader != null) {
                        addHeader("Authorization", authHeader)
                    }
                }
                .crossfade(true)
                .build()
        }

        SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        )
    }
}
}
