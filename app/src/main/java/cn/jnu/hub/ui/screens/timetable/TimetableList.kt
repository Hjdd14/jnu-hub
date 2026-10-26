package cn.jnu.hub.ui.screens.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jnu.hub.feature.timetable.CourseCustomization
import cn.jnu.hub.feature.timetable.CourseSlot
import cn.jnu.hub.feature.timetable.TimetableColors
import cn.jnu.hub.feature.timetable.TimetableCourse
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.theme.Dimens

/**
 * 列表视图：按星期分组，展示本周要上的课。
 *
 * ## 为什么按「时间段」展开而不是按「课程」
 *
 * 早先的实现是「一门课一行、多个时间段挤在同一行」，LazyColumn 的 key 用了
 * `星期-课程号-课程名`，结果同一课程在同一星期有多个时间段时 key 冲突，
 * 直接抛 IllegalArgumentException 闪退：
 *
 *     Key "2-61010012-博弈论与多准则决策-GTMCD" was already used.
 *
 * 现在改成「一个时间段一行」：既让 key 天然唯一（星期+课程号+起止节次），
 * 也顺带修好了体验 —— 一门课两个时间段本来就应该显示两行，
 * 挤在一行里节次信息会互相遮挡。
 */
@Composable
fun TimetableList(
    week: Int,
    courses: List<TimetableCourse>,
    customizations: Map<String, CourseCustomization>,
    onCourseClick: (TimetableCourse) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开成「课程 + 时间段」的行，按星期分组
    val grouped: Map<Int, List<RowItem>> = remember(courses, week) {
        val unsorted = ArrayList<RowItem>()
        for (course in courses) {
            for (slot in course.slots) {
                if (slot.activeIn(week) && slot.weekday in 1..7) {
                    unsorted.add(RowItem(course, slot))
                }
            }
        }
        unsorted.sortWith(ROW_ORDER)
        unsorted.dedupeByKey().groupBy { it.slot.weekday }.toSortedMap()
    }

    if (grouped.isEmpty()) {
        EmptyState(
            title = "第 $week 周没有课",
            message = "切换周次看看其它周",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            bottom = Dimens.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        grouped.forEach { (day, rows) ->
            item(key = "header-$day") {
                DayHeader(day = day, count = rows.map { it.course.code + it.course.name }.distinct().size)
            }
            items(rows, key = { it.uniqueKey }) { row ->
                val key = row.course.code.ifBlank { row.course.name }
                CourseRow(
                    course = row.course,
                    slot = row.slot,
                    customization = customizations[key] ?: CourseCustomization(),
                    onClick = { onCourseClick(row.course) },
                )
            }
        }
    }
}

/**
 * 兜底去重：保证 key 绝对唯一。
 *
 * LazyColumn 的 key 重复会直接抛 IllegalArgumentException 闪退，属于崩溃级问题，
 * 所以这里做最后一道防线 —— 即使教务返回两条完全相同的记录（实测存在这种情况），
 * 也不会让 App 崩掉，只是合并显示为一行。
 */
private fun List<RowItem>.dedupeByKey(): List<RowItem> {
    val seen = HashSet<String>(size)
    return filter { seen.add(it.uniqueKey) }
}

/** 行排序：先星期，再起始节次，最后课程名 */
private val ROW_ORDER = Comparator<RowItem> { a, b ->
    val byDay = a.slot.weekday.compareTo(b.slot.weekday)
    if (byDay != 0) {
        byDay
    } else {
        val byPeriod = a.slot.startPeriod.compareTo(b.slot.startPeriod)
        if (byPeriod != 0) byPeriod else a.course.name.compareTo(b.course.name)
    }
}

/** 列表中的一行：一门课的一个时间段 */
private data class RowItem(val course: TimetableCourse, val slot: CourseSlot) {
    /**
     * LazyColumn 唯一 key。
     *
     * 用「星期-课程号-课程名-起-止-教师哈希」保证唯一：
     * 同一门课同一天的多个时间段起止节次必然不同。
     * 完全重复的记录由 [dedupeByKey] 兜底过滤，双层保险。
     */
    val uniqueKey: String
        get() = buildString {
            append(slot.weekday).append('-')
            append(course.code.ifBlank { course.name }).append('-')
            append(course.name).append('-')
            append(slot.startPeriod).append('-')
            append(slot.endPeriod).append('-')
            append(course.teacher.hashCode())
        }
}

@Composable
private fun DayHeader(day: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = Dimens.sm, bottom = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = weekdayName(day),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Dimens.sm))
        Text(
            text = "$count 门课",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CourseRow(
    course: TimetableCourse,
    slot: CourseSlot,
    customization: CourseCustomization,
    onClick: () -> Unit,
) {
    val swatch = TimetableColors.forCourse(course, customization.colorIndex)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Dimens.cardPadding),
    ) {
        // 左侧色条
        Box(
            Modifier
                .width(4.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(swatch.content),
        )
        Spacer(Modifier.width(Dimens.md))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (customization.starred) {
                    Spacer(Modifier.width(Dimens.xs))
                    Text(
                        text = "★",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (slot.startPeriod == slot.endPeriod) {
                        "第 ${slot.startPeriod} 节"
                    } else {
                        "第 ${slot.startPeriod}-${slot.endPeriod} 节"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (slot.room.isNotBlank()) {
                    Spacer(Modifier.width(Dimens.sm))
                    Text(
                        text = slot.room,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            }

            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (course.teacher.isNotBlank()) append(course.teacher)
                    if (course.weekText.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(course.weekText)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (customization.note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = customization.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
