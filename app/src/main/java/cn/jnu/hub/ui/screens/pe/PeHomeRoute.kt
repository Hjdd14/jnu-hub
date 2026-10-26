@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.pe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.pe.PeHomeViewModel
import cn.jnu.hub.feature.pe.PeUiState
import cn.jnu.hub.feature.pe.peFeatureInit
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

/**
 * 体测入口页（Index.aspx / index_m.aspx）。
 *
 * 体测各子页面都在同一个站点里，这里把入口做成原生卡片：
 * 点击后由外层导航跳到对应的原生页面，而不是打开网页。
 */
@Composable
fun PeHomeRoute(
    onBack: () -> Unit,
    onOpenResult: () -> Unit = {},
    onOpenStandard: () -> Unit = {},
    onOpenBooking: () -> Unit = {},
    onOpenExempt: () -> Unit = {},
    onOpenPrescription: () -> Unit = {},
    onNeedLogin: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(context) { peFeatureInit(context) }

    val vm: PeHomeViewModel = viewModel()
    val state by vm.state.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    // 不在 LaunchedEffect 里自动跳登录页。
    // 否则「PE_LOGIN 点返回 → 回到本页 → 本页检测到未登录 → 又跳回 PE_LOGIN」
    // 形成死循环，用户永远退不出体测模块。
    // 本页已有 PeLoginRequiredState(onNeedLogin) 引导，由用户点击再跳。

    PeSubScaffold(
        title = "体质健康",
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmLogout = true }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出体测登录")
            }
        },
    ) {
        when (val s = state) {
            is PeUiState.Loading -> LoadingState(text = "正在连接体测系统…")
            is PeUiState.LoginRequired -> PeLoginRequiredState(onNeedLogin)
            is PeUiState.Error -> ErrorState(
                title = "体测主页加载失败",
                message = s.message,
                actionText = "重试",
                onAction = { vm.load() },
            )
            is PeUiState.Content -> LazyColumn(
                contentPadding = PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = Dimens.lg,
                    bottom = Dimens.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
            ) {
                item { PeStudentHeader(student = s.value.student) }
                item {
                    SectionCard(title = "常用功能") {
                        PeEntryRow("体测成绩", "往年成绩、总分与各项得分", onOpenResult)
                        PeEntryRow("评分标准", "按年级与性别查看各项目评分档位", onOpenStandard)
                        PeEntryRow("预约测试", "查看待预约项目与我的预约", onOpenBooking)
                        PeEntryRow("免测申请", "查看免测申请须知与历史记录", onOpenExempt)
                        PeEntryRow("运动处方", "体测诊断与个性化运动建议", onOpenPrescription)
                    }
                }
                item {
                    Text(
                        text = "数据来自体质健康测试系统（s2.shishengyun.com），需独立登录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出体测登录？") },
            text = { Text("会清除本地保存的体测账号密码与 Cookie，下次需重新输入验证码登录。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout()
                    onNeedLogin()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PeEntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
