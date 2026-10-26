@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.blackboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.blackboard.BbApi
import cn.jnu.hub.feature.blackboard.BbCourse
import cn.jnu.hub.feature.blackboard.BbSession
import cn.jnu.hub.feature.blackboard.toBbMessage
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 课程列表页状态。
 *
 * 三个非正常态必须分开：它们的用户预期完全不同 ——
 * - [NeedsSync]  缺 BB 自己的会话 →「同步 Blackboard」能解决
 * - [NoAccess]   是权限边界（非 JBJI 学生），重试无用，只能说明情况
 * - [Error]      真实故障（断网 / 5xx / 解析），重试有意义
 */
sealed interface BbCoursesState {
    data object Loading : BbCoursesState
    /** 缺 Blackboard 自己的业务会话（不是 CAS 会话过期） */
    data object NeedsSync : BbCoursesState
    data object NoAccess : BbCoursesState
    data class Error(val message: String) : BbCoursesState
    data class Ready(val courses: List<BbCourse>) : BbCoursesState
}

class BbCoursesViewModel : ViewModel() {

    var state by mutableStateOf<BbCoursesState>(BbCoursesState.Loading)
        private set

    var refreshing by mutableStateOf(false)
        private set

    private var job: Job? = null

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        job?.cancel()
        if (silent) {
            refreshing = true
        } else {
            state = BbCoursesState.Loading
        }
        job = viewModelScope.launch {
            val next = try {
                BbCoursesState.Ready(fetchWithBootstrap())
            } catch (t: Throwable) {
                when {
                    t.isBbNoAccess() -> BbCoursesState.NoAccess
                    t.isBbSessionExpired() -> BbCoursesState.NeedsSync
                    else -> BbCoursesState.Error(t.toBbMessage())
                }
            }
            state = next
            refreshing = false
        }
    }

    /** 同步弹层里被 BB 拒绝授权时直接切到无权限态（不用再等一次请求） */
    fun markNoAccess() {
        state = BbCoursesState.NoAccess
    }

    /**
     * 先直接拉课程；缺 BB 会话时**自动静默建立一次**再重试。
     *
     * 多数用户全程零点击：CAS 会话还在，OkHttp 跟一遍首页 302 就能拿到 BB 的 Cookie。
     * 只有 CAS 也失效（真要输密码）才会落到 NeedsSync，让用户点「同步 Blackboard」。
     */
    private suspend fun fetchWithBootstrap(): List<BbCourse> {
        runCatching { BbApi.fetchCourses() }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        if (BbSession.ensureSession(reuseRecent = false)) {
            runCatching { BbApi.fetchCourses() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // 静默路线走不通：把最后一次的真实结论抛出去，由 UI 分档展示
        return BbApi.fetchCourses()
    }
}

/**
 * Blackboard 课程列表（替换原先的假数据）。
 *
 * 数据来自 POST /webapps/portal/execute/tabs/tabAction?action=refreshAjaxModule，
 * 响应是 XML 包 CDATA HTML，解析 launcher?type=Course&id=_659_1 链接。
 *
 * 课程（Course）与机构（Organization）分开展示：机构页往往不是真课程，
 * 混在一起会让「我这学期有哪些课」变得难找。
 */
@Composable
fun BbCoursesRoute(
    onBack: (() -> Unit)? = null,
    onOpenCourse: (String, String) -> Unit,
    // 保留旧参数名只为兼容既有调用点：BB 不再打开 CAS 登录页（会落到网上服务大厅），
    // 所以这个回调现在不再必需，缺会话时统一走内嵌的「同步 Blackboard」弹层。
    onLogin: (() -> Unit)? = null,
    // 兼容无 Scaffold 的旧调用点（原先 CoursesScreen 只有一个 onNavigate）：
    // 同时传入 onNavigate 时按 "bb_course/{id}" 路由，否则调用 onOpenCourse
    onNavigate: ((String) -> Unit)? = null,
    showTopBarBack: Boolean = onBack != null,
    // 在浏览器里打开 BB 首页（无权限兜底 / 同步失败兜底）；不传则按钮自动隐藏
    onOpenBrowser: (() -> Unit)? = null,
    vm: BbCoursesViewModel = viewModel(),
) {
    val openCourse: (String, String) -> Unit = { id, name ->
        if (onNavigate != null) onNavigate("bb_course/" + id) else onOpenCourse(id, name)
    }

    var showSync by rememberSaveable { mutableStateOf(false) }

    // 浏览器兜底：优先调用方注入的 onOpenBrowser；否则走导航的通用 web 路由
    val openBrowserInWeb: () -> Unit = {
        when {
            onOpenBrowser != null -> onOpenBrowser()
            onNavigate != null -> onNavigate(BB_HOME_WEB_ROUTE)
            else -> Unit
        }
    }

    BbScaffold(
        title = "课程",
        onBack = if (showTopBarBack) onBack else null,
        refreshing = vm.refreshing,
        onRefresh = { vm.load(silent = true) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                is BbCoursesState.Loading -> LoadingState("正在加载 Blackboard 课程…")

                // 缺 BB 自己的会话：不再跳 CAS（那会落到网上服务大厅，回来还是没会话），
                // 而是在内嵌 WebView 里去 BB 首页真实走一次，让 BB 下发自己的 Cookie。
                is BbCoursesState.NeedsSync -> Box(
                    Modifier.fillMaxSize().align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    BbNeedSyncScreen(
                        onSync = { showSync = true },
                        onOpenBrowser = openBrowserInWeb,
                        onBack = onBack,
                    )
                }

                is BbCoursesState.NoAccess -> BbNoAccessState(
                    onBack = onBack,
                    onOpenBrowser = {
                        if (onOpenBrowser != null) onOpenBrowser()
                        else if (onNavigate != null) onNavigate(BB_HOME_WEB_ROUTE)
                    },
                    modifier = Modifier.align(Alignment.Center),
                )

                is BbCoursesState.Error -> ErrorState(
                    message = s.message,
                    onAction = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )

                is BbCoursesState.Ready -> {
                    val courses = s.courses.filter { !it.isOrganization }
                    val orgs = s.courses.filter { it.isOrganization }
                    if (s.courses.isEmpty()) {
                        BbEmpty(
                            title = "没有课程",
                            message = "Blackboard 上暂未找到你的课程或机构页",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        CourseList(
                            courses = courses,
                            orgs = orgs,
                            onOpen = openCourse,
                        )
                    }
                }
            }
        }
    }

    // 同步弹层：成功后重拉列表；被拒绝授权则直接切到「仅 JBJI 可用」
    if (showSync) {
        BbSyncDialog(
            onSynced = {
                showSync = false
                vm.load()
            },
            onNoAccess = {
                showSync = false
                vm.markNoAccess()
            },
            onDismiss = { showSync = false },
        )
    }
}

/** 浏览器兜底用的通用 web 路由（与 Routes.WEB 的格式一致："web?url=..&title=.."） */
private const val BB_HOME_WEB_ROUTE = "web?url=https%3A%2F%2Fblackboard.jnu.edu.cn%2F&title=Blackboard"

@Composable
private fun CourseList(
    courses: List<BbCourse>,
    orgs: List<BbCourse>,
    onOpen: (String, String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.sm,
            bottom = Dimens.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        if (courses.isNotEmpty()) {
            item {
                SectionCard(title = "我的课程（" + courses.size + "）") {
                    courses.forEachIndexed { index, course ->
                        BbRow(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            title = course.name,
                            onClick = { onOpen(course.id, course.name) },
                        )
                        if (index < courses.size - 1) BbDivider()
                    }
                }
            }
        }

        if (orgs.isNotEmpty()) {
            item {
                SectionCard(title = "机构 / 其他（" + orgs.size + "）") {
                    orgs.forEachIndexed { index, org ->
                        BbRow(
                            icon = Icons.Filled.Apartment,
                            title = org.name,
                            tint = MaterialTheme.colorScheme.secondary,
                            onClick = { onOpen(org.id, org.name) },
                        )
                        if (index < orgs.size - 1) BbDivider()
                    }
                }
            }
        }

        item {
            Text(
                text = "数据来自 Blackboard Learn。下拉顶部图标可刷新。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.sm),
            )
        }
    }
}

@Composable
private fun BbDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = Dimens.xs),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}
