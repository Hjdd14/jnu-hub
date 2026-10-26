@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.pe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.pe.PeBookingViewModel
import cn.jnu.hub.feature.pe.PeExemptViewModel
import cn.jnu.hub.feature.pe.PePrescriptionViewModel
import cn.jnu.hub.feature.pe.PePrescription
import cn.jnu.hub.feature.pe.PeUiState
import cn.jnu.hub.feature.pe.peFeatureInit
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

/**
 * 体测预约页（PHTest_Booking.aspx）。
 * 站点只提供「查看预约 / 查看预约场次」，预约动作在服务端完成，这里做只读展示。
 */
@Composable
fun PeBookingRoute(onBack: () -> Unit, onNeedLogin: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }
    val vm: PeBookingViewModel = viewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    // 不在 LaunchedEffect 里自动跳登录页。
    // 否则「PE_LOGIN 点返回 → 回到本页 → 本页检测到未登录 → 又跳回 PE_LOGIN」
    // 形成死循环，用户永远退不出体测模块。
    // 本页已有 PeLoginRequiredState(onNeedLogin) 引导，由用户点击再跳。

    PeSubScaffold("体测预约", onBack) {
        when (val s = state) {
            is PeUiState.Loading -> LoadingState(text = "正在加载预约信息…")
            is PeUiState.LoginRequired -> PeLoginRequiredState(onNeedLogin)
            is PeUiState.Error -> ErrorState(message = s.message, onAction = { vm.load() })
            is PeUiState.Content -> {
                val info = s.value
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.lg,
                        bottom = Dimens.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                ) {
                    if (info.notice.isNotBlank() || info.tip.isNotBlank()) {
                        item {
                            PeHintSurface(
                                main = info.notice,
                                sub = info.tip,
                            )
                        }
                    }
                    if (info.items.isNotEmpty()) {
                        item {
                            SectionCard(title = "体测项目") {
                                info.items.forEach { item ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        PeBadge(
                                            text = item.status.ifBlank { "未知" },
                                            color = if (item.status.contains("未")) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                    }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                    if (info.myBookings.isNotEmpty()) {
                        item {
                            SectionCard(title = "我的预约") {
                                info.myBookings.forEach { t ->
                                    Text(
                                        text = t,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = Dimens.xs),
                                    )
                                }
                            }
                        }
                    }
                    if (info.items.isEmpty() && info.myBookings.isEmpty() && info.notice.isBlank()) {
                        item {
                            EmptyState(
                                title = "暂无预约信息",
                                message = "当前没有开放的预约场次。",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 免测申请页（PHTest_ExemptApply.aspx） */
@Composable
fun PeExemptRoute(onBack: () -> Unit, onNeedLogin: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }
    val vm: PeExemptViewModel = viewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    // 不在 LaunchedEffect 里自动跳登录页。
    // 否则「PE_LOGIN 点返回 → 回到本页 → 本页检测到未登录 → 又跳回 PE_LOGIN」
    // 形成死循环，用户永远退不出体测模块。
    // 本页已有 PeLoginRequiredState(onNeedLogin) 引导，由用户点击再跳。

    PeSubScaffold("免测申请", onBack) {
        when (val s = state) {
            is PeUiState.Loading -> LoadingState(text = "正在加载免测信息…")
            is PeUiState.LoginRequired -> PeLoginRequiredState(onNeedLogin)
            is PeUiState.Error -> ErrorState(message = s.message, onAction = { vm.load() })
            is PeUiState.Content -> {
                val info = s.value
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.lg,
                        bottom = Dimens.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                ) {
                    if (info.notice.isNotBlank()) {
                        item { PeHintSurface(main = info.notice, sub = "") }
                    }
                    if (info.noticeText.isNotBlank()) {
                        item {
                            SectionCard(title = "申请须知") {
                                Text(
                                    text = info.noticeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(info.records) { r ->
                        SectionCard(title = "申请记录") {
                            Text(
                                text = r,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (info.noticeText.isBlank() && info.records.isEmpty()) {
                        item {
                            EmptyState(
                                title = "暂无免测申请",
                                message = "如需免测，请按学校通知准备材料后在官网提交。",
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 运动处方页（PHTest_ExercisePrescription.aspx） */
@Composable
fun PePrescriptionRoute(onBack: () -> Unit, onNeedLogin: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }
    val vm: PePrescriptionViewModel = viewModel()
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    // 不在 LaunchedEffect 里自动跳登录页。
    // 否则「PE_LOGIN 点返回 → 回到本页 → 本页检测到未登录 → 又跳回 PE_LOGIN」
    // 形成死循环，用户永远退不出体测模块。
    // 本页已有 PeLoginRequiredState(onNeedLogin) 引导，由用户点击再跳。

    PeSubScaffold("运动处方", onBack) {
        when (val s = state) {
            is PeUiState.Loading -> LoadingState(text = "正在加载运动处方…")
            is PeUiState.LoginRequired -> PeLoginRequiredState(onNeedLogin)
            is PeUiState.Error -> ErrorState(message = s.message, onAction = { vm.load() })
            is PeUiState.Content -> PePrescriptionContent(s.value)
        }
    }
}

@Composable
private fun PePrescriptionContent(p: PePrescription) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.lg,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item {
            SectionCard(title = "个人信息") {
                PeInfoLine("年级", p.gradeName)
                PeInfoLine("性别", p.gender)
                PeInfoLine("年龄", p.age)
                PeInfoLine("班级", p.major)
                PeInfoLine("评分", p.score)
                PeInfoLine("等级", p.grade)
            }
        }
        if (p.items.isNotEmpty()) {
            item {
                SectionCard(title = "测试项目") {
                    PeScoreHeaderRow()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    p.items.forEach { item -> PeScoreRow(item = item) }
                }
            }
        }
        items(p.diagnosis) { d ->
            SectionCard(title = d.title) {
                Text(
                    text = d.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeInfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 二级页面统一外壳（预约 / 免测 / 处方 / 主页共用） */
@Composable
fun PeSubScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = actions,
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}