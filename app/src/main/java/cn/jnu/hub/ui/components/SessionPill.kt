package cn.jnu.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.theme.Dimens

/**
 * 登录状态胶囊。
 * 明确告诉用户「登录态还能撑多久」，这是需求的第 6 条。
 */
@Composable
fun SessionPill(
    remainingMinutes: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (color, text) = when {
        remainingMinutes <= 0 -> Color(0xFFBA1A1A) to "登录已过期"
        remainingMinutes < 10 -> Color(0xFFED8B00) to "剩余 $remainingMinutes 分钟"
        else -> Color(0xFF2E7D32) to formatRemaining(remainingMinutes)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(color.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun formatRemaining(minutes: Int): String =
    if (minutes >= 60) "可用 %d 小时 %d 分".format(minutes / 60, minutes % 60)
    else "可用 $minutes 分钟"
