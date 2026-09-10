package com.example.myfile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myfile.core.TrafficMonitor
import com.example.myfile.core.TrafficRecord
import com.example.myfile.core.TransferState

@Composable
fun DebugTrafficDialog(
    onDismiss: () -> Unit
) {
    val activeTransfers by TrafficMonitor.activeTransfers.collectAsState()
    val recentTransfers by TrafficMonitor.recentTransfers.collectAsState()
    val debugLogs by TrafficMonitor.debugLogs.collectAsState()
    val totalSpeed by TrafficMonitor.totalDownloadSpeed.collectAsState()
    val speedHistory by TrafficMonitor.speedHistory.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }

    // 时间窗口切换：30s / 1min / 2min / 5min 循环
    val timeWindows = listOf(30_000L, 60_000L, 120_000L, 300_000L)
    val timeWindowLabels = listOf("30秒", "1分钟", "2分钟", "5分钟")
    var timeWindowIndex by remember { mutableStateOf(timeWindows.size - 1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. 顶栏：标题与实时总网速
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "实时网络传输监视器 v1.1.9",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "实时监控应用发起的所有网络传输",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                // 2. 速度面板：当前速度 + 5 分钟曲线图
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("实时下行速度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatSpeed(totalSpeed),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (totalSpeed > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            // 时间窗口切换按钮：点击循环切换
                            Surface(
                                onClick = { timeWindowIndex = (timeWindowIndex + 1) % timeWindows.size },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = timeWindowLabels[timeWindowIndex],
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "切换时间范围",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        SpeedCurveChart(
                            speedHistory = speedHistory,
                            lineColor = MaterialTheme.colorScheme.primary,
                            windowMs = timeWindows[timeWindowIndex],
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        )
                    }
                }



                // 3. Tab 切换
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "活跃传输 (${activeTransfers.size})",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "传输历史 (${recentTransfers.size})",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                "调试日志 (${debugLogs.size})",
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                // 4. 列表内容
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> ActiveTransfersList(
                            transfers = activeTransfers,
                            onCancel = { TrafficMonitor.cancelTransfer(it) }
                        )
                        1 -> RecentTransfersList(
                            transfers = recentTransfers,
                            onClear = { TrafficMonitor.clearHistory() }
                        )
                        2 -> DebugLogsList(
                            logs = debugLogs,
                            onClear = { TrafficMonitor.clearDebugLogs() }
                        )
                    }
                }

                // 5. 底部操作栏
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeTransfers.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { TrafficMonitor.cancelAllActive() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("终止所有活跃连接")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(onClick = onDismiss) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTransfersList(
    transfers: List<TrafficRecord>,
    onCancel: (Long) -> Unit
) {
    if (transfers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF43A047),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "当前无活跃网络传输",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "网络处于空闲状态，未发生背景流量损耗",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transfers, key = { it.id }) { record ->
            ActiveTransferCard(record = record, onCancel = { onCancel(record.id) })
        }
    }
}

@Composable
private fun ActiveTransferCard(
    record: TrafficRecord,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第 1 行：分类标签、HTTP 方法、实时速率与终止按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = record.category,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = record.method,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡ ${formatSpeed(record.speedBytesPerSec)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (record.speedBytesPerSec > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onCancel,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("终止", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第 2 行：路径 / URL
            Text(
                text = record.displayUrl,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis
            )

            if (expanded && record.url != record.displayUrl) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = record.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(6.dp))

            // 第 3 行：已下载大小、耗时与 Range 信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalStr = if (record.totalBytesExpected > 0) {
                    val pct = (record.totalBytesRead * 100 / record.totalBytesExpected).coerceIn(0, 100)
                    "已传 ${formatSize(record.totalBytesRead)} / ${formatSize(record.totalBytesExpected)} ($pct%)"
                } else {
                    "已传 ${formatSize(record.totalBytesRead)}"
                }
                Text(
                    text = totalStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "持续 ${(record.durationMs / 1000f).let { "%.1fs".format(it) }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (record.rangeHeader != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Range: ${record.rangeHeader}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RecentTransfersList(
    transfers: List<TrafficRecord>,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "显示最近 50 条已完成的传输记录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (transfers.isNotEmpty()) {
                TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
                    Text("清空历史", fontSize = 12.sp)
                }
            }
        }

        if (transfers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无历史传输记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(transfers, key = { it.id }) { record ->
                RecentTransferItem(record = record)
            }
        }
    }
}

@Composable
private fun RecentTransferItem(record: TrafficRecord) {
    val statusColor = when (record.status) {
        TransferState.COMPLETED -> Color(0xFF2E7D32)
        TransferState.CANCELED -> Color(0xFFE65100)
        TransferState.FAILED -> MaterialTheme.colorScheme.error
        TransferState.ACTIVE -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (record.responseCode > 0) "${record.responseCode}" else record.status.name,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = record.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.displayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSize(record.totalBytesRead),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${record.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun DebugLogsList(
    logs: List<String>,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "显示内部流程与默认应用调试事件 (${logs.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (logs.isNotEmpty()) {
                TextButton(onClick = onClear, contentPadding = PaddingValues(0.dp)) {
                    Text("清空日志", fontSize = 12.sp)
                }
            }
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无调试日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs.size) { idx ->
                val log = logs[idx]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = log,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/** 滚动速度曲线图（带纵坐标 Y 轴刻度指示大小），支持时间窗口 + 平滑处理 */
@Composable
fun SpeedCurveChart(
    speedHistory: List<Pair<Long, Long>>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    windowMs: Long = 300_000L
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

    // 只取窗口内的数据点
    val now = System.currentTimeMillis()
    val cutoff = now - windowMs
    val inWindow = speedHistory.filter { it.first >= cutoff }

    // 平滑处理：对窗口内速度做移动平均（滑动窗口），消除瞬时毛刺
    val smoothed = smoothSpeed(inWindow, windowSize = 5)

    val maxSpeed = smoothed.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    val midSpeed = maxSpeed / 2

    Row(modifier = modifier) {
        // 左侧/右侧纵坐标刻度文字 (3个刻度: 顶端最大值、中间值、0)
        Column(
            modifier = Modifier
                .width(52.dp)
                .fillMaxHeight()
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatSpeed(maxSpeed),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = formatSpeed(midSpeed),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
            Text(
                text = "0 B/s",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1
            )
        }

        // 曲线绘制画布
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width
            val h = size.height

            // 背景参考线（顶、中、底 3 条横线）
            val gridLines = 2
            for (i in 0..gridLines) {
                val y = h * i / gridLines
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            if (smoothed.size < 2) {
                // 无数据：底部灰色平线
                drawLine(
                    color = lineColor.copy(alpha = 0.3f),
                    start = Offset(0f, h),
                    end = Offset(w, h),
                    strokeWidth = 2f
                )
                return@Canvas
            }

            val tMin = now - windowMs

            val path = Path()
            var firstPoint = true

            smoothed.forEach { (ts, speed) ->
                val x = ((ts - tMin).toFloat() / windowMs * w).coerceIn(0f, w)
                val y = (h - speed.toFloat() / maxSpeed * h).coerceIn(0f, h)
                if (firstPoint) {
                    path.moveTo(x, y)
                    firstPoint = false
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 顶端速度标注点
            val peakEntry = smoothed.maxByOrNull { it.second }
            if (peakEntry != null && peakEntry.second > 0L) {
                val px = ((peakEntry.first - tMin).toFloat() / windowMs * w).coerceIn(0f, w)
                val py = (h - peakEntry.second.toFloat() / maxSpeed * h).coerceIn(0f, h)
                drawCircle(lineColor, radius = 4f, center = Offset(px, py))
            }
        }
    }
}

/**
 * 对速度序列做滑动窗口移动平均，消除瞬时毛刺，让曲线更平滑。
 * @param windowSize 移动平均窗口大小（采样点数，奇数最佳），越大越平滑。
 */
private fun smoothSpeed(
    data: List<Pair<Long, Long>>,
    windowSize: Int = 5
): List<Pair<Long, Long>> {
    if (data.size < 3 || windowSize <= 1) return data
    val half = windowSize / 2
    val result = ArrayList<Pair<Long, Long>>(data.size)
    for (i in data.indices) {
        val start = (i - half).coerceAtLeast(0)
        val end = (i + half).coerceAtMost(data.size - 1)
        var sum = 0L
        for (j in start..end) {
            sum += data[j].second
        }
        val avg = sum / (end - start + 1)
        // 保留原始时间戳，仅平滑速度值
        result.add(data[i].first to avg)
    }
    return result
}

