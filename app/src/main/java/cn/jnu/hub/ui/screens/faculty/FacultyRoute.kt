package cn.jnu.hub.ui.screens.faculty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cn.jnu.hub.feature.faculty.TeacherSummary

/**
 * 师资查询的统一入口（导航里注册的就是它）。
 *
 * 为什么把详情页放在这个文件里而不是交给导航：
 * 教师详情页的地址是搜索结果里带的 `cnUrl`（形如
 * `https://faculty.jnu.edu.cn/smkx/anj/list.htm`），既不是纯数字 id，
 * 也没法用 columnId/siteId 直接拼出来，做成导航参数会很别扭。
 * 这里用一层内部状态管理「搜索页 ⇄ 详情页」，导航层只认识一个入口，保持简单。
 *
 * 需要的话，外部也可以直接用 [FacultySearchRoute] / [FacultyDetailRoute] 单独组合。
 */
@Composable
fun FacultyRoute(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<TeacherSummary?>(null) }

    val current = selected
    if (current != null) {
        FacultyDetailRoute(
            onBack = { selected = null },
            homeUrl = current.homeUrl,
            summary = current,
        )
    } else {
        FacultySearchRoute(
            onBack = onBack,
            onOpenTeacher = { teacher -> selected = teacher },
        )
    }
}
