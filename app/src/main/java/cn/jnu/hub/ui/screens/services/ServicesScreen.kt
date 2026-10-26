@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.theme.Dimens

private data class ServiceItem(
    val title: String,
    val short: String,
    val color: Color,
    val route: String?,
    val note: String? = null,
)

/**
 * 服务入口清单。
 *
 * 设计原则：只保留「点了一定有反应」的项。
 * - 无 URL 或需要微信授权的，要么给出明确提示，要么不展示（避免用户点了没反应）。
 */
private val demoServices = listOf(
    ServiceItem("水电查询", "查", Color(0xFF34A853), Routes.UTILITY_QUERY),
    ServiceItem("水电缴费", "缴", Color(0xFFEA4335), Routes.UTILITY_PAY),
    ServiceItem("校园网", "网", Color(0xFF4285F4), Routes.CAMPUS_NET),
    ServiceItem("师资查询", "师", Color(0xFF4F9BEA), Routes.FACULTY),
    ServiceItem("体测成绩", "体", Color(0xFFF9AB00), Routes.PE_RESULT),
    ServiceItem("评分标准", "标", Color(0xFF11A39A), Routes.PE_STANDARD),
    ServiceItem("我的课表", "课", Color(0xFF4285F4), Routes.JW_TIMETABLE),
    ServiceItem("成绩查询", "绩", Color(0xFF34A853), Routes.JW_GRADES),
    ServiceItem("综合测评", "测", Color(0xFFA45BC9), Routes.ST_EVALUATION),
    ServiceItem("考试安排", "考", Color(0xFFF57C00), Routes.JW_EXAMS),
    // Blackboard 只对 JBJI（伯明翰联合学院）开放，作为附加入口
    ServiceItem("Blackboard", "BB", Color(0xFF00558C), Routes.BB_HOME),
    ServiceItem("服务大厅", "厅", Color(0xFF00897B), "https://ehall.jnu.edu.cn"),
    ServiceItem("校内通知", "通", Color(0xFFD32F2F), "https://www.jnu.edu.cn/tz/list.psp"),
    // 校园卡走 auth7 微信网关，只能跳转（页面内会提示用微信打开）
    ServiceItem(
        "校园卡", "卡", Color(0xFF7B1FA2),
        "https://weixinfwh.jnu.edu.cn/wechat/url/redirectJkbh.html?jkbh=0006",
        note = "需微信打开",
    ),
    ServiceItem("假期值班", "假", Color(0xFFF57C00), "https://webapi4.jnu.edu.cn/jqzb"),
)

@Composable
fun ServicesScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("服务", style = MaterialTheme.typography.headlineSmall) },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.screenPadding,
                end = Dimens.screenPadding,
                top = Dimens.sm,
                bottom = Dimens.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.md),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
        ) {
            items(demoServices) { item ->
                ServiceTile(item) {
                    val r = item.route
                    when {
                        r == null -> Unit
                        r.startsWith("http") -> onNavigate(Routes.web(r, item.title))
                        else -> onNavigate(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceTile(item: ServiceItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(item.color.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.short,
                    style = MaterialTheme.typography.titleMedium,
                    color = item.color,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (item.note != null) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}