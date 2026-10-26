@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.pe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.pe.PeBookingInfo
import cn.jnu.hub.feature.pe.PeExemptInfo
import cn.jnu.hub.feature.pe.PeGradeLevel
import cn.jnu.hub.feature.pe.PePrescription
import cn.jnu.hub.feature.pe.PeResultViewModel
import cn.jnu.hub.feature.pe.PeScoreReport
import cn.jnu.hub.feature.pe.PeUiState
import cn.jnu.hub.feature.pe.peFeatureInit
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

/**
 * 体测成绩页（替换原占位实现）。
 *
 * 数据来自 s2.shishengyun.com 的 PHTest_StudentScore.aspx / PHTest_StudentScore_H.aspx，
 * 是服务端渲染的 HTML 表格，解析见 PeParser.parseScore。
 * 顶部年份切换支持「本学年」+ 页面里提供的往年链接。
 */
@Composable
fun PeResultRoute(onBack: () -> Unit, onNeedLogin: () -> Unit = {}) {
    val context = LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }

    val vm: PeResultViewModel = viewModel()
    val state by vm.state.collectAsState()
    val year by vm.year.collectAsState()

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    // 会话失效 → 直接引导去登录，避免用户反复点重试
    // 同上：不自动跳转，避免返回即被弹回的循环
    // （本页已有 PeLoginRequiredState 引导按钮）

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("体测成绩") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is PeUiState.Loading -> LoadingState(text = "正在加载体测成绩…")

                is PeUiState.LoginRequired -> ErrorState(
                    title = "需要登录体测系统",
                    message = "体测系统使用独立账号（学号 + 8 位生日密码），请重新登录。",
                    actionText = "去登录",
                    onAction = onNeedLogin,
                )

                is PeUiState.Error -> ErrorState(
                    title = "成绩加载失败",
                    message = s.message,
                    actionText = "重试",
                    onAction = { vm.load() },
                )

                is PeUiState.Content -> PeResultContent(
                    report = s.value,
                    selectedYear = year,
                    onSelectYear = { vm.selectYear(it) },
                )
            }
        }
    }
}

@Composable
private fun PeResultContent(
    report: PeScoreReport,
    selectedYear: String,
    onSelectYear: (String) -> Unit,
) {
    // 年份选项：本学年 + 往年（去重、倒序）。
    // 用「只增不减」的累积集合：不同年份页面下发的往年链接数量不同，
    // 若每次重组都按当前 report 重建，会出现「点 2025 后 2024 按钮消失」。
    var accumulatedYears by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(report.historyYears, selectedYear) {
        accumulatedYears = accumulatedYears +
            report.historyYears.map { it.trim() }.filter { it.isNotBlank() } +
            listOfNotNull(selectedYear.takeIf { it.isNotBlank() })
    }
    val yearOptions = remember(accumulatedYears) {
        buildList {
            add("")
            accumulatedYears.sortedDescending().forEach { add(it) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.lg,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item { PeStudentHeader(student = report.student) }

        item {
            PeYearTabs(
                years = yearOptions,
                selected = selectedYear,
                onSelect = onSelectYear,
            )
        }

        if (report.notice.isNotBlank()) {
            item { PeNotice(report.notice) }
        }

        item { PeTotalCard(report) }

        if (report.items.isNotEmpty()) {
            item {
                SectionCard(title = "测试项目（" + report.year.ifBlank { "本学年" } + "）") {
                    PeScoreHeaderRow()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    report.items.forEach { item -> PeScoreRow(item = item) }
                }
            }
        } else if (report.notice.isBlank()) {
            item {
                EmptyState(
                    title = "暂无成绩记录",
                    message = "本赛季成绩还没上传，参加完测试后再来查看。",
                )
            }
        }

        if (report.vision.any { it.left.isNotBlank() || it.right.isNotBlank() }) {
            item { PeVisionCard(report) }
        }
    }
}

/** 站点提示条（如「暂未开放体测成绩查询！」） */
@Composable
private fun PeNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(Dimens.md),
        )
    }
}

/** 年份切换：横向滚动的胶囊 */
@Composable
private fun PeYearTabs(
    years: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        items(years) { y ->
            val isSelected = y == selected
            val label = if (y.isBlank()) "本学年" else y + " 年"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onSelect(y) }
                    .padding(horizontal = Dimens.lg, vertical = Dimens.sm),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 总分卡片 */
@Composable
private fun PeTotalCard(report: PeScoreReport) {
    val level = when (report.totalGrade) {
        "优秀" -> PeGradeLevel.EXCELLENT
        "良好" -> PeGradeLevel.GOOD
        "及格" -> PeGradeLevel.PASS
        "不及格" -> PeGradeLevel.FAIL
        else -> PeGradeLevel.NONE
    }
    val color = peGradeColor(level)
    val hasScore = report.totalScore.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (hasScore) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (report.year.isNotBlank()) report.year + " 学年总分" else "本学年总分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.xs))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (hasScore) report.totalScore else "—",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasScore) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "  分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Dimens.sm),
                    )
                }
                if (report.totalGrade.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = "等级 " + report.totalGrade,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
            }
            if (report.standardScore.isNotBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "标准分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = report.standardScore,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (report.standardGrade.isNotBlank()) {
                        Text(
                            text = report.standardGrade,
                            style = MaterialTheme.typography.bodySmall,
                            color = peGradeColor(report.standardGrade),
                        )
                    }
                }
            }
        }
    }
}

/** 视力表 */
@Composable
private fun PeVisionCard(report: PeScoreReport) {
    SectionCard(title = "视力") {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "项目",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.4f),
            )
            Text(
                text = "左眼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "右眼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Dimens.sm))
        report.vision.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    text = row.left.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.right.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (report.keratoscope.isNotBlank()) {
            Spacer(Modifier.height(Dimens.sm))
            Text(
                text = "角膜镜佩戴者：" + report.keratoscope,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
