@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.timetable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.jw.JwUiState
import cn.jnu.hub.feature.timetable.CourseCustomization
import cn.jnu.hub.feature.timetable.TimetableCourse
import cn.jnu.hub.feature.timetable.TimetableData
import cn.jnu.hub.feature.timetable.TimetablePrefs
import cn.jnu.hub.feature.timetable.TimetableViewModel
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.screens.jw.JwSessionRequiredState
import cn.jnu.hub.ui.screens.jw.rememberJwSync
import cn.jnu.hub.ui.theme.Dimens

/**
 * 我的课表主页面。
 *
 * 会话引导复用课程组已有的 JwSessionRequiredState + rememberJwSync：
 * 教务会话由 JwSession 负责 WebView 换票与 cookie 轮询，
 * 这里只负责把「需要同步」的状态渲染出来。
 *
 * @param onBack 返回
 */
@Composable
fun TimetableRoute(
    onBack: () -> Unit,
    viewModel: TimetableViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val fromCache by viewModel.fromCache.collectAsStateWithLifecycle()
    val cachedAt by viewModel.cachedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 用户个性化设置（颜色/备注/收藏）
    val prefs = remember(context) { TimetablePrefs.get(context) }
    var customizations by remember { mutableStateOf<Map<String, CourseCustomization>>(emptyMap()) }
    var selected by remember { mutableStateOf<TimetableCourse?>(null) }
    var isGrid by rememberSaveable { mutableStateOf(true) }
    val sync = rememberJwSync(viewModel)

    // 首次进入：注入 Context（缓存需要）→ 有缓存先渲染再后台刷新
    LaunchedEffect(Unit) {
        viewModel.attach(context)
        customizations = prefs.all()
    }

    fun refreshCustomizations() {
        customizations = prefs.all()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的课表", style = MaterialTheme.typography.titleMedium)
                        (state as? JwUiState.Content)?.let { c ->
                            Text(
                                text = c.value.semesterName.ifBlank { c.value.semesterCode },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state is JwUiState.Content) {
                        IconButton(
                            onClick = { viewModel.forceRefresh() },
                            enabled = !refreshing,
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is JwUiState.Loading -> TimetableLoadingState()

                is JwUiState.SessionRequired -> JwSessionRequiredState(
                    onSync = sync.request,
                )

                // 教务对 App 直连恒 403，属于「限制」而非「故障」，
                // 所以给一个能真正看到课表的出口，而不是让用户干等重试。
                is JwUiState.PermissionDenied -> ErrorState(
                    title = "无法获取课表数据",
                    message = "教务系统对 App 直接请求有限制，可以打开学校原始页面查看。",
                    actionText = "重试",
                    onAction = { viewModel.forceRefresh() },
                )

                is JwUiState.Error -> ErrorState(
                    message = s.message,
                    onAction = { viewModel.forceRefresh() },
                )

                is JwUiState.Content -> TimetableContentBody(
                    data = s.value,
                    week = week,
                    isGrid = isGrid,
                    refreshing = refreshing,
                    fromCache = fromCache,
                    cachedAt = cachedAt,
                    customizations = customizations,
                    onToggleView = { isGrid = it },
                    onPrev = viewModel::prevWeek,
                    onNext = viewModel::nextWeek,
                    onJumpToday = viewModel::jumpToCurrentWeek,
                    onRefresh = { viewModel.forceRefresh() },
                    onCourseClick = { selected = it },
                )
            }

            // 失败态统一补一个「打开学校原始课表」出口
            if (state is JwUiState.PermissionDenied || state is JwUiState.Error) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    OutlinedButton(
                        onClick = { openJwWeb(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.screenPadding, vertical = Dimens.xxl),
                    ) {
                        Text("打开学校原始课表")
                    }
                }
            }
        }
    }

    selected?.let { course ->
        val key = course.code.ifBlank { course.name }
        CourseDetailDialog(
            course = course,
            currentWeek = week,
            customization = customizations[key] ?: CourseCustomization(),
            onDismiss = { selected = null },
            onToggleStar = {
                prefs.toggleStar(course)
                refreshCustomizations()
            },
            onSaveNote = { note ->
                prefs.setNote(course, note)
                refreshCustomizations()
            },
            onPickColor = { index ->
                prefs.setColor(course, index)
                refreshCustomizations()
            },
        )
    }
}

@Composable
private fun TimetableContentBody(
    data: TimetableData,
    week: Int,
    isGrid: Boolean,
    refreshing: Boolean,
    fromCache: Boolean,
    cachedAt: Long,
    customizations: Map<String, CourseCustomization>,
    onToggleView: (Boolean) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToday: () -> Unit,
    onRefresh: () -> Unit,
    onCourseClick: (TimetableCourse) -> Unit,
) {
    if (data.courses.isEmpty()) {
        EmptyState(
            title = "本学期暂无课程",
            message = data.semesterName.ifBlank { data.semesterCode } + " 没有排课数据",
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimetableViewToggle(isGrid = isGrid, onToggle = onToggleView)
            // 缓存时间提示：让用户知道眼前的是不是旧数据
            if (fromCache && cachedAt > 0L) {
                Text(
                    text = "数据更新于 " + formatRelativeTime(cachedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        WeekSelector(
            week = week,
            weekCount = data.weekCount,
            onPrev = onPrev,
            onNext = onNext,
            onJumpToday = onJumpToday,
            onRefresh = onRefresh,
            refreshing = refreshing,
        )

        // 后台刷新时只加一条细进度条，不打断阅读
        if (refreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
            )
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }

        if (isGrid) {
            TimetableGrid(
                week = week,
                courses = data.courses,
                customizations = customizations,
                onCourseClick = onCourseClick,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TimetableList(
                week = week,
                courses = data.courses,
                customizations = customizations,
                onCourseClick = onCourseClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 课表加载态。
 *
 * 首次进入要等 10-25 秒（WebView 走完整门户流程再旁路抓接口），
 * 所以必须把「为什么慢」告诉用户，否则会被当成卡死。
 */
@Composable
private fun TimetableLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Dimens.lg))
            Text(
                text = "正在同步课表…",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = "首次约 10-20 秒，之后秒开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 打开学校原始课表页面（教务门户），作为兜底出口 */
private fun openJwWeb(context: android.content.Context) {
    runCatching {
        context.startActivity(
            cn.jnu.hub.feature.jw.JwWebActivity.intent(
                context,
                cn.jnu.hub.core.network.JnuHosts.JW + "/new/index.html",
                "我的课表",
            ),
        )
    }
}

/** 把时间戳格式化成「刚刚 / N 分钟前 / N 小时前 / N 天前」 */
private fun formatRelativeTime(savedAt: Long): String {
    if (savedAt <= 0L) return ""
    val diff = System.currentTimeMillis() - savedAt
    if (diff < 0) return "刚刚"
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> minutes.toString() + " 分钟前"
        minutes < 60 * 24 -> (minutes / 60).toString() + " 小时前"
        else -> (minutes / (60 * 24)).toString() + " 天前"
    }
}
