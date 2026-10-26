@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.ExamsContent
import cn.jnu.hub.feature.jw.JwExam
import cn.jnu.hub.feature.jw.JwExamsViewModel
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.feature.jw.jwFormatMonthDay
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.theme.Dimens

@Composable
fun JwExamsRoute(
    onBack: () -> Unit,
    viewModel: JwExamsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync = rememberJwSync(viewModel)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("考试安排") },
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
                is JwUiState.Loading -> LoadingState(text = "正在获取考试安排…")
                is JwUiState.SessionRequired -> JwSessionRequiredState(onSync = sync.request)
                is JwUiState.PermissionDenied -> JwSessionRequiredState(
                    onSync = sync.request,
                    permissionDenied = true,
                )
                is JwUiState.Error -> ErrorState(
                    message = s.message,
                    onAction = { viewModel.load() },
                )
                is JwUiState.Content -> ExamsBody(s.value)
            }
        }
    }
}

@Composable
private fun ExamsBody(content: ExamsContent) {
    if (content.exams.isEmpty()) {
        EmptyState(
            title = "暂无考试安排",
            message = "${content.semester.mc} 还没有发布考试安排",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item(key = "header") {
            InfoStrip(
                text = "${content.semester.mc} · 共 ${content.exams.size} 场考试，" +
                    "按考试日期从近到远排列",
            )
        }
        items(
            items = content.exams,
            key = { it.KCM + "|" + it.KSRQ + "|" + it.KSMC },
        ) { exam ->
            ExamCard(exam)
        }
    }
}

@Composable
private fun ExamCard(exam: JwExam) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(Dimens.cardPadding)) {
            // 左侧日期块
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = Dimens.md, vertical = Dimens.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = jwFormatMonthDay(exam.KSRQ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                exam.weekdayText.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = exam.KCM.ifBlank { exam.KSMC },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                exam.KSMC.takeIf { it.isNotBlank() && it != exam.KCM }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                }
                exam.timeRange.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "时间 $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val meta = buildList {
                    exam.ZJJSXM.takeIf { it.isNotBlank() }?.let { add("监考 $it") }
                    exam.JASMC.takeIf { it.isNotBlank() }?.let { add("教室 $it") }
                    exam.ZWH.takeIf { it.isNotBlank() }?.let { add("座位 $it") }
                }
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}