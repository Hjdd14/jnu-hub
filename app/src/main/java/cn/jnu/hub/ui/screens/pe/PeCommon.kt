package cn.jnu.hub.ui.screens.pe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.jnu.hub.feature.pe.PeGradeLevel
import cn.jnu.hub.feature.pe.PeScoreItem
import cn.jnu.hub.feature.pe.PeStudentInfo
import cn.jnu.hub.ui.theme.Dimens

/**
 * 体测模块的公共 UI 片段。
 *
 * 所有颜色都从 MaterialTheme.colorScheme 派生（评分等级映射到 primary / tertiary / secondary / error 等语义色），
 * 不硬编码色值，保证深浅色主题一致。
 */

/** 按等级取语义色 */
@Composable
fun peGradeColor(level: PeGradeLevel): Color = when (level) {
    PeGradeLevel.EXCELLENT -> MaterialTheme.colorScheme.primary
    PeGradeLevel.GOOD -> MaterialTheme.colorScheme.tertiary
    PeGradeLevel.PASS -> MaterialTheme.colorScheme.secondary
    PeGradeLevel.FAIL -> MaterialTheme.colorScheme.error
    PeGradeLevel.NORMAL, PeGradeLevel.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 按等级文字取语义色 */
@Composable
fun peGradeColor(grade: String): Color = peGradeColor(peGradeLevelOf(grade))

/** 等级文字 → 枚举 */
fun peGradeLevelOf(grade: String): PeGradeLevel = when (grade.trim()) {
    "优秀" -> PeGradeLevel.EXCELLENT
    "良好" -> PeGradeLevel.GOOD
    "及格" -> PeGradeLevel.PASS
    "不及格" -> PeGradeLevel.FAIL
    "" -> PeGradeLevel.NONE
    else -> PeGradeLevel.NORMAL
}

/** 学生信息条：姓名 / 学号 / 班级，右侧可放操作区 */
@Composable
fun PeStudentHeader(
    student: PeStudentInfo,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (student.name.isBlank() && student.studentNo.isBlank()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = student.name.ifBlank { "体测账号" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (student.studentNo.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = student.studentNo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (student.className.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = student.className,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** 小徽章：等级 / 状态 */
@Composable
fun PeBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Dimens.sm, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 成绩行：项目名 | 成绩 | 得分+等级 | 分值
 * 用带比例的 Row 而不是表格，移动端更稳，也不会出现横向滚动条。
 */
@Composable
fun PeScoreRow(
    item: PeScoreItem,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    showPoints: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1.1f),
            )
            Text(
                text = item.value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                color = if (item.value.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1.3f),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.grade.isNotBlank()) {
                    PeBadge(item.grade, peGradeColor(item.grade))
                    Spacer(Modifier.width(Dimens.xs))
                }
                Text(
                    text = item.score.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = peGradeColor(item.grade),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (showPoints) {
                Text(
                    text = item.points.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** 成绩表表头 */
@Composable
fun PeScoreHeaderRow(modifier: Modifier = Modifier, showPoints: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "测试项目",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = "成绩",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1.3f),
        )
        Text(
            text = "得分/等级",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        if (showPoints) {
            Text(
                text = "分值",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

/** 提示条（站点公告 / 补测时间等） */
@Composable
fun PeHintSurface(main: String, sub: String) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.md)) {
            if (main.isNotBlank()) {
                Text(
                    text = main,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (sub.isNotBlank()) {
                if (main.isNotBlank()) Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** 会话失效时的统一引导（体测需要独立账号登录） */
@Composable
fun PeLoginRequiredState(onNeedLogin: () -> Unit) {
    cn.jnu.hub.ui.components.ErrorState(
        title = "需要登录体测系统",
        message = "体测系统使用独立账号（学号 + 8 位生日密码），请先登录。",
        actionText = "去登录",
        onAction = onNeedLogin,
    )
}
