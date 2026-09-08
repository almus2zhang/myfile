package com.example.myfile.ui.components

import android.net.Uri
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myfile.MyApp
import com.example.myfile.data.db.entity.VideoProgressEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun VideoPlayerDialog(
    videoUri: Uri,
    videoTitle: String,
    progressKey: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        val scope = rememberCoroutineScope()
        val dao = MyApp.instance.db.videoProgressDao()

        var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var isPrepared by remember { mutableStateOf(false) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var isDraggingSlider by remember { mutableStateOf(false) }
        var sliderPosition by remember { mutableFloatStateOf(0f) }
        var showControls by remember { mutableStateOf(true) }
        var resumedToast by remember { mutableStateOf<String?>(null) }

        // 周期性同步播放进度并持久化到 Room 数据库
        LaunchedEffect(isPrepared, isPlaying) {
            while (isActive) {
                videoViewRef?.let { vv ->
                    if (isPrepared && vv.isPlaying) {
                        isPlaying = true
                        val pos = vv.currentPosition.toLong()
                        val dur = vv.duration.toLong().coerceAtLeast(0L)
                        if (!isDraggingSlider) {
                            currentPositionMs = pos
                            durationMs = dur
                        }
                        if (pos > 0L && dur > 0L) {
                            dao.save(
                                VideoProgressEntity(
                                    uriKey = progressKey,
                                    positionMs = pos,
                                    durationMs = dur,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    } else if (isPrepared && !vv.isPlaying) {
                        isPlaying = false
                    }
                }
                delay(1000)
            }
        }

        // 自动隐藏控件（播放状态下 4 秒无操作自动隐藏）
        LaunchedEffect(showControls, isPlaying) {
            if (showControls && isPlaying) {
                delay(4000)
                showControls = false
            }
        }

        // 退出时保存最后进度
        DisposableEffect(Unit) {
            onDispose {
                videoViewRef?.let { vv ->
                    val pos = vv.currentPosition.toLong()
                    val dur = vv.duration.toLong().coerceAtLeast(0L)
                    if (pos > 0L && dur > 0L) {
                        scope.launch {
                            dao.save(
                                VideoProgressEntity(
                                    uriKey = progressKey,
                                    positionMs = pos,
                                    durationMs = dur,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    vv.stopPlayback()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls }
                    )
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        setVideoURI(videoUri)
                        setOnPreparedListener { mp ->
                            isPrepared = true
                            durationMs = duration.toLong().coerceAtLeast(0L)
                            scope.launch {
                                val saved = dao.get(progressKey)
                                if (saved != null && saved.positionMs > 1000L && saved.positionMs < (durationMs - 3000L)) {
                                    seekTo(saved.positionMs.toInt())
                                    currentPositionMs = saved.positionMs
                                    resumedToast = "已从上次位置 ${formatVideoTime(saved.positionMs)} 继续播放"
                                }
                                start()
                                isPlaying = true
                            }
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            currentPositionMs = durationMs
                            scope.launch {
                                dao.save(
                                    VideoProgressEntity(
                                        uriKey = progressKey,
                                        positionMs = durationMs,
                                        durationMs = durationMs,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        videoViewRef = this
                    }
                }
            )

            // 加载中指示器
            if (!isPrepared) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 恢复进度提示
            AnimatedVisibility(
                visible = resumedToast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            ) {
                resumedToast?.let {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 顶部控制栏
            AnimatedVisibility(
                visible = showControls,
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
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = videoTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 底部控制栏
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // 进度条与时间
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatVideoTime(if (isDraggingSlider) (sliderPosition * durationMs).toLong() else currentPositionMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = if (isDraggingSlider) sliderPosition else {
                                if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                            },
                            onValueChange = {
                                isDraggingSlider = true
                                sliderPosition = it
                            },
                            onValueChangeFinished = {
                                val targetPos = (sliderPosition * durationMs).toInt()
                                videoViewRef?.seekTo(targetPos)
                                currentPositionMs = targetPos.toLong()
                                isDraggingSlider = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatVideoTime(durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }

                    // 播放/暂停控制
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                videoViewRef?.let { vv ->
                                    val current = vv.currentPosition
                                    vv.seekTo((current - 10000).coerceAtLeast(0))
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay10,
                                contentDescription = "快退10秒",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))

                        IconButton(
                            onClick = {
                                videoViewRef?.let { vv ->
                                    if (vv.isPlaying) {
                                        vv.pause()
                                        isPlaying = false
                                    } else {
                                        vv.start()
                                        isPlaying = true
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatVideoTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
