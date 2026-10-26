@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.theme.Dimens

/**
 * 学业 Tab：聚合教务系统 + 学生工作管理系统。
 * 两个系统都走 auth4 网关，与 Blackboard / 水电共用同一 CAS 会话。
 */
@Composable
fun StudyScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("学业", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "教务系统 · 学生工作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                SectionCard(title = "教务系统") {
                    EntryRow("我的课表", "本周课程安排", Icons.Outlined.CalendarMonth, Color(0xFF4285F4)) {
                        onNavigate(Routes.JW_TIMETABLE)
                    }
                    DividerSoft()
                    EntryRow("成绩查询", "各学期成绩与绩点", Icons.Outlined.Assessment, Color(0xFF34A853)) {
                        onNavigate(Routes.JW_GRADES)
                    }
                    DividerSoft()
                    EntryRow("考试安排", "期末考试时间与地点", Icons.Outlined.EventNote, Color(0xFFF9AB00)) {
                        onNavigate(Routes.JW_EXAMS)
                    }
                    DividerSoft()
                    EntryRow("学业完成", "学分进度与必修要求", Icons.Outlined.DonutLarge, Color(0xFFEA4335)) {
                        onNavigate(Routes.JW_CREDITS)
                    }
                    DividerSoft()
                    EntryRow("个人信息", "学籍信息与照片", Icons.Outlined.Badge, Color(0xFF6C7BDB)) {
                        onNavigate(Routes.JW_PROFILE)
                    }
                }
            }

            item {
                SectionCard(title = "学生工作") {
                    EntryRow("综合测评", "总分、排名与五育表现", Icons.Outlined.EmojiEvents, Color(0xFFA45BC9)) {
                        onNavigate(Routes.ST_EVALUATION)
                    }
                    DividerSoft()
                    EntryRow("专业排名", "各维度排名与百分比", Icons.Outlined.Leaderboard, Color(0xFF11A39A)) {
                        onNavigate(Routes.ST_RANKING)
                    }
                    DividerSoft()
                    EntryRow("学生成绩", "按学年查看课程成绩", Icons.Outlined.ListAlt, Color(0xFF4285F4)) {
                        onNavigate(Routes.ST_GRADES)
                    }
                    DividerSoft()
                    EntryRow("基本信息", "个人信息与证件", Icons.Outlined.Person, Color(0xFF34A853)) {
                        onNavigate(Routes.ST_BASIC)
                    }
                }
            }

            item {
                SectionCard(title = "更多服务") {
                    EntryRow("奖助学金 / 荣誉称号", "申请与查询", Icons.Outlined.CardGiftcard, Color(0xFFF57C00)) {
                        onNavigate(
                            Routes.web(
                                "https://stuit.jnu.edu.cn/student/scholarship/studentScholarAppList.jsp",
                                "奖助学金",
                            ),
                        )
                    }
                    DividerSoft()
                    EntryRow("困难认定 / 勤工助学", "资助与工作记录", Icons.Outlined.VolunteerActivism, Color(0xFF2FA84F)) {
                        onNavigate(
                            Routes.web(
                                "https://stuit.jnu.edu.cn/student/hardship/hardshipAppList.jsp",
                                "困难认定",
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DividerSoft() {
    HorizontalDivider(
        Modifier.padding(start = 56.dp, top = Dimens.sm, bottom = Dimens.sm),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
    )
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
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
}
