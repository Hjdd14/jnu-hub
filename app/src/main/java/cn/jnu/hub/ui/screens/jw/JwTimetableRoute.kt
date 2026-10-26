@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.JwCourse
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.feature.jw.JwTimetableViewModel
import cn.jnu.hub.feature.jw.TimetableContent
import cn.jnu.hub.feature.jw.jwWeekdayShort
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.theme.Dimens

private const val MAX_PERIOD = 12
private val PERIOD_HEIGHT = 64.dp
private val SOLID_DAYS = 7

/** 课程配色：按课程名稳定散列到主题色的几档，不使用硬编码品牌色以外的新色板 */
private fun courseColors(index: Int): Pair<Color, Color> = when (index % 4) {
    0 -> Color(0xFF0B4C8C) to Color(0xFFFFFFFF)
    1 -> Color(0xFF2E7D32) to Color(0xFFFFFFFF)
    2 -> Color(0xFF7B4E9B) to Color(0xFFFFFFFF)
    else -> Color(0xFFB5651D) to Color(0xFFFFFFFF)
}

@Composable
fun JwTimetableRoute(
    onBack: () -> Unit,
    viewModel: JwTimetableViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<JwCourse?>(null) }
    val sync = rememberJwSync(viewModel)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("我的课表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    (state as? JwUiState.Content)?.let { c ->
                        Text(
                            text = c.value.semester.mc,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Dimens.lg),
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is JwUiState.Loading -> LoadingState(text = "正在获取课表…")
                is JwUiState.SessionRequired -> JwSessionRequiredState(onSync = sync.request)
                is JwUiState.PermissionDenied -> JwSessionRequiredState(
                    onSync = sync.request,
                    permissionDenied = true,
                )
                is JwUiState.Error -> ErrorState(
                    message = s.message,
                    onAction = { viewModel.load() },
                )
                is JwUiState.Content -> TimetableBody(
                    content = s.value,
                    currentWeek = week,
                    onSelectWeek = viewModel::selectWeek,
                    onCourseClick = { selected = it },
                )
            }
        }
    }

    selected?.let { course ->
        CourseDetailDialog(course = course, onDismiss = { selected = null })
    }
}

@Composable
private fun TimetableBody(
    content: TimetableContent,
    currentWeek: Int,
    onSelectWeek: (Int) -> Unit,
    onCourseClick: (JwCourse) -> Unit,
) {
    if (content.courses.isEmpty()) {
        EmptyState(
            title = "本学期暂无课程",
            message = "${content.semester.mc} 没有排课数据",
        )
        return
    }

    // 本周实际要上的课：按 SKZC 位串过滤
    val visible = remember(content.courses, currentWeek) {
        content.courses.filter { it.activeInWeek(currentWeek) && it.SKXQ in 1..7 && it.KSJC in 1..MAX_PERIOD }
    }

    Column(Modifier.fillMaxSize()) {
        WeekSelector(
            weekCount = content.weekCount,
            currentWeek = currentWeek,
            onSelectWeek = onSelectWeek,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(Dimens.lg), contentAlignment = Alignment.Center) {
                Text(
                    text = "第 $currentWeek 周没有课程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val scroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            PeriodColumn()
            DayGrid(
                courses = visible,
                modifier = Modifier.weight(1f),
                onCourseClick = onCourseClick,
            )
        }
    }
}

/** 顶部周次选择条，可横向滑动 */
@Composable
private fun WeekSelector(
    weekCount: Int,
    currentWeek: Int,
    onSelectWeek: (Int) -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(currentWeek) {
        // 让当前周次大致居中
        scroll.animateScrollTo(((currentWeek - 1) * 56).coerceAtLeast(0))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm),
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        for (w in 1..weekCount) {
            val selected = w == currentWeek
            Surface(
                onClick = { onSelectWeek(w) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "第",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$w",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 左侧节次列 */
@Composable
private fun PeriodColumn() {
    Column(Modifier.width(36.dp)) {
        Spacer(Modifier.height(28.dp))
        for (p in 1..MAX_PERIOD) {
            Box(
                Modifier.fillMaxWidth().height(PERIOD_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$p",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 右侧 7 天网格 */
@Composable
private fun DayGrid(
    courses: List<JwCourse>,
    modifier: Modifier = Modifier,
    onCourseClick: (JwCourse) -> Unit,
) {
    // 同名课程同色
    val colorMap = remember(courses) {
        courses.map { it.KCM }.distinct()
            .withIndex()
            .associate { (i, name) -> name to courseColors(i) }
    }

    Column(modifier) {
        // 星期表头
        Row(Modifier.fillMaxWidth()) {
            for (d in 1..SOLID_DAYS) {
                Text(
                    text = jwWeekdayShort(d),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = Dimens.sm),
                )
            }
        }

        Box(Modifier.fillMaxWidth()) {
            // 背景横格 + 分隔线
            Column(Modifier.fillMaxWidth()) {
                for (p in 1..MAX_PERIOD) {
                    HorizontalDivider(
                        modifier = Modifier.height(PERIOD_HEIGHT),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                for (d in 1..SOLID_DAYS) {
                    Box(Modifier.weight(1f).height(PERIOD_HEIGHT * MAX_PERIOD)) {
                        courses.filter { it.SKXQ == d }.forEach { course ->
                            val rowStart = course.KSJC - 1
                            val rowEnd = (if (course.JSJC >= course.KSJC) course.JSJC else course.KSJC) - 1
                            val spanRows = (rowEnd - rowStart + 1).coerceAtLeast(1)
                            val (bg, fg) = colorMap[course.KCM] ?: courseColors(0)
                            Box(
                                Modifier
                                    .padding(start = 2.dp, end = 2.dp, top = (rowStart * PERIOD_HEIGHT.value).dp)
                                    .fillMaxWidth()
                                    .height((spanRows * PERIOD_HEIGHT.value - 2).dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .clickable { onCourseClick(course) }
                                    .padding(horizontal = 3.dp, vertical = 4.dp),
                            ) {
                                Column {
                                    Text(
                                        text = course.KCM,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = fg,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (spanRows >= 2) {
                                        Text(
                                            text = course.JASMC,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = fg.copy(alpha = 0.85f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 课程详情弹窗 */
@Composable
private fun CourseDetailDialog(course: JwCourse, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = {
            Text(
                text = course.KCM,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                KeyValueRow("教师", course.SKJS)
                KeyValueRow("时间", course.SKSJ)
                KeyValueRow("节次", course.periodText + " " + course.weekdayText)
                KeyValueRow("周次", course.weekRanges().ifBlank { "-" })
                KeyValueRow("教室", course.JASMC)
                KeyValueRow("学分", if (course.XF > 0) "${course.XF}" else "")
                KeyValueRow("课程号", course.KCH)
                KeyValueRow("校区", course.XXXQDM_DISPLAY)
            }
        },
    )
}