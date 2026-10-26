package cn.jnu.hub.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** 底部 5 个主 Tab */
enum class TopLevelDest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home("home", "首页", Icons.Filled.Home, Icons.Outlined.Home),
    Study("study", "学业", Icons.Filled.School, Icons.Outlined.School),
    Courses("courses", "课程", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    Services("services", "服务", Icons.Filled.Apps, Icons.Outlined.Apps),
    Profile("profile", "我的", Icons.Filled.Person, Icons.Outlined.Person),
}

/** 二级页面路由 */
object Routes {
    // 登录
    const val LOGIN = "login"

    // 水电（查询与缴费独立入口）
    const val UTILITY_QUERY = "utility_query"
    const val UTILITY_PAY = "utility_pay"

    // 校园网
    const val CAMPUS_NET = "campus_net"

    // Blackboard
    const val BB_HOME = "bb_home"
    const val BB_COURSE = "bb_course/{courseId}"
    const val BB_COURSE_ARG = "courseId"
    const val BB_ANNOUNCEMENT = "bb_announcement/{courseId}/{announcementId}"
    const val BB_ANN_ARG = "announcementId"

    // 主 Tab 路由别名
    val COURSES = TopLevelDest.Courses.route
    val SERVICES = TopLevelDest.Services.route

    // 师资
    const val FACULTY = "faculty"

    // 教务
    const val JW_TIMETABLE = "jw_timetable"
    const val JW_GRADES = "jw_grades"
    const val JW_EXAMS = "jw_exams"
    const val JW_CREDITS = "jw_credits"
    const val JW_PROFILE = "jw_profile"

    // 学工
    const val ST_EVALUATION = "st_evaluation"
    const val ST_RANKING = "st_ranking"
    const val ST_GRADES = "st_grades"
    const val ST_BASIC = "st_basic"

    // 体测
    const val PE_RESULT = "pe_result"
    const val PE_STANDARD = "pe_standard"
    const val PE_LOGIN = "pe_login"
    const val PE_HOME = "pe_home"
    const val PE_BOOKING = "pe_booking"
    const val PE_EXEMPT = "pe_exempt"
    const val PE_PRESCRIPTION = "pe_prescription"

    // 通用
    const val WEB = "web?url={url}&title={title}"
    const val SESSION_DEBUG = "session_debug"

    fun bbCourse(id: String) = "bb_course/${id}"

    fun bbAnnouncement(courseId: String, annId: String) = "bb_announcement/$courseId/$annId"

    fun web(url: String, title: String) =
        "web?url=${android.net.Uri.encode(url)}&title=${android.net.Uri.encode(title)}"
}