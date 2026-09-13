package com.example.myfile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 滚动速度曲线图（带纵坐标 Y 轴刻度指示大小），支持平滑贝塞尔曲线、半透明渐变发光填充与时间窗口。
 */
@Composable
fun SpeedCurveChart(
    speedHistory: List<Pair<Long, Long>>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000L
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)

    // 只取窗口内的数据点
    val now = System.currentTimeMillis()
    val cutoff = now - windowMs
    val inWindow = speedHistory.filter { it.first >= cutoff }

    // 平滑处理：对窗口内速度做移动平均（滑动窗口），消除瞬时毛刺
    val smoothed = smoothSpeed(inWindow, windowSize = 5)

    val maxSpeed = (smoothed.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1024L)
    val midSpeed = maxSpeed / 2

    Row(modifier = modifier) {
        // 左侧纵坐标刻度文字 (3个刻度: 顶端最大值、中间值、0)
        Column(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatSpeed(maxSpeed),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1
            )
            Text(
                text = formatSpeed(midSpeed),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1
            )
            Text(
                text = "0 B/s",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                maxLines = 1
            )
        }

        // 曲线绘制画布
        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width
            val h = size.height

            // 背景参考线（顶、中、底 3 条虚线）
            val gridLines = 2
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            for (i in 0..gridLines) {
                val y = h * i / gridLines
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
            }

            if (smoothed.isEmpty()) {
                // 无数据：底部灰色虚线
                drawLine(
                    color = lineColor.copy(alpha = 0.25f),
                    start = Offset(0f, h - 2f),
                    end = Offset(w, h - 2f),
                    strokeWidth = 1.5f,
                    pathEffect = dashEffect
                )
                return@Canvas
            }

            // 计算所有数据点坐标 (x, y)
            val paddingV = 4f
            val usableH = (h - paddingV * 2).coerceAtLeast(1f)
            val pts = smoothed.map { (ts, speed) ->
                val x = ((ts - cutoff).toFloat() / windowMs * w).coerceIn(0f, w)
                val y = (h - paddingV - (speed.toFloat() / maxSpeed * usableH)).coerceIn(paddingV, h - 1f)
                Offset(x, y)
            }.sortedBy { it.x }

            val strokePath = Path()
            val fillPath = Path()

            if (pts.size == 1) {
                strokePath.moveTo(0f, pts[0].y)
                strokePath.lineTo(w, pts[0].y)
                drawPath(
                    path = strokePath,
                    color = lineColor.copy(alpha = 0.6f),
                    style = Stroke(width = 2f, cap = StrokeCap.Round)
                )
                drawCircle(lineColor, radius = 3.5.dp.toPx(), center = pts[0])
                return@Canvas
            }

            // 使用平滑贝塞尔曲线连接数据点 (Catmull-Rom to Cubic Bezier)
            strokePath.moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[(i - 1).coerceAtLeast(0)]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]

                val cp1x = p1.x + (p2.x - p0.x) / 6f
                val cp1y = (p1.y + (p2.y - p0.y) / 6f).coerceIn(paddingV, h)
                val cp2x = p2.x - (p3.x - p1.x) / 6f
                val cp2y = (p2.y - (p3.y - p1.y) / 6f).coerceIn(paddingV, h)

                strokePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }

            // 填充渐变路径
            fillPath.addPath(strokePath)
            fillPath.lineTo(pts.last().x, h)
            fillPath.lineTo(pts.first().x, h)
            fillPath.close()

            // 1. 绘制半透明渐变区域
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.32f),
                        lineColor.copy(alpha = 0.12f),
                        lineColor.copy(alpha = 0.01f)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // 2. 绘制平滑线条（横向渐变高亮）
            drawPath(
                path = strokePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.55f),
                        lineColor
                    ),
                    startX = pts.first().x,
                    endX = pts.last().x.coerceAtLeast(pts.first().x + 1f)
                ),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 3. 峰值点标记
            val peakEntry = smoothed.maxByOrNull { it.second }
            if (peakEntry != null && peakEntry.second > 0L) {
                val px = ((peakEntry.first - cutoff).toFloat() / windowMs * w).coerceIn(0f, w)
                val py = (h - paddingV - (peakEntry.second.toFloat() / maxSpeed * usableH)).coerceIn(paddingV, h - 1f)
                val peakOffset = Offset(px, py)
                val lastOffset = pts.last()
                if (Math.abs(peakOffset.x - lastOffset.x) > 12f || Math.abs(peakOffset.y - lastOffset.y) > 12f) {
                    drawCircle(
                        color = lineColor.copy(alpha = 0.45f),
                        radius = 3.dp.toPx(),
                        center = peakOffset
                    )
                }
            }

            // 4. 最新点光晕与实心标记（指示当前实时速度）
            val lastPt = pts.last()
            drawCircle(
                color = lineColor.copy(alpha = 0.22f),
                radius = 7.dp.toPx(),
                center = lastPt
            )
            drawCircle(
                color = lineColor,
                radius = 3.5.dp.toPx(),
                center = lastPt
            )
            drawCircle(
                color = Color.White,
                radius = 1.6.dp.toPx(),
                center = lastPt
            )
        }
    }
}

/**
 * 对速度序列做滑动窗口移动平均，消除瞬时毛刺，让曲线更平滑。
 * @param windowSize 移动平均窗口大小（采样点数，奇数最佳），越大越平滑。
 */
fun smoothSpeed(
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

