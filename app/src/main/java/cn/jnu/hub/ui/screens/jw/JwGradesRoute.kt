@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.GradesContent
import cn.jnu.hub.feature.jw.JwGrade
import cn.jnu.hub.feature.jw.JwGradesViewModel
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.theme.Dimens
import cn.jnu.hub.ui.theme.SuccessGreen
import cn.jnu.hub.ui.theme.ErrorRed

@Composable
fun JwGradesRoute(
    onBack: () -> Unit,
    viewModel: JwGradesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync = rememberJwSync(viewModel)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("成绩查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 点按 = 重新查询（清缓存强制重抓）
                    // 长按 = 重置 OkHttp 探测标志：教务从 403 恢复后，用户可自助切回直连
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.refresh() },
                            onLongClick = {
                                cn.jnu.hub.feature.jw.JwApi.resetOkHttpRejectedFlag()
                                viewModel.refresh()
                            },
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "重新查询（长按重探直连）",
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is JwUiState.Loading -> LoadingState(text = "正在获取成绩…")
                is JwUiState.SessionRequired -> JwSessionRequiredState(onSync = sync.request)
                is JwUiState.PermissionDenied -> JwSessionRequiredState(
                    onSync = sync.request,
                    permissionDenied = true,
                )
                is JwUiState.Error -> ErrorState(
                    message = s.message,
                    onAction = { viewModel.load() },
                )
                is JwUiState.Content -> GradesBody(
                    content = s.value,
                    onSelectSemester = viewModel::selectSemester,
                )
            }
        }
    }
}

@Composable
private fun GradesBody(
    content: GradesContent,
    onSelectSemester: (String) -> Unit,
) {
    if (content.semesters.isEmpty()) {
        EmptyState(
            title = "暂无可查询的学期",
            message = "教务系统没有返回学期列表，请稍后重试",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item(key = "semester") {
            SemesterPicker(
                semesters = content.semesters,
                selected = content.selected,
                onSelect = onSelectSemester,
            )
        }

        content.stats?.let { stats ->
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                    StatCard(
                        label = "总学分",
                        value = jwNumberText(stats.credits),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "加权平均分",
                        value = "%.1f".format(stats.weightedScore),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "课程数",
                        value = stats.count.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (content.grades.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "暂无成绩",
                    message = "${content.selected.ifBlank { "该学期" }} 的成绩尚未发布",
                )
            }
        } else {
            items(content.grades, key = { it.XNXQDM + "|" + it.KCH + "|" + it.KCM }) { grade ->
                GradeCard(grade)
            }
            item(key = "footer") {
                Text(
                    text = "共 ${content.grades.size} 门课程 · 数据来自教务系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.md),
                )
            }
        }
    }
}

@Composable
private fun SemesterPicker(
    semesters: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "学年学期",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = selected.ifBlank { "请选择学期" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "选择学期")
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                semesters.forEach { dm ->
                    DropdownMenuItem(
                        text = { Text(dm, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onSelect(dm)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(vertical = Dimens.md, horizontal = Dimens.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GradeCard(grade: JwGrade) {
    val failed = grade.numericScore?.let { it < 60.0 } == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = grade.KCM,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    grade.XF.takeIf { it.isNotBlank() }?.let {
                        GradeTag(text = "$it 学分")
                    }
                    grade.JD.takeIf { it.isNotBlank() }?.let {
                        GradeTag(text = "绩点 $it")
                    }
                    grade.KCKSDM_DISPLAY.takeIf { it.isNotBlank() }?.let {
                        GradeTag(text = it)
                    }
                }
            }
            Spacer(Modifier.width(Dimens.md))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = grade.displayScore.ifBlank { "—" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when {
                        failed -> ErrorRed
                        grade.numericScore != null -> SuccessGreen
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (grade.KCCJ_DISPLAY.isNotBlank()) {
                    Text(
                        text = grade.KCCJ_DISPLAY,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GradeTag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = 2.dp),
        )
    }
}