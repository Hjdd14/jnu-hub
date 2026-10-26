@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.stuit.STUIT_YEAR_OPTIONS
import cn.jnu.hub.feature.stuit.StuitApi
import cn.jnu.hub.feature.stuit.StuitGrade
import cn.jnu.hub.feature.stuit.StuitGradeSheet
import cn.jnu.hub.feature.stuit.toStuitMessage
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface GradeState {
    data object Loading : GradeState
    /** 缺少学工系统业务会话，需要用户同步一次 */
    data object NeedsSession : GradeState
    data class Error(val message: String) : GradeState
    data class Ready(val sheet: StuitGradeSheet) : GradeState
}

class StuitGradesViewModel : ViewModel() {
    var year by mutableStateOf(STUIT_YEAR_OPTIONS.first())
        private set
    var state by mutableStateOf<GradeState>(GradeState.Loading)
        private set

    private var job: Job? = null

    init { query() }

    fun selectYear(value: String) {
        if (value == year) return
        year = value
        query()
    }

    fun query() {
        job?.cancel()
        state = GradeState.Loading
        val target = year
        job = viewModelScope.launch {
            state = try {
                val list = StuitApi.fetchGrades(target)
                GradeState.Ready(StuitGradeSheet(year = target, grades = list))
            } catch (t: Throwable) {
                // 被取消的旧请求不要覆盖新请求的状态
                if (t.isStuitCancellation()) return@launch
                if (t.isStuitSessionMissing()) GradeState.NeedsSession
                else GradeState.Error(t.stuitUserMessage())
            }
        }
    }
}

@Composable
fun StuitGradesRoute(
    onBack: () -> Unit,
    vm: StuitGradesViewModel = viewModel(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    val sync = rememberStuitSync { vm.query() }

    StuitScaffold(title = "成绩管理", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ———— 学年选择 ————
            Box(Modifier.padding(horizontal = Dimens.screenPadding, vertical = Dimens.sm)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = vm.year,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择学年")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    STUIT_YEAR_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option,
                                    fontWeight = if (option == vm.year) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (option == vm.year) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                vm.selectYear(option)
                            },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                when (val s = vm.state) {
                    is GradeState.Loading -> LoadingState("正在查询 " + vm.year + " 成绩…")
                    is GradeState.NeedsSession -> StuitNeedsSessionScreen(onSync = { sync.open() })
                    is GradeState.Error -> ErrorState(
                        message = s.message,
                        onAction = { vm.query() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                    is GradeState.Ready ->
                        if (s.sheet.grades.isEmpty()) {
                            EmptyState(
                                title = "该学年暂无成绩",
                                message = vm.year + " 没有查询到课程成绩，可以切换其他学年看看",
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            GradeList(s.sheet)
                        }
                }
            }
        }
    }

    sync.Host()
}

@Composable
private fun GradeList(sheet: StuitGradeSheet) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item { SummaryCard(sheet) }
        items(sheet.grades) { grade -> GradeCard(grade) }
    }
}

@Composable
private fun SummaryCard(sheet: StuitGradeSheet) {
    SectionCard(title = sheet.year.ifBlank { "成绩汇总" }) {
        Row(Modifier.fillMaxWidth()) {
            SummaryCell("课程数", sheet.courseCount.toString(), Modifier.weight(1f))
            SummaryCell("总学分", fmtScore(sheet.totalCredit, 1), Modifier.weight(1f))
            SummaryCell("加权平均", fmtScore(sheet.weightedScore, 2), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeCard(grade: StuitGrade) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = grade.course,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Spacer(Modifier.height(Dimens.xs))
                val tags = listOfNotNull(
                    grade.courseType.takeIf { it.isNotBlank() },
                    grade.term.takeIf { it.isNotBlank() },
                    grade.credit.takeIf { it.isNotBlank() }?.let { it + " 学分" },
                    grade.gpa.takeIf { it.isNotBlank() }?.let { "绩点 " + it },
                )
                Text(
                    text = tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val flags = listOfNotNull(
                    grade.retake.takeIf { it.isNotBlank() && it != "否" }?.let { "重修" },
                    grade.makeup.takeIf { it.isNotBlank() && it != "否" }?.let { "补考" },
                    grade.postpone.takeIf { it.isNotBlank() && it != "否" }?.let { "缓考" },
                )
                if (flags.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        flags.forEach { flag ->
                            StuitTag(flag, MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
            Spacer(Modifier.width(Dimens.md))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = grade.score.ifBlank { "-" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (grade.isPassed) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )
                if (grade.passed.isNotBlank()) {
                    StuitTag(
                        text = grade.passed,
                        color = if (grade.isPassed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
