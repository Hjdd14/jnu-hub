@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cn.jnu.hub.core.session.Service
import cn.jnu.hub.core.session.SessionState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.MetricCard
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.components.SessionPill
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.theme.Dimens
import java.time.LocalDate

private val weekNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var data by remember { mutableStateOf(HomeData()) }
    var refreshing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        data = HomeRepository.load(context)
        refreshing = false
    }

    val today = LocalDate.now()
    val dateText = "%d月%d日 %s".format(today.monthValue, today.dayOfMonth, weekNames[today.dayOfWeek.value - 1])

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = Dimens.screenPadding, end = Dimens.screenPadding),
            ) {
                Spacer(Modifier.height(Dimens.md))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("暨大通", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SessionPill(
                        remainingMinutes = if (data.anyLoggedIn) 28 else 0,
                        onClick = { onNavigate(Routes.SESSION_DEBUG) },
                    )
                }
                Spacer(Modifier.height(Dimens.lg))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Dimens.screenPadding, end = Dimens.screenPadding, bottom = Dimens.xxl),
            verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
        ) {
            if (refreshing) {
                item { LoadingState("正在同步各系统数据…") }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                    MetricCard(
                        label = "水电余额",
                        value = formatBalance(data.utilityBalance),
                        unit = if (data.utilityBalance != null) "元" else null,
                        caption = data.utilityAccount?.let { "账号 " + it } ?: "点击进入查看",
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Routes.UTILITY_QUERY) },
                    )
                    // 校园网：首页展示**到期日期**（yyyy-MM-dd），余额放到说明行
                    MetricCard(
                        label = "校园网到期",
                        value = data.campusNetExpire ?: "--",
                        caption = when {
                            data.campusNetExpire != null && data.campusNetBalance != null ->
                                "余额 " + data.campusNetBalance
                            data.campusNetExpire != null && data.campusNetInfo != null ->
                                data.campusNetInfo
                            data.campusNetExpire != null -> "点击查看详情"
                            else -> "点击进入"
                        },
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Routes.CAMPUS_NET) },
                    )
                }
            }

            item {
                SectionCard(
                    title = "系统状态",
                    actionText = "详情",
                    onAction = { onNavigate(Routes.SESSION_DEBUG) },
                ) {
                    SessionGrid(data.sessions)
                }
            }

            if (data.bbCourses > 0) {
                item {
                    SectionCard(
                        title = "Blackboard",
                        actionText = "进入",
                        onAction = { onNavigate(Routes.COURSES) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Dimens.md))
                            Column {
                                Text("已加入 " + data.bbCourses + " 门课程", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "仅伯明翰联合学院（JBJI）可用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "快捷入口") {
                    Row(Modifier.fillMaxWidth().padding(vertical = Dimens.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QuickEntry("课表", Color(0xFF4285F4)) { onNavigate(Routes.JW_TIMETABLE) }
                        QuickEntry("成绩", Color(0xFF34A853)) { onNavigate(Routes.JW_GRADES) }
                        QuickEntry("综测", Color(0xFFA45BC9)) { onNavigate(Routes.ST_EVALUATION) }
                        QuickEntry("师资", Color(0xFFF57C00)) { onNavigate(Routes.FACULTY) }
                    }
                }
            }

            item {
                SectionCard(title = "常用服务") {
                    ServiceRow("水电缴费", Icons.Filled.Payments) { onNavigate(Routes.UTILITY_PAY) }
                    HorizontalDivider(Modifier.padding(vertical = Dimens.sm), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ServiceRow("体测成绩", Icons.Filled.DirectionsRun) { onNavigate(Routes.PE_RESULT) }
                    HorizontalDivider(Modifier.padding(vertical = Dimens.sm), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ServiceRow("更多服务", Icons.Filled.Apps) { onNavigate(Routes.SERVICES) }
                }
            }
        }
    }
}

private fun formatBalance(v: Double?): String {
    if (v == null || !v.isFinite() || v < 0) return "--"
    return "%.2f".format(v)
}

@Composable
private fun SessionGrid(sessions: Map<Service, SessionState>) {
    Column {
        Service.entries.take(4).forEach { s ->
            val st = sessions[s] ?: SessionState.UNKNOWN
            val color = when (st) {
                SessionState.LOGGED_IN -> Color(0xFF2E7D32)
                SessionState.EXPIRED -> Color(0xFFBA1A1A)
                SessionState.ERROR -> Color(0xFFED8B00)
                SessionState.UNKNOWN -> MaterialTheme.colorScheme.outline
            }
            val text = when (st) {
                SessionState.LOGGED_IN -> "已登录"
                SessionState.EXPIRED -> "未登录"
                SessionState.ERROR -> "异常"
                SessionState.UNKNOWN -> "未知"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                Spacer(Modifier.width(Dimens.sm))
                Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(text, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

@Composable
private fun ServiceRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.md))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickEntry(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.14f), modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(label.take(1), style = MaterialTheme.typography.titleMedium, color = color)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}