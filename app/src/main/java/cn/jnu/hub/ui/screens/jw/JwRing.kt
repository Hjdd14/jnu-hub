package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.theme.Dimens

/** 学分完成度环形进度图：中心显示完成/要求学分 */
@Composable
fun CreditRing(
    done: Double,
    total: Double,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    strokeWidth: Dp = 14.dp,
) {
    val ratio = if (total <= 0) 0f else (done / total).toFloat().coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val stroke = Stroke(width = strokeWidth.value * 2f, cap = StrokeCap.Round)
        Canvas(Modifier.fillMaxSize()) {
            val inset = strokeWidth.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                width = this.size.width - inset * 2,
                height = this.size.height - inset * 2,
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            // 轨道
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // 进度
            if (ratio > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * ratio,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = jwNumberText(done),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "/ ${jwNumberText(total)} 学分",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = "%.1f%%".format(ratio * 100),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 模块进度条：标题 + 已完成/要求 + 进度 */
@Composable
fun ModuleProgress(
    label: String,
    done: Double?,
    total: Double,
    modifier: Modifier = Modifier,
) {
    val used = done ?: 0.0
    val ratio = if (total <= 0) 0f else (used / total).toFloat().coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().padding(vertical = Dimens.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (done == null) "/ ${jwNumberText(total)}" else
                    "${jwNumberText(used)} / ${jwNumberText(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

/** 学分数字展示：整数不带小数，非整数保留一位 */
fun jwNumberText(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.1f".format(value)
