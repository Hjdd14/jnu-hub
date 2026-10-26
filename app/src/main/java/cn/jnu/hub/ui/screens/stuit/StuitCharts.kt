package cn.jnu.hub.ui.screens.stuit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.jnu.hub.feature.stuit.StuitFiveEdu
import cn.jnu.hub.ui.theme.Dimens

/**
 * 五育（德/智/体/美/劳）横向条形图，Canvas 自绘，不引入任何图表依赖。
 *
 * 每一行：名称（德）| 轨道 + 填充条 | 分值
 * 填充比例 = score / maxScore，全 0 时显示空轨道。
 */
@Composable
fun FiveEduBarChart(
    items: List<StuitFiveEdu>,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = Color(0xFF00897B)

    val target = items.map { item ->
        val max = if (item.maxScore > 0.0) item.maxScore else 10.0
        ((item.score ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
    }
    val anim by animateFloatAsState(targetValue = 1f, label = "five-edu")

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.width(36.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.height / 2f
                        // 轨道
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(0f, 0f),
                            size = Size(size.width, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        )
                        val ratio = target.getOrElse(index) { 0f } * anim
                        if (ratio > 0f) {
                            val barColor = if (index % 2 == 0) accent else accent2
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(0f, 0f),
                                size = Size(size.width * ratio, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(Dimens.md))
                Text(
                    text = fmtScore(item.score),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((item.score ?: 0.0) > 0.0) textColor else mutedColor,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

/**
 * 总分仪表：Canvas 画的半圆环 + 中间大数字。
 * 用于综合测评页顶部「64.54」大卡片。
 */
@Composable
fun ScoreGauge(
    score: Double?,
    maxScore: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    centerLabel: String = "综合测评总分",
    /** 中间大数字颜色；放在有底色的大卡片里时要传卡片的前景色 */
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    /** 辅助文字（"/ 100.0"、底部标签）颜色 */
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // 轨道色跟随前景，避免放在有色卡片上时与底色冲突
    val track = mutedColor.copy(alpha = 0.25f)
    val target = if (score == null || maxScore <= 0.0) 0f
    else (score / maxScore).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(targetValue = target, label = "gauge")

    Column(
        modifier = modifier.widthIn(max = 160.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                if (anim > 0f) {
                    drawArc(
                        color = color,
                        startAngle = 135f,
                        sweepAngle = 270f * anim,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(
                    text = fmtScore(score),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
                Text(
                    text = "/ %.1f".format(maxScore),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                )
            }
        }
        Spacer(Modifier.height(Dimens.xs))
        Text(
            text = centerLabel,
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor,
        )
    }
}
