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
 * 滚动速度曲线图（带纵坐标 Y 轴刻度指示大小），支持保形单调三次埃尔米特样条曲线（PCHIP）、半透明渐变发光填充与时间窗口。
 * 彻底杜绝水平折返、数值过冲及阶梯毛刺，确保曲线圆润平滑且单调严格向前。
 */
@Composable
fun SpeedCurveChart(
    speedHistory: List<Pair<Long, Long>>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000L
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    // 只取窗口内的数据点，并按时间升序排序
    val now = System.currentTimeMillis()
    val cutoff = now - windowMs
    val inWindow = speedHistory.filter { it.first >= cutoff }.sortedBy { it.first }

    // 处理时间序列：合并过近采样点，并智能补齐起点与当前时间终点
    val prepared = prepareSpeedData(inWindow, cutoff, now)

    // 平滑处理：对窗口内速度做移动平均（滑动窗口），消除瞬时毛刺
    val smoothed = smoothSpeed(prepared, windowSize = 3)

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

            val paddingV = 4f
            val usableH = (h - paddingV * 2).coerceAtLeast(1f)

            // 背景参考线（顶、中、底 3 条虚线，与左侧刻度精确对齐）
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            val gridYs = floatArrayOf(paddingV, paddingV + usableH / 2f, h - paddingV)
            for (y in gridYs) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
            }

            if (smoothed.isEmpty()) {
                // 无数据：底部静止参考虚线
                drawLine(
                    color = lineColor.copy(alpha = 0.25f),
                    start = Offset(0f, h - paddingV),
                    end = Offset(w, h - paddingV),
                    strokeWidth = 1.5f,
                    pathEffect = dashEffect
                )
                return@Canvas
            }

            // 计算所有数据点屏幕坐标 (x, y)
            val rawPts = smoothed.map { (ts, speed) ->
                val x = ((ts - cutoff).toFloat() / windowMs * w).coerceIn(0f, w)
                val y = (h - paddingV - (speed.toFloat() / maxSpeed * usableH)).coerceIn(paddingV, h - paddingV)
                Offset(x, y)
            }

            // 屏幕坐标去重与单调递增保证：若两点水平间距 < 2px，合并并保留更高速度点，杜绝斜率无限大或回弹
            val pts = ArrayList<Offset>(rawPts.size)
            for (p in rawPts) {
                if (pts.isEmpty()) {
                    pts.add(p)
                } else {
                    val last = pts.last()
                    if (p.x - last.x < 2f) {
                        if (p.y < last.y) { // y 越小速度越高
                            pts[pts.size - 1] = p
                        }
                    } else {
                        pts.add(p)
                    }
                }
            }

            if (pts.size <= 1) {
                val pt = pts.firstOrNull() ?: Offset(0f, h - paddingV)
                drawLine(
                    color = lineColor.copy(alpha = 0.6f),
                    start = Offset(0f, pt.y),
                    end = Offset(w, pt.y),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(lineColor, radius = 3.5.dp.toPx(), center = pt)
                return@Canvas
            }

            val strokePath = Path()
            val fillPath = Path()

            // 基于保形三次埃尔米特插值（Monotone Cubic Hermite Spline / PCHIP）构造三次贝塞尔曲线
            // 该算法在数学上严格保证 X(t) 单调递增且 dx/dt > 0，彻底根除水平折返；极值点导数置 0，杜绝过冲振荡
            val n = pts.size
            val secants = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                val dx = pts[i + 1].x - pts[i].x
                val dy = pts[i + 1].y - pts[i].y
                secants[i] = dy / dx
            }

            val slopes = FloatArray(n)
            for (i in 0 until n) {
                if (i == 0) {
                    slopes[i] = secants[0]
                } else if (i == n - 1) {
                    slopes[i] = secants[n - 2]
                } else {
                    val s0 = secants[i - 1]
                    val s1 = secants[i]
                    if (s0 * s1 <= 0f) {
                        // 局部极值点（峰值或谷值）：切线斜率为 0，保证过渡平滑饱满且不过冲
                        slopes[i] = 0f
                    } else {
                        // PCHIP 谐波均值
                        slopes[i] = (2f * s0 * s1) / (s0 + s1)
                    }
                }
            }

            strokePath.moveTo(pts[0].x, pts[0].y)
            for (i in 0 until n - 1) {
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val dx = p2.x - p1.x

                // 控制点 1 位于 1/3 处，控制点 2 位于 2/3 处，X 轴恒单调递增，无任何回折
                val cp1x = p1.x + dx / 3f
                val cp1y = (p1.y + slopes[i] * dx / 3f).coerceIn(paddingV, h - paddingV)

                val cp2x = p2.x - dx / 3f
                val cp2y = (p2.y - slopes[i + 1] * dx / 3f).coerceIn(paddingV, h - paddingV)

                strokePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }

            // 填充渐变路径：从折线闭合到基准线 (h - paddingV)
            fillPath.addPath(strokePath)
            fillPath.lineTo(pts.last().x, h - paddingV)
            fillPath.lineTo(pts.first().x, h - paddingV)
            fillPath.close()

            // 1. 绘制半透明发光渐变填充
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.32f),
                        lineColor.copy(alpha = 0.12f),
                        lineColor.copy(alpha = 0.01f),
                        Color.Transparent
                    ),
                    startY = paddingV,
                    endY = h - paddingV
                )
            )

            // 2. 绘制平滑主体曲线（横向发光渐变）
            drawPath(
                path = strokePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.45f),
                        lineColor.copy(alpha = 0.85f),
                        lineColor
                    ),
                    startX = pts.first().x,
                    endX = pts.last().x.coerceAtLeast(pts.first().x + 1f)
                ),
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 3. 峰值点标记（若峰值明显且距离最新点有一定间隔）
            val peakPt = pts.minByOrNull { it.y }
            if (peakPt != null && maxSpeed > 1024L) {
                val lastOffset = pts.last()
                if (Math.abs(peakPt.x - lastOffset.x) > 16f || Math.abs(peakPt.y - lastOffset.y) > 16f) {
                    drawCircle(
                        color = lineColor.copy(alpha = 0.35f),
                        radius = 4.dp.toPx(),
                        center = peakPt
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 2.dp.toPx(),
                        center = peakPt
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
 * 预处理时间窗口内的速度采样点：
 * 1. 若首个采样点晚于窗口起点，自动补齐从 0 开始的平滑爬升锚点；
 * 2. 合并 < 150ms 的密集采样点，防止重叠抖动；
 * 3. 若最新采样点距离当前时间超过 1s，延伸至当前时间保持图表右侧贴边。
 */
private fun prepareSpeedData(
    data: List<Pair<Long, Long>>,
    cutoff: Long,
    now: Long
): List<Pair<Long, Long>> {
    if (data.isEmpty()) return emptyList()

    val result = ArrayList<Pair<Long, Long>>(data.size + 3)

    val first = data.first()
    // 若中途开始传输，补齐 0 速率起点
    if (first.first > cutoff + 1000L) {
        result.add(cutoff to 0L)
        if (first.first - 300L > cutoff) {
            result.add((first.first - 300L) to 0L)
        }
    }

    for (pt in data) {
        if (result.isNotEmpty() && pt.first - result.last().first < 150L) {
            val last = result.last()
            result[result.size - 1] = pt.first to maxOf(last.second, pt.second)
        } else {
            result.add(pt)
        }
    }

    val last = result.last()
    if (now - last.first > 1000L) {
        result.add(now to last.second)
    }

    return result
}

/**
 * 对速度序列做滑动窗口移动平均，消除瞬时毛刺，让曲线更平滑。
 * @param windowSize 移动平均窗口大小（采样点数，奇数最佳），越大越平滑。
 */
fun smoothSpeed(
    data: List<Pair<Long, Long>>,
    windowSize: Int = 3
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
