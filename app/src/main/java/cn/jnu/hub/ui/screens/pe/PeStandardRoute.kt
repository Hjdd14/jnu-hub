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
import cn.jnu.hub.feature.pe.PeStandardPage
import cn.jnu.hub.feature.pe.PeStandardTable
import cn.jnu.hub.feature.pe.PeStandardViewModel
import cn.jnu.hub.feature.pe.PeUiState
import cn.jnu.hub.feature.pe.peFeatureInit
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

/**
 * 体测评分标准页（替换原占位实现）。
 *
 * 数据来自 PHTest_Standard.aspx：服务端按「年级 + 性别」渲染出各项目评分表，
 * 每张表 = 一个测试项目（身高体重 / 肺活量 / 50米跑 / 坐位体前屈 / 立定跳远 / 引体向上 / 1000米跑 …）。
 * 顶部用胶囊切换项目，下面展示该项目的完整评分档位。
 */
@Composable
fun PeStandardRoute(onBack: () -> Unit, onNeedLogin: () -> Unit = {}) {
    val context = LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }

    val vm: PeStandardViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    // 不在 LaunchedEffect 里自动跳登录页。
    // 否则「PE_LOGIN 点返回 → 回到本页 → 本页检测到未登录 → 又跳回 PE_LOGIN」
    // 形成死循环，用户永远退不出体测模块。
    // 本页已有 PeLoginRequiredState(onNeedLogin) 引导，由用户点击再跳。

    PeSubScaffold(
        title = "评分标准",
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.load() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
    ) {
        when (val s = state) {
            is PeUiState.Loading -> LoadingState(text = "正在加载评分标准…")
            is PeUiState.LoginRequired -> PeLoginRequiredState(onNeedLogin)
            is PeUiState.Error -> ErrorState(
                title = "评分标准加载失败",
                message = s.message,
                actionText = "重试",
                onAction = { vm.load() },
            )
            is PeUiState.Content -> PeStandardContent(s.value)
        }
    }
}

@Composable
private fun PeStandardContent(page: PeStandardPage) {
    if (page.tables.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (page.notice.isNotBlank()) {
                Box(Modifier.padding(Dimens.screenPadding)) {
                    PeHintSurface(main = page.notice, sub = "")
                }
            }
            EmptyState(
                title = "暂无评分标准",
                message = "站点当前没有下发评分表，请稍后再试。",
            )
        }
        return
    }

    var selectedIndex by remember(page.tables.size) { mutableIntStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, page.tables.lastIndex)
    val current: PeStandardTable = page.tables[safeIndex]

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
        if (page.student.name.isNotBlank() || page.tips.isNotBlank()) {
            item {
                SectionCard(title = "适用标准") {
                    if (page.student.name.isNotBlank()) {
                        PeStudentHeader(student = page.student)
                        Spacer(Modifier.height(Dimens.sm))
                    }
                    Text(
                        text = page.tips.ifBlank {
                            "依据《国家学生体质健康标准》，按年级与性别给出评分档位。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (page.notice.isNotBlank()) {
            item { PeHintSurface(main = page.notice, sub = "") }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                items(page.tables) { table ->
                    val index = page.tables.indexOf(table)
                    val selected = index == safeIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable { selectedIndex = index }
                            .padding(horizontal = Dimens.lg, vertical = Dimens.sm),
                    ) {
                        Text(
                            text = table.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = current.title.ifBlank { "评分档位" }) {
                PeStandardHeaderRow()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                current.rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.project,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1.1f),
                        )
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1.3f),
                        )
                        Row(
                            modifier = Modifier.weight(1.1f),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PeBadge(text = row.grade, color = peGradeColor(row.grade))
                            if (row.grade.isNotBlank()) Spacer(Modifier.width(Dimens.xs))
                            Text(
                                text = row.score.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = peGradeColor(row.grade),
                            )
                        }
                        Text(
                            text = row.points.ifBlank { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.width(44.dp),
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }

        item {
            Text(
                text = "共 " + page.tables.size + " 个项目；数据来自体测系统 PHTest_Standard 页面，",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeStandardHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
        Text(
            text = "项目",
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
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = "分值",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}
