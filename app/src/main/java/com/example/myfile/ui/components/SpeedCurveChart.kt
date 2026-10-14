package com.example.myfile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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

    // 内部平滑时钟驱动：每 200ms 刷新一次当前时间，即使网络速度为 0 也平滑驱动时间轴向左滚动
    val liveTime by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(200L)
            value = System.currentTimeMillis()
        }
    }

    val now = liveTime
    val cutoff = now - windowMs
    val inWindow = speedHistory.filter { it.first >= cutoff }.sortedBy { it.first }

    // 处理时间序列：检测断档智能快速归零，并锚定当前时间右侧
    val prepared = prepareSpeedData(inWindow, cutoff, now)

    // 平滑处理：仅对连续传输密集采样消除局部毛刺，0 速率严格保持为 0
    val smoothed = smoothSpeed(prepared)

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

            // 屏幕坐标单调递增保证：若两点水平间距极小，确保 X 轴严格单调向前微移，绝不丢弃下落归零点
            val pts = ArrayList<Offset>(rawPts.size)
            for (p in rawPts) {
                if (pts.isEmpty()) {
                    pts.add(p)
                } else {
                    val last = pts.last()
                    if (p.x <= last.x) {
                        pts.add(Offset(last.x + 0.5f, p.y))
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
 * 3. 智能检测断档（采样间隔 > 1200ms）：前点非零时 400ms 内迅速坠落归零，断档全段锚定 0 速率，杜绝大斜坡；
 * 4. 右侧贴边：距最新采样 > 400ms 时，400ms 快速归零并在当前时间 now 处锚定 0 速率。
 */
private fun prepareSpeedData(
    data: List<Pair<Long, Long>>,
    cutoff: Long,
    now: Long
): List<Pair<Long, Long>> {
    if (data.isEmpty()) {
        return listOf(cutoff to 0L, now to 0L)
    }

    val result = ArrayList<Pair<Long, Long>>(data.size * 2 + 4)

    val first = data.first()
    // 若中途开始传输，补齐 0 速率起点
    if (first.first > cutoff + 500L) {
        result.add(cutoff to 0L)
        if (first.first - 400L > cutoff) {
            result.add((first.first - 400L) to 0L)
        }
    }

    for (pt in data) {
        if (result.isNotEmpty()) {
            val (prevT, prevV) = result.last()
            val dt = pt.first - prevT
            if (dt < 150L) {
                // <150ms 密集采样点合并，保留较高速度
                result[result.size - 1] = pt.first to maxOf(prevV, pt.second)
                continue
            } else if (dt > 1200L) {
                // 出现大于 1.2 秒的断档！说明前一次传输已结束或断流
                if (prevV > 0L) {
                    // 前点非零：400ms 内迅速坠落归零，不要形成长斜坡
                    result.add((prevT + 400L) to 0L)
                }
                // 在新传输开始前 400ms 补齐 0（从 0 开始爬升）
                if (pt.second > 0L && pt.first - 400L > prevT + 400L) {
                    result.add((pt.first - 400L) to 0L)
                }
            }
        }
        result.add(pt)
    }

    // 终点闭合与当前实时时间对齐
    val (lastT, lastV) = result.last()
    if (now - lastT > 400L) {
        if (lastV > 0L) {
            // 最后一个历史采样点是非零（比如在 20M 处传输结束），400ms 内迅速坠落到 0
            val dropT = minOf(lastT + 400L, now)
            result.add(dropT to 0L)
        }
        // 当前时间 now 处锚定 0 速率
        if (now > result.last().first) {
            result.add(now to 0L)
        }
    }

    return result
}

/**
 * 局部移动平均平滑滤波：
 * 仅对连续密集采样（时间间隔 <= maxIntervalMs）消除瞬时毛刺；
 * 0 速率点严格保持为 0，绝不跨越断档或将 0 速率点向上抬升涂抹。
 */
fun smoothSpeed(
    data: List<Pair<Long, Long>>,
    maxIntervalMs: Long = 800L
): List<Pair<Long, Long>> {
    if (data.size < 3) return data
    val n = data.size
    val result = ArrayList<Pair<Long, Long>>(n)
    for (i in 0 until n) {
        val (ti, vi) = data[i]
        // 0 速率点纯净保持，杜绝向上抬升
        if (vi == 0L) {
            result.add(ti to 0L)
            continue
        }

        var sum = vi
        var count = 1

        if (i > 0 && (ti - data[i - 1].first <= maxIntervalMs)) {
            sum += data[i - 1].second
            count++
        }
        if (i < n - 1 && (data[i + 1].first - ti <= maxIntervalMs)) {
            sum += data[i + 1].second
            count++
        }

        val avg = sum / count
        result.add(ti to avg)
    }
    return result
}
