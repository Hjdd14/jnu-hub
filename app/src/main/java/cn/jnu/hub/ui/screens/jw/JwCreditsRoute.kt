@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package cn.jnu.hub.ui.screens.jw

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.JwCreditSummary
import cn.jnu.hub.feature.jw.JwCreditsViewModel
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

@Composable
fun JwCreditsRoute(
    onBack: () -> Unit,
    viewModel: JwCreditsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val xh by viewModel.xh.collectAsStateWithLifecycle()
    val needStudentId by viewModel.needStudentId.collectAsStateWithLifecycle()
    val sync = rememberJwSync(viewModel)

    LaunchedEffect(Unit) { viewModel.ensureLoaded() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("学分完成情况") },
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
                is JwUiState.Loading -> LoadingState(text = "正在计算学分进度…")
                is JwUiState.SessionRequired -> JwSessionRequiredState(onSync = sync.request)
                is JwUiState.PermissionDenied -> JwSessionRequiredState(
                    onSync = sync.request,
                    permissionDenied = true,
                )
                is JwUiState.Error -> {
                    if (needStudentId || xh.isBlank()) {
                        Column(
                            Modifier.fillMaxSize().padding(Dimens.screenPadding),
                            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                        ) {
                            StudentIdGate(
                                hint = "学分查询需要你的学号，填写一次后本机记住",
                                onSubmit = viewModel::submitStudentId,
                            )
                        }
                    } else {
                        ErrorState(
                            message = s.message,
                            onAction = { viewModel.load() },
                        )
                    }
                }
                is JwUiState.Content -> CreditsBody(
                    summary = s.value,
                    onChangeStudent = { viewModel.submitStudentId(it) },
                    xh = xh,
                )
            }
        }
    }
}

@Composable
private fun CreditsBody(
    summary: JwCreditSummary,
    xh: String,
    onChangeStudent: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        // 总进度环
        SectionCard(title = "总学分进度") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CreditRing(done = summary.WCXF, total = summary.YQXF)
                Spacer(Modifier.height(Dimens.md))
                Text(
                    text = summary.PYFAMC.ifBlank { "培养方案" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 各模块进度
        SectionCard(title = "各模块要求") {
            ModuleProgress(
                label = "专业教育",
                done = summary.ZYJYBXZXF,
                total = summary.ZYJYBXYQXF,
            )
            ModuleProgress(
                label = "基础教育部",
                done = summary.JCJYBXZXF,
                total = summary.JCJYBXYQXF,
            )
            ModuleProgress(
                label = "通识教育",
                done = summary.TSJYBXZXF,
                total = summary.TSJYBXYQXF,
            )
        }

        // 明细
        SectionCard(title = "学分明细") {
            KeyValueRow("要求学分", jwNumberText(summary.YQXF))
            KeyValueRow("完成学分", jwNumberText(summary.WCXF), emphasize = true)
            KeyValueRow("必修学分", jwNumberText(summary.BXYXXF))
            KeyValueRow("选修学分", jwNumberText(summary.XXYXXF))
            KeyValueRow("跨专业选修", jwNumberText(summary.KZYYXXF))
            KeyValueRow("专业教育要求", jwNumberText(summary.ZYJYBXYQXF))
            KeyValueRow("基础教育要求", jwNumberText(summary.JCJYBXYQXF))
            KeyValueRow("通识教育要求", jwNumberText(summary.TSJYBXYQXF))
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Dimens.sm),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            if (editing) {
                StudentIdGate(
                    hint = "更换查询学号",
                    initial = xh,
                    onSubmit = {
                        editing = false
                        onChangeStudent(it)
                    },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "当前学号 $xh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { editing = true }) { Text("更换") }
                }
            }
        }
    }
}