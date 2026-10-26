package cn.jnu.hub.ui.screens.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.theme.Dimens

/**
 * 周次切换条：◀ 第 N 周 ▶ + 「本周」+ 刷新。
 *
 * 独立成组件便于网格/列表两种视图共用。
 *
 * @param refreshing true 时刷新图标转圈且不可点
 * @param onRefresh 清缓存并强制重抓
 */
@Composable
fun WeekSelector(
    week: Int,
    weekCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToday: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.sm, vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = week > 1) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = if (week > 1) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }

        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "第 $week 周",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "共 $weekCount 周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onNext, enabled = week < weekCount) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = if (week < weekCount) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }

        TextButton(onClick = onJumpToday) {
            Text("本周", style = MaterialTheme.typography.labelMedium)
        }

        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新课表",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 视图切换：网格 / 列表 */
@Composable
fun TimetableViewToggle(
    isGrid: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        FilterChip(
            selected = isGrid,
            onClick = { onToggle(true) },
            label = { Text("网格") },
        )
        FilterChip(
            selected = !isGrid,
            onClick = { onToggle(false) },
            label = { Text("列表") },
        )
    }
}
