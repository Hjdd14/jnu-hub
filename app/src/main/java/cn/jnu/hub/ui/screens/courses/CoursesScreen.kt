package cn.jnu.hub.ui.screens.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens

private data class CourseItem(val id: String, val name: String, val org: Boolean)

private val demoCourses = listOf(
    CourseItem("_659_1", "Integer Programming and Combinatorial Optimization", false),
    CourseItem("_40_1", "J-BJI (For JNU Side)", true),
    CourseItem("_127_1", "JBJI Summer School", true),
    CourseItem("_109_1", "Past Exam Papers", true),
    CourseItem("_53_1", "UoB Module Feedback", true),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("课程", style = MaterialTheme.typography.headlineSmall) },
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
                SectionCard(title = "我的课程") {
                    val list = demoCourses.filter { !it.org }
                    list.forEachIndexed { i, c ->
                        CourseRow(c) { onNavigate("bb_course/${c.id}") }
                        if (i < list.size - 1) DividerSoft()
                    }
                }
            }
            item {
                SectionCard(title = "机构 / 其他") {
                    val list = demoCourses.filter { it.org }
                    list.forEachIndexed { i, c ->
                        CourseRow(c) { onNavigate("bb_course/${c.id}") }
                        if (i < list.size - 1) DividerSoft()
                    }
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
private fun CourseRow(course: CourseItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(Dimens.md))
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}