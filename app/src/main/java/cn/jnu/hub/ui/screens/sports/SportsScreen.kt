package cn.jnu.hub.ui.screens.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.components.MetricCard
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportsScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("体测", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.statusBars,
                modifier = Modifier.windowInsetsPadding(WindowInsets(0, 0, 0, 0)),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                bottom = Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
        ) {
            item {
                SectionCard(title = "2026 学年") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
                        MetricCard(
                            label = "总分",
                            value = "--",
                            caption = "暂未开放查询",
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            label = "标准分",
                            value = "--",
                            caption = "暂未开放查询",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                SectionCard(title = "历年成绩") {
                    MenuRow("2025 学年", "总分 49 · 不及格") { onNavigate(Routes.PE_RESULT) }
                    DividerSoft()
                    MenuRow("2024 学年", "总分 50 · 不及格") { onNavigate(Routes.PE_RESULT) }
                }
            }
            item {
                SectionCard(title = "体测服务") {
                    MenuRow("评分标准", "各年级 / 性别评分细则") { onNavigate(Routes.PE_STANDARD) }
                    DividerSoft()
                    MenuRow("预约测试", "查看与预约体测项目") { }
                    DividerSoft()
                    MenuRow("免测申请", "上传证明材料提交申请") { }
                    DividerSoft()
                    MenuRow("运动处方", "体型 / 肺功能 / 耐力评估") { }
                }
            }
        }
    }
}

@Composable
private fun DividerSoft() {
    HorizontalDivider(
        Modifier.padding(vertical = Dimens.sm),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    )
}

@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}