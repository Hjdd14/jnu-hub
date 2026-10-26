package cn.jnu.hub.ui.screens.utility

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jnu.hub.feature.utility.UsagePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * 纯 Canvas 自绘图表（项目未引入图表库，也不新增依赖）。
 * 只做「可读的概览」：网格 + 折线/柱子 + 少量坐标标注，交互交给上层。
 */

private val dayFmt = SimpleDateFormat("M/d", Locale.CHINA)
private val monthFmt = SimpleDateFormat("M月", Locale.CHINA)

/** 每日用量折线图 */
@Composable
fun UsageLineChart(
    points: List<UsagePoint>,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                "暂无用量数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val bubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val bubbleTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxValue = smoothMax(points.maxOf { it.value })
    val minTime = points.first().timeMillis
    val maxTime = points.last().timeMillis
    val total = points.sumOf { it.value }

    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val tooltipStyle = TextStyle(fontSize = 11.sp, color = bubbleTextColor)

    // 用户选中的数据点。点按或横向拖动选择，抬手后保留，方便慢慢读数。
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    Box(modifier.fillMaxWidth()) {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .pointerInput(points, maxValue) {
                    // 与 Canvas 内绘制用同一套几何，保证手指落点和高亮点一致
                    fun pickIndex(x: Float): Int {
                        val leftPad = 34.dp.toPx()
                        val rightPad = 6.dp.toPx()
                        val chartW = size.width - leftPad - rightPad
                        if (chartW <= 0f) return 0
                        val span = (maxTime - minTime).toFloat().coerceAtLeast(1f)
                        var best = 0
                        var bestD = Float.MAX_VALUE
                        points.forEachIndexed { i, p ->
                            val px = leftPad + chartW * ((p.timeMillis - minTime) / span)
                            val d = abs(px - x)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        android.util.Log.d(
                            "ChartTap",
                            "LINE x=" + x + " w=" + size.width + " leftPad=" + leftPad +
                                " chartW=" + chartW + " n=" + points.size + " best=" + best +
                                " dist=" + bestD,
                        )
                        return best
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selected = pickIndex(down.position.x)
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.firstOrNull()?.let { selected = pickIndex(it.position.x) }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            val leftPad = 34.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val topPad = 6.dp.toPx()
            val rightPad = 6.dp.toPx()

            val chartW = size.width - leftPad - rightPad
            val chartH = size.height - topPad - bottomPad
            if (chartW <= 0f || chartH <= 0f) return@Canvas

            // 横向网格 + Y 轴刻度
            val gridLines = 3
            for (i in 0..gridLines) {
                val ratio = i.toFloat() / gridLines
                val y = topPad + chartH * (1f - ratio)
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartW, y),
                    strokeWidth = 1f,
                )
                val label = formatAxis(maxValue * ratio)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = leftPad - layout.size.width - 4.dp.toPx(),
                        y = y - layout.size.height / 2f,
                    ),
                )
            }

            // 数据点坐标
            val span = (maxTime - minTime).toFloat().coerceAtLeast(1f)
            val pointsXY = points.map { p ->
                val x = leftPad + chartW * ((p.timeMillis - minTime) / span)
                val y = topPad + chartH * (1f - (p.value / maxValue).toFloat())
                Offset(x, y)
            }

            // 渐变填充
            val fillPath = Path().apply {
                moveTo(pointsXY.first().x, topPad + chartH)
                pointsXY.forEach { lineTo(it.x, it.y) }
                lineTo(pointsXY.last().x, topPad + chartH)
                close()
            }
            val areaColor = color
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(areaColor.copy(alpha = 0.30f), areaColor.copy(alpha = 0.02f)),
                    startY = topPad,
                    endY = topPad + chartH,
                ),
            )

            // 折线
            val linePath = Path().apply {
                pointsXY.forEachIndexed { index, p ->
                    if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // 最大值点标一下，避免用户找不到峰值
            val peakIndex = points.indices.maxByOrNull { points[it].value }
            if (peakIndex != null && points.size > 2 && points[peakIndex].value > 0.0) {
                val peak = pointsXY[peakIndex]
                drawCircle(color = color, radius = 3.dp.toPx(), center = peak)
                drawCircle(color = surfaceColor, radius = 1.4.dp.toPx(), center = peak)
            }

            // 选中点：竖直参考线 + 高亮 + 数值气泡
            val sel = selected
            if (sel != null && sel in points.indices) {
                val sp = pointsXY[sel]
                drawLine(
                    color = color.copy(alpha = 0.55f),
                    start = Offset(sp.x, topPad),
                    end = Offset(sp.x, topPad + chartH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        0f,
                    ),
                )
                drawCircle(color = surfaceColor, radius = 5.dp.toPx(), center = sp)
                drawCircle(color = color, radius = 3.2.dp.toPx(), center = sp)

                val p = points[sel]
                val tip = dayFmt.format(Date(p.timeMillis)) + "  " + fmtValue(p.value) + unit
                val layout = textMeasurer.measure(tip, tooltipStyle)
                val padH = 7.dp.toPx()
                val padV = 4.dp.toPx()
                val bw = layout.size.width + padH * 2
                val bh = layout.size.height + padV * 2
                val bx = (sp.x - bw / 2f).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
                val by = (sp.y - bh - 10.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    color = bubbleColor,
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                drawText(layout, topLeft = Offset(bx + padH, by + padV))
            }

            // X 轴首尾日期
            val firstLabel = textMeasurer.measure(dayFmt.format(Date(minTime)), labelStyle)
            drawText(firstLabel, topLeft = Offset(leftPad, topPad + chartH + 4.dp.toPx()))
            if (points.size > 1) {
                val lastLabel = textMeasurer.measure(dayFmt.format(Date(maxTime)), labelStyle)
                drawText(
                    textLayoutResult = lastLabel,
                    topLeft = Offset(
                        leftPad + chartW - lastLabel.size.width,
                        topPad + chartH + 4.dp.toPx(),
                    ),
                )
            }
        }

        Text(
            text = "合计 %.2f %s".format(total, unit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/** 月度消费柱状图 */
@Composable
fun MonthlyBarChart(
    points: List<UsagePoint>,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                "暂无月度数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val bubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val bubbleTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxValue = smoothMax(points.maxOfOrNull { it.value } ?: 0.0)
    val total = points.sumOf { it.value }
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val tooltipStyle = TextStyle(fontSize = 11.sp, color = bubbleTextColor)

    // 最多显示 12 根，超出取最近的
    val shown = if (points.size > 12) points.takeLast(12) else points

    // 选中的柱子。点按或横向拖动选择，抬手后保留，方便读数。
    var selected by remember(shown) { mutableStateOf<Int?>(null) }

    Box(modifier.fillMaxWidth()) {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .pointerInput(shown, maxValue) {
                    // 整列（slot）都可点，不必精确点中柱子本身
                    fun pickIndex(x: Float): Int? {
                        val leftPad = 34.dp.toPx()
                        val rightPad = 6.dp.toPx()
                        val chartW = size.width - leftPad - rightPad
                        if (chartW <= 0f || shown.isEmpty()) return null
                        val slot = chartW / shown.size
                        if (slot <= 0f) return null
                        val idx = ((x - leftPad) / slot).toInt().coerceIn(0, shown.size - 1)
                        android.util.Log.d(
                            "ChartTap",
                            "BAR x=" + x + " w=" + size.width + " leftPad=" + leftPad +
                                " chartW=" + chartW + " count=" + shown.size + " slot=" + slot +
                                " idx=" + idx,
                        )
                        return idx
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selected = pickIndex(down.position.x)
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.firstOrNull()?.let { selected = pickIndex(it.position.x) }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            val leftPad = 34.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val topPad = 6.dp.toPx()
            val rightPad = 6.dp.toPx()

            val chartW = size.width - leftPad - rightPad
            val chartH = size.height - topPad - bottomPad
            if (chartW <= 0f || chartH <= 0f) return@Canvas

            val gridLines = 3
            for (i in 0..gridLines) {
                val ratio = i.toFloat() / gridLines
                val y = topPad + chartH * (1f - ratio)
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + chartW, y),
                    strokeWidth = 1f,
                )
                val layout = textMeasurer.measure(formatAxis(maxValue * ratio), labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = leftPad - layout.size.width - 4.dp.toPx(),
                        y = y - layout.size.height / 2f,
                    ),
                )
            }

            val count = shown.size
            val slot = chartW / count
            val barW = max(4.dp.toPx(), slot * 0.52f)
            shown.forEachIndexed { index, p ->
                val ratio = if (maxValue <= 0.0) 0f else (p.value / maxValue).toFloat()
                val barH = (chartH * ratio).coerceAtLeast(1.dp.toPx())
                val x = leftPad + slot * index + (slot - barW) / 2f
                val y = topPad + chartH - barH
                val isSel = selected == index
                // 选中列额外加一层淡淡的背景，让「当前看的是哪根」一眼可见
                if (isSel) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.12f),
                        topLeft = Offset(leftPad + slot * index, topPad),
                        size = Size(slot, chartH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
                drawRoundRect(
                    color = if (isSel) color else color.copy(alpha = 0.85f),
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
                // 月份标签（隔一个显示，避免拥挤；选中的那根一定显示）
                if (count <= 6 || index % 2 == 0 || isSel) {
                    val layout = textMeasurer.measure(
                        monthFmt.format(Date(p.timeMillis)),
                        if (isSel) TextStyle(fontSize = 10.sp, color = color) else labelStyle,
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = x + barW / 2f - layout.size.width / 2f,
                            y = topPad + chartH + 4.dp.toPx(),
                        ),
                    )
                }
            }

            // 选中柱子的数值气泡
            val sel = selected
            if (sel != null && sel in shown.indices) {
                val p = shown[sel]
                val ratio = if (maxValue <= 0.0) 0f else (p.value / maxValue).toFloat()
                val barH = (chartH * ratio).coerceAtLeast(1.dp.toPx())
                val cx = leftPad + slot * sel + slot / 2f
                val topY = topPad + chartH - barH

                val tip = monthFmt.format(Date(p.timeMillis)) + "  " + fmtValue(p.value) + unit
                val layout = textMeasurer.measure(tip, tooltipStyle)
                val padH = 7.dp.toPx()
                val padV = 4.dp.toPx()
                val bw = layout.size.width + padH * 2
                val bh = layout.size.height + padV * 2
                val bx = (cx - bw / 2f).coerceIn(0f, (size.width - bw).coerceAtLeast(0f))
                val by = (topY - bh - 8.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    color = bubbleColor,
                    topLeft = Offset(bx, by),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                drawText(layout, topLeft = Offset(bx + padH, by + padV))
            }
        }

        Text(
            text = "合计 %.2f %s".format(total, unit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/**
 * 提示气泡里的数值：整数不带小数，小数按量级保留 1~2 位。
 */
private fun fmtValue(value: Double): String = when {
    value <= 0.0 -> "0"
    value == floor(value) -> value.toLong().toString()
    value >= 10 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

/**
 * 把最大值向上取整到一个好看的刻度，避免折线贴顶。
 * 同时保证非 0，否则除零。
 */
private fun smoothMax(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val normalized = raw / magnitude
    val step = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return step * magnitude
}

/** Y 轴刻度文字：小数位随量级自适应 */
private fun formatAxis(value: Double): String = when {
    value <= 0.0 -> "0"
    value >= 100 -> "%.0f".format(value)
    value >= 10 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

