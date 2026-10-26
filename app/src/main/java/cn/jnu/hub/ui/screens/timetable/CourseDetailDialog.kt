package cn.jnu.hub.ui.screens.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.jnu.hub.feature.timetable.CourseCustomization
import cn.jnu.hub.feature.timetable.TimetableColors
import cn.jnu.hub.feature.timetable.TimetableCourse
import cn.jnu.hub.ui.theme.Dimens

/**
 * 课程详情弹窗。
 *
 * 除基本信息外，还支持：
 * - 收藏（★）
 * - 备注编辑
 * - 颜色选择（8 色板）
 *
 * @param week 当前周，用于判断本周是否上课
 */
@Composable
fun CourseDetailDialog(
    course: TimetableCourse,
    currentWeek: Int,
    customization: CourseCustomization,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onSaveNote: (String) -> Unit,
    onPickColor: (Int?) -> Unit,
) {
    var noteDraft by remember(course.code, course.name) { mutableStateOf(customization.note) }
    var editingNote by remember { mutableStateOf(false) }
    val swatch = TimetableColors.forCourse(course, customization.colorIndex)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (editingNote) {
                    onSaveNote(noteDraft)
                    editingNote = false
                } else {
                    onDismiss()
                }
            }) {
                Text(if (editingNote) "保存备注" else "知道了")
            }
        },
        dismissButton = {
            if (editingNote) {
                TextButton(onClick = { editingNote = false; noteDraft = customization.note }) {
                    Text("取消")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(swatch.content),
                )
                Spacer(Modifier.width(Dimens.sm))
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (customization.starred) "★" else "☆",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (customization.starred) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleStar)
                        .padding(horizontal = Dimens.sm, vertical = Dimens.xs),
                )
            }
        },
        text = {
            Column {
                DetailRow("课程号", course.code.ifBlank { "-" })
                DetailRow("教师", course.teacher.ifBlank { "-" })
                DetailRow(
                    "学分",
                    if (course.credits > 0) {
                        if (course.credits == course.credits.toLong().toDouble()) {
                            course.credits.toLong().toString()
                        } else {
                            course.credits.toString()
                        }
                    } else "-",
                )
                DetailRow("周次", course.weekText.ifBlank { "-" })

                HorizontalDivider(Modifier.padding(vertical = Dimens.sm))

                // 各时间段（含调课）
                course.slots.forEach { slot ->
                    val active = slot.activeIn(currentWeek)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = weekdayName(slot.weekday),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Dimens.sm))
                        Text(
                            text = if (slot.startPeriod == slot.endPeriod) {
                                "第 ${slot.startPeriod} 节"
                            } else {
                                "第 ${slot.startPeriod}-${slot.endPeriod} 节"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (slot.room.isNotBlank()) {
                            Spacer(Modifier.width(Dimens.sm))
                            Text(
                                text = slot.room,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (slot.isAdjusted) {
                            Spacer(Modifier.width(Dimens.xs))
                            Text(
                                text = "调课",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!active) {
                            Text(
                                text = "本周不上",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (course.campus.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.xs))
                    DetailRow("校区", course.campus)
                }

                HorizontalDivider(Modifier.padding(vertical = Dimens.sm))

                // 颜色选择
                Text(
                    text = "颜色",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.xs))
                ColorPickerRow(
                    currentIndex = customization.colorIndex,
                    autoSwatch = TimetableColors.forCourse(course, null).content,
                    onPick = onPickColor,
                )

                Spacer(Modifier.height(Dimens.md))

                // 备注
                if (editingNote) {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("备注") },
                        placeholder = { Text("比如：带教材、实验室位置…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "备注",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = customization.note.ifBlank { "未填写" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (customization.note.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        TextButton(onClick = { editingNote = true }) { Text("编辑") }
                    }
                }
            }
        },
    )
}

/** 8 色选择行，第一个是「自动」 */
@Composable
private fun ColorPickerRow(
    currentIndex: Int?,
    autoSwatch: androidx.compose.ui.graphics.Color,
    onPick: (Int?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Dimens.xs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        // 自动
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (currentIndex == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable { onPick(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.labelSmall,
                color = if (currentIndex == null) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TimetableColors.palette.forEachIndexed { index, s ->
            val selected = currentIndex == index
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(s.container)
                    .then(
                        if (selected) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape,
                            )
                        } else Modifier,
                    )
                    .clickable { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(s.content),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 1..7 -> 星期一..星期日 */
fun weekdayName(day: Int): String = when (day) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    7 -> "星期日"
    else -> "星期?"
}
