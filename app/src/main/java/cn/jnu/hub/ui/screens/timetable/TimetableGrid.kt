package cn.jnu.hub.ui.screens.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.jnu.hub.feature.timetable.CourseCustomization
import cn.jnu.hub.feature.timetable.CourseSlot
import cn.jnu.hub.feature.timetable.TimetableColors
import cn.jnu.hub.feature.timetable.TimetableCourse
import cn.jnu.hub.feature.timetable.TimetableParser

/** 网格尺寸常量 */
private val PERIOD_HEIGHT = 62.dp
private val PERIOD_COL_WIDTH = 26.dp
private val DAY_HEADER_HEIGHT = 30.dp

/** 星期表头文字 */
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 节次上限 */
const val TIMETABLE_MAX_PERIOD = TimetableParser.MAX_PERIOD

/**
 * 周课表网格：左侧节次列 + 顶部星期行 + 课程块。
 *
 * 布局全部用 Row/Column 权重实现，靠 padding-top 定位，不用绝对像素偏移，
 * 换字号或窄屏都不会错位。
 */
@Composable
fun TimetableGrid(
    week: Int,
    courses: List<TimetableCourse>,
    customizations: Map<String, CourseCustomization>,
    onCourseClick: (TimetableCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(courses, week) {
        courses.flatMap { course ->
            course.slots
                .filter { it.activeIn(week) && it.weekday in 1..7 }
                .map { slot -> Block(course, slot) }
        }
    }

    val scroll = rememberScrollState()

    Row(modifier.fillMaxSize().verticalScroll(scroll)) {
        Column(Modifier.width(PERIOD_COL_WIDTH)) {
            Spacer(Modifier.height(DAY_HEADER_HEIGHT))
            for (p in 1..TIMETABLE_MAX_PERIOD) {
                Box(
                    Modifier.fillMaxWidth().height(PERIOD_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = p.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(Modifier.weight(1f)) {
            WeekdayHeader()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PERIOD_HEIGHT * TIMETABLE_MAX_PERIOD),
            ) {
                Column(Modifier.fillMaxSize()) {
                    for (p in 1..TIMETABLE_MAX_PERIOD) {
                        Box(Modifier.fillMaxWidth().height(PERIOD_HEIGHT)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                    ),
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxSize()) {
                    for (day in 1..7) {
                        DayColumn(
                            blocks = blocks.filter { it.slot.weekday == day },
                            customizations = customizations,
                            onCourseClick = onCourseClick,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** 一段课在某一列中的位置 */
private data class Block(val course: TimetableCourse, val slot: CourseSlot)

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().height(DAY_HEADER_HEIGHT)) {
        for (label in WEEKDAY_LABELS) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    blocks: List<Block>,
    customizations: Map<String, CourseCustomization>,
    onCourseClick: (TimetableCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        blocks.sortedBy { it.slot.startPeriod }.forEach { block ->
            val key = block.course.code.ifBlank { block.course.name }
            CourseBlockView(
                block = block,
                customization = customizations[key] ?: CourseCustomization(),
                onClick = { onCourseClick(block.course) },
            )
        }
    }
}

@Composable
private fun CourseBlockView(
    block: Block,
    customization: CourseCustomization,
    onClick: () -> Unit,
) {
    val slot = block.slot
    val swatch = TimetableColors.forCourse(block.course, customization.colorIndex)
    val topPad = PERIOD_HEIGHT * (slot.startPeriod - 1)
    val blockHeight = PERIOD_HEIGHT * slot.span
    val shape = RoundedCornerShape(7.dp)

    Column(
        Modifier
            .padding(top = topPad)
            .fillMaxWidth()
            .height(blockHeight)
            .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
            .clip(shape)
            .background(swatch.container)
            .then(
                if (slot.isAdjusted) {
                    Modifier.border(1.dp, swatch.content.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
    ) {
        Text(
            text = block.course.name,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = swatch.content,
            maxLines = if (slot.span >= 3) 4 else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (slot.span >= 2) {
            val room = slot.room.ifBlank {
                block.course.slots.firstOrNull()?.room.orEmpty()
            }
            if (room.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = room,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = swatch.content.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (customization.starred && slot.span >= 2) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = "★",
                fontSize = 9.sp,
                color = swatch.content.copy(alpha = 0.9f),
            )
        }
    }
}
