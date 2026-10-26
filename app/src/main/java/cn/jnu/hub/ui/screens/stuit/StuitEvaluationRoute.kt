@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.stuit.*
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 综合测评页状态 */
sealed interface EvalState {
    data object Loading : EvalState
    /** 缺少学工系统业务会话，需要用户同步一次 */
    data object NeedsSession : EvalState
    data class Error(val message: String) : EvalState
    data class Ready(val data: StuitEvaluation) : EvalState
}

class StuitEvaluationViewModel : ViewModel() {
    var state by mutableStateOf<EvalState>(EvalState.Loading)
        private set

    private var job: Job? = null

    init { load() }

    fun load() {
        job?.cancel()
        state = EvalState.Loading
        job = viewModelScope.launch {
            state = try {
                EvalState.Ready(StuitApi.fetchEvaluation())
            } catch (t: Throwable) {
                // 被取消的旧请求不要覆盖新请求的状态
                if (t.isStuitCancellation()) return@launch
                if (t.isStuitSessionMissing()) EvalState.NeedsSession
                else EvalState.Error(t.stuitUserMessage())
            }
        }
    }
}

@Composable
fun StuitEvaluationRoute(
    onBack: () -> Unit,
    vm: StuitEvaluationViewModel = viewModel(),
) {
    val sync = rememberStuitSync { vm.load() }

    StuitScaffold(title = "综合测评", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                is EvalState.Loading -> LoadingState("正在加载综合测评…")
                is EvalState.NeedsSession -> StuitNeedsSessionScreen(onSync = { sync.open() })
                is EvalState.Error -> ErrorState(
                    message = s.message,
                    onAction = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )
                is EvalState.Ready -> EvaluationContent(s.data)
            }
        }
    }

    sync.Host()
}

@Composable
private fun EvaluationContent(data: StuitEvaluation) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.sm,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item { TotalScoreCard(data) }

        item { StudentInfoCard(data.header) }

        item { BasicQualityCard(data) }

        item { AcademicCard(data) }

        item { ComprehensiveCard(data) }
    }
}

// ———————————————————————— 顶部总分大卡 ————————————————————————

@Composable
private fun TotalScoreCard(data: StuitEvaluation) {
    val header = data.header
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // 用 primaryContainer 而不是 primary：深色主题下 primary 是浅色底，
        // 配 onPrimary 的低对比文字会看不清（真机踩过）。
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.padding(Dimens.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = data.templateName.ifBlank { "综合测评" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(Dimens.sm))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = fmtScore(header.totalScore),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(Dimens.sm))
                    Text(
                        text = "分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (header.classRank != null) {
                    Spacer(Modifier.height(Dimens.sm))
                    Text(
                        text = buildString {
                            append("班级排名 ").append(header.classRank)
                            header.classPercentile?.let { append("  |  ").append("%.2f%%".format(it)) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.92f),
                    )
                }
            }
            ScoreGauge(
                score = header.totalScore,
                maxScore = 100.0,
                color = MaterialTheme.colorScheme.primary,
                centerLabel = "满分 100",
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                mutedColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

// ———————————————————————— 学生信息 ————————————————————————

@Composable
private fun StudentInfoCard(header: StuitEvalHeader) {
    val rows = listOf(
        "学号" to header.studentId,
        "姓名" to header.name,
        "性别" to header.gender,
        "学院" to header.college,
        "专业" to header.major,
        "年级" to header.grade,
        "自然班" to header.className,
    ).filter { it.second.isNotBlank() }

    if (rows.isEmpty()) return

    SectionCard(title = "基本信息") {
        Column {
            rows.forEachIndexed { index, (label, value) ->
                StuitInfoRow(label, value)
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

// ———————————————————————— 基础性素养 ————————————————————————

@Composable
private fun BasicQualityCard(data: StuitEvaluation) {
    var expanded by remember { mutableStateOf(true) }
    val items = data.basicItems
    if (items.isEmpty() && data.basicScore == null) return

    SectionCard(
        title = "基础性素养",
        actionText = if (items.isNotEmpty()) (if (expanded) "收起" else "展开明细") else null,
        onAction = { expanded = !expanded },
    ) {
        Column {
            ScoreHeadline(data.basicScore, "由学生个人、班级、学院三类主体分别评分")
            AnimatedVisibility(visible = expanded && items.isNotEmpty()) {
                Column {
                    Spacer(Modifier.height(Dimens.md))
                    items.forEachIndexed { index, item ->
                        BasicItemBlock(item)
                        if (index < items.lastIndex) {
                            Spacer(Modifier.height(Dimens.md))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            Spacer(Modifier.height(Dimens.md))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicItemBlock(item: StuitBasicItem) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.index,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(Dimens.sm))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fmtScore(item.total),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (item.range.isNotBlank()) {
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = "分值 " + item.range,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
        Spacer(Modifier.height(Dimens.sm))
        // 三类主体评分横条
        Column(
            Modifier.padding(start = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            ScoreBar("学生评分", item.studentScore, item.total ?: 0.0, MaterialTheme.colorScheme.primary)
            ScoreBar("班级评分", item.classScore, item.total ?: 0.0, MaterialTheme.colorScheme.tertiary)
            ScoreBar("学院评分", item.collegeScore, item.total ?: 0.0, MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ScoreBar(label: String, value: Double?, max: Double, color: androidx.compose.ui.graphics.Color) {
    if (value == null) return
    val ratio = if (max <= 0.0) 0f else (value / max).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.width(Dimens.sm))
        Text(
            text = fmtScore(value, 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp),
        )
    }
}

// ———————————————————————— 学业成绩 ————————————————————————

@Composable
private fun AcademicCard(data: StuitEvaluation) {
    var showAll by remember { mutableStateOf(false) }
    val courses = data.academicCourses
    if (courses.isEmpty() && data.academicScore == null) return
    val visible = if (showAll) courses else courses.take(5)

    SectionCard(
        title = "学业成绩",
        actionText = if (courses.size > 5) (if (showAll) "收起" else "全部 " + courses.size + " 门") else null,
        onAction = { showAll = !showAll },
    ) {
        Column {
            ScoreHeadline(data.academicScore, "Σ(单科成绩×学分) / Σ学分")
            if (courses.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.md))
                visible.forEachIndexed { index, course ->
                    CourseRow(course)
                    if (index < visible.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseRow(course: StuitEvalCourse) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            val sub = listOf(course.type, course.term, if (course.credit.isNotBlank()) course.credit + " 学分" else "")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Dimens.md))
        Text(
            text = course.score.ifBlank { "-" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ———————————————————————— 综合素养（五育） ————————————————————————

@Composable
private fun ComprehensiveCard(data: StuitEvaluation) {
    if (data.fiveEdu.isEmpty() && data.comprehensiveScore == null) return

    SectionCard(title = "综合素养 · 五育") {
        Column {
            ScoreHeadline(data.comprehensiveScore, "德育 · 智育 · 体育 · 美育 · 劳育")
            if (data.fiveEdu.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.lg))
                FiveEduBarChart(data.fiveEdu)
            }
        }
    }
}

// ———————————————————————— 公用：块标题 + 分值 ————————————————————————

@Composable
private fun ScoreHeadline(score: Double?, caption: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = fmtScore(score),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(Dimens.md))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
