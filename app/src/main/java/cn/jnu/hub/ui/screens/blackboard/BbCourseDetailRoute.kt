@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.blackboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.blackboard.*
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.MetricCard
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 课程详情 4 个 Tab */
enum class BbTab(val label: String) {
    ANNOUNCEMENT("通知"),
    ASSIGNMENT("作业"),
    GRADE("成绩"),
    RECORDING("回放"),
}

/** 单个 Tab 的通用状态 */
sealed interface BbTabState<out T> {
    data object Loading : BbTabState<Nothing>
    data object NeedsLogin : BbTabState<Nothing>
    /** 已登录但没有该模块的访问权限（非 JBJI 学生常见） */
    data object NoAccess : BbTabState<Nothing>
    data class Error(val message: String) : BbTabState<Nothing>
    data class Ready<T>(val data: T) : BbTabState<T>
}

fun Throwable.toBbTabState(): BbTabState<Nothing> = when {
    isBbNoAccess() -> BbTabState.NoAccess
    isBbSessionExpired() -> BbTabState.NeedsLogin
    else -> BbTabState.Error(toBbMessage())
}

// ———————————————————————————————— ViewModel ————————————————————————————————

class BbCourseDetailViewModel(courseId: String) : ViewModel() {

    val courseId: String = courseId

    var courseName by mutableStateOf("")
        private set

    var overview by mutableStateOf<BbCourseOverview?>(null)
        private set

    var announcements by mutableStateOf<BbTabState<List<BbAnnouncement>>>(BbTabState.Loading)
        private set

    var assignments by mutableStateOf<BbTabState<BbAssignmentSheet>>(BbTabState.Loading)
        private set

    var grades by mutableStateOf<BbTabState<BbGradeSheet>>(BbTabState.Loading)
        private set

    var recordings by mutableStateOf<BbTabState<BbRecordingSheet>>(BbTabState.Loading)
        private set

    var refreshing by mutableStateOf(false)
        private set

    /** 整门课都不可用时的终态（无权限 / 未登录），非空则整页替换成提示 */
    var terminalState by mutableStateOf<BbTabState<Nothing>?>(null)
        private set

    /** 同步弹层里被拒绝授权时直接切到无权限终态 */
    fun markNoAccess() {
        terminalState = BbTabState.NoAccess
        announcements = BbTabState.NoAccess
        assignments = BbTabState.NoAccess
        grades = BbTabState.NoAccess
        recordings = BbTabState.NoAccess
    }

    /** 同步成功：清掉终态并重置已加载标记，让各 Tab 重新请求一遍 */
    fun markNoAccessGuardReset() {
        terminalState = null
        loaded.clear()
    }

    private val loaded = mutableSetOf<BbTab>()
    private var overviewJob: Job? = null
    private val tabJobs = mutableMapOf<BbTab, Job>()

    init {
        loadOverview()
    }

    /** 课程主页 + 内容列表（作业 Tab 直接依赖 contents） */
    fun loadOverview() {
        overviewJob?.cancel()
        terminalState = null
        overviewJob = viewModelScope.launch {
            try {
                val o = BbApi.fetchCourseOverview(courseId)
                overview = o
                if (o.course.courseName.isNotBlank()) courseName = o.course.courseName
            } catch (t: Throwable) {
                // 课程主页都拿不到：四个 Tab 统一进入同一个终态，
                // 免得用户在 4 个 Tab 之间来回点都只看得到空白
                val terminal = when {
                    t.isBbNoAccess() -> BbTabState.NoAccess
                    t.isBbSessionExpired() -> BbTabState.NeedsLogin
                    else -> null
                }
                if (terminal != null) {
                    announcements = terminal
                    assignments = terminal
                    grades = terminal
                    recordings = terminal
                    terminalState = terminal
                }
            }
        }
    }

    /** 切到某个 Tab 时按需加载（只加载一次） */
    fun ensureLoaded(tab: BbTab, force: Boolean = false) {
        if (!force && tab in loaded) return
        loaded += tab
        tabJobs[tab]?.cancel()
        when (tab) {
            BbTab.ANNOUNCEMENT -> {
                announcements = BbTabState.Loading
                tabJobs[tab] = viewModelScope.launch {
                    announcements = try {
                        BbTabState.Ready(BbApi.fetchAnnouncementsSmart(courseId, overview?.course))
                    } catch (t: Throwable) {
                        t.toBbTabState()
                    }
                }
            }

            BbTab.ASSIGNMENT -> {
                assignments = BbTabState.Loading
                tabJobs[tab] = viewModelScope.launch {
                    assignments = try {
                        // 交给 BbApi.fetchAssignments 统一处理合并：
                        // 它会同时扫「课程内容区」（uploadAssignment + launchAssessment）
                        // 和「成绩中心里还没出分的条目」，再按 content_id / 标题去重。
                        // 只扫内容区是之前的 bug —— 以 test_on.gif 出现的测评类作业
                        // （如 GT CA1）会被整条漏掉，表现为「成绩里有、作业里没有」。
                        val sheet = BbApi.fetchAssignments(courseId, overview?.course)
                        BbTabState.Ready(sheet)
                    } catch (t: Throwable) {
                        t.toBbTabState()
                    }
                }
            }

            BbTab.GRADE -> {
                grades = BbTabState.Loading
                tabJobs[tab] = viewModelScope.launch {
                    grades = try {
                        BbTabState.Ready(BbApi.fetchGradesSmart(courseId))
                    } catch (t: Throwable) {
                        t.toBbTabState()
                    }
                }
            }

            BbTab.RECORDING -> {
                recordings = BbTabState.Loading
                tabJobs[tab] = viewModelScope.launch {
                    recordings = try {
                        BbTabState.Ready(BbApi.fetchRecordings(courseId, overview?.course))
                    } catch (t: Throwable) {
                        t.toBbTabState()
                    }
                }
            }
        }
    }

    /** 顶部刷新：重拉课程主页 + 当前 Tab */
    fun refresh(tab: BbTab) {
        refreshing = true
        loadOverview()
        ensureLoaded(tab, force = true)
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            refreshing = false
        }
    }
}

// ———————————————————————————————— 页面 ————————————————————————————————

/**
 * 课程详情：通知 / 作业 / 成绩 / 回放 四个 Tab。
 *
 * 所有 Tab 都是按需加载（第一次切过去才请求），避免一进课程就打 4 个请求。
 */
@Composable
fun BbCourseDetailRoute(
    onBack: () -> Unit,
    courseId: String,
    onLogin: () -> Unit,
    onOpenAnnouncement: (announcementId: String) -> Unit,
    onOpenWeb: (url: String, title: String) -> Unit,
    vm: BbCourseDetailViewModel = viewModel(key = "bb_course_" + courseId) {
        BbCourseDetailViewModel(courseId)
    },
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showSync by rememberSaveable { mutableStateOf(false) }
    val current = BbTab.entries.getOrElse(tab) { BbTab.ANNOUNCEMENT }

    LaunchedEffect(tab) {
        vm.ensureLoaded(current)
    }
    LaunchedEffect(vm.overview) {
        // 课程主页回来之后补一次（作业 / 回放依赖它）
        if (vm.overview != null) vm.ensureLoaded(current)
    }

    BbScaffold(
        title = vm.courseName.ifBlank { "课程" },
        subtitle = vm.overview?.course?.courseName,
        onBack = onBack,
        refreshing = vm.refreshing,
        onRefresh = { vm.refresh(current) },
    ) { padding ->
        val terminal = vm.terminalState
        if (terminal != null) {
            // 整门课不可用（无权限 / 未登录）：不显示 4 个空 Tab，直接给对应提示
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when (terminal) {
                    is BbTabState.NoAccess -> BbNoAccessState(
                        onBack = onBack,
                        onOpenBrowser = {
                            onOpenWeb(
                                BbCourse.BASE + "/webapps/blackboard/execute/courseMain?course_id=" + courseId,
                                "Blackboard",
                            )
                        },
                    )
                    else -> BbNoticePane(
                        title = "需要同步 Blackboard 登录状态",
                        message = "Blackboard 需要单独建立一次自己的登录会话，点下面的按钮即可。",
                        detail = "统一认证仍然有效时，一般无需再输入密码。",
                        icon = Icons.Filled.Sync,
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
                        OutlinedButton(onClick = onBack) { Text("返回") }
                        Spacer(Modifier.width(Dimens.md))
                        Button(onClick = { showSync = true }) { Text("同步 Blackboard") }
                    }
                }
            }
            return@BbScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = Dimens.screenPadding,
                divider = {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                },
            ) {
                BbTab.entries.forEachIndexed { index, item ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    BbTab.ANNOUNCEMENT -> Icons.Filled.Campaign
                                    BbTab.ASSIGNMENT -> Icons.AutoMirrored.Filled.Assignment
                                    BbTab.GRADE -> Icons.Filled.Grade
                                    BbTab.RECORDING -> Icons.Filled.PlayCircle
                                },
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.iconSm),
                            )
                        },
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                when (current) {
                    BbTab.ANNOUNCEMENT -> AnnouncementTab(
                        state = vm.announcements,
                        onRetry = { vm.ensureLoaded(BbTab.ANNOUNCEMENT, force = true) },
                        onLogin = onLogin,
                        onClick = { onOpenAnnouncement(it.id) },
                    )

                    BbTab.ASSIGNMENT -> AssignmentTab(
                        state = vm.assignments,
                        onRetry = { vm.ensureLoaded(BbTab.ASSIGNMENT, force = true) },
                        onLogin = onLogin,
                        onOpenWeb = onOpenWeb,
                    )

                    BbTab.GRADE -> GradeTab(
                        state = vm.grades,
                        onRetry = { vm.ensureLoaded(BbTab.GRADE, force = true) },
                        onLogin = onLogin,
                    )

                    BbTab.RECORDING -> RecordingTab(
                        state = vm.recordings,
                        onRetry = { vm.ensureLoaded(BbTab.RECORDING, force = true) },
                        onLogin = onLogin,
                        onOpenWeb = onOpenWeb,
                    )
                }
            }
        }
    }

    // 同步弹层：成功后重拉，被拒绝授权则整页切到无权限态
    if (showSync) {
        BbSyncDialog(
            onSynced = {
                showSync = false
                vm.markNoAccessGuardReset()
                vm.loadOverview()
                vm.ensureLoaded(current, force = true)
            },
            onNoAccess = {
                showSync = false
                vm.markNoAccess()
            },
            onDismiss = { showSync = false },
        )
    }
}

/**
 * 单个 Tab 无权限时的提示。
 *
 * 用「轻量说明 + 重试」而不是整页 BbNoAccessState：
 * 用户可能只是这一门课的内容区没开权限，课程名和其它 Tab 还能看。
 */
@Composable
private fun TabNoAccess(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        BbNoticePane(
            title = "没有访问权限",
            message = "这部分内容仅对伯明翰联合学院（JBJI）学生开放。",
            detail = "如果你的账号应当有权限，可以点下面的按钮再试一次。",
            icon = Icons.Filled.LockPerson,
            tint = MaterialTheme.colorScheme.tertiary,
        ) {
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

// ———————————————————————————————— 通知 Tab ————————————————————————————————

@Composable
private fun AnnouncementTab(
    state: BbTabState<List<BbAnnouncement>>,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onClick: (BbAnnouncement) -> Unit,
) {
    when (state) {
        is BbTabState.Loading -> LoadingState("正在加载通知…")
        is BbTabState.NeedsLogin -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            BbNeedLoginState(onLogin = onLogin)
        }

        is BbTabState.NoAccess -> TabNoAccess(onRetry = onRetry)

        is BbTabState.Error -> ErrorState(
            message = state.message,
            onAction = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.xxxl),
        )

        is BbTabState.Ready -> {
            val list = state.data
            if (list.isEmpty()) {
                EmptyState(title = "暂无通知", message = "该课程还没有发布公告")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.md,
                        bottom = Dimens.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                ) {
                    items(list, key = { it.id }) { item ->
                        BbListItem(
                            title = item.title,
                            meta = item.metaLine(),
                            preview = item.preview,
                            badge = if (item.attachments.isNotEmpty()) item.attachments.size.toString() + " 附件" else null,
                            onClick = { onClick(item) },
                        )
                    }
                }
            }
        }
    }
}

// ———————————————————————————————— 作业 Tab ————————————————————————————————

@Composable
private fun AssignmentTab(
    state: BbTabState<BbAssignmentSheet>,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
) {
    when (state) {
        is BbTabState.Loading -> LoadingState("正在加载作业…")
        is BbTabState.NeedsLogin -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            BbNeedLoginState(onLogin = onLogin)
        }

        is BbTabState.NoAccess -> TabNoAccess(onRetry = onRetry)

        is BbTabState.Error -> ErrorState(
            message = state.message,
            onAction = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.xxxl),
        )

        is BbTabState.Ready -> {
            val sheet = state.data
            if (sheet.isEmpty) {
                // 区分「真的一件都没有」和「有东西但没取到」：
                // 后者要说明原因，否则用户只会以为 App 漏了作业。
                EmptyState(
                    title = "暂无作业",
                    message = sheet.note.ifBlank { "这门课程还没有布置作业或测评" },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.md,
                        bottom = Dimens.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                ) {
                    if (sheet.note.isNotBlank()) {
                        item {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = sheet.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(Dimens.md),
                                )
                            }
                        }
                    }
                    items(sheet.items, key = { it.contentId + "|" + it.title }) { item ->
                        AssignmentCard(item, onOpenWeb)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignmentCard(item: BbAssignment, onOpenWeb: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(Dimens.cardPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dimens.sm))
                BbTag(
                    text = when (item.source) {
                        BbAssignmentSource.ASSESSMENT -> "测评"
                        BbAssignmentSource.GRADEBOOK -> "待提交"
                        BbAssignmentSource.CONTENT -> "作业"
                    },
                    color = when (item.source) {
                        BbAssignmentSource.ASSESSMENT -> MaterialTheme.colorScheme.secondary
                        BbAssignmentSource.GRADEBOOK -> MaterialTheme.colorScheme.tertiary
                        BbAssignmentSource.CONTENT -> MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.width(Dimens.xs))
                BbTag(
                    text = if (item.isGraded) {
                        item.grade + (if (item.pointsPossible.isNotBlank()) "/" + item.pointsPossible else "")
                    } else {
                        "未评分"
                    },
                    color = if (item.isGraded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.dueDate.isNotBlank()) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = "截止：" + item.dueDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (item.attempts.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = "已提交 " + item.attempts.size + " 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                // 测评 / 成绩中心条目没有可解析的详情页，给出明确的「去原页面」出口，
                // 而不是留一张只有标题的卡片让用户干瞪眼。
                if (item.instructionsText.isBlank() && item.files.isEmpty() && item.openUrl.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.md))
                    Text(
                        text = "这项作业的说明在 Blackboard 原页面里，点下面按钮查看与提交。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.sm))
                    OutlinedButton(onClick = { onOpenWeb(absoluteBbUrl(item.openUrl), item.title) }) {
                        Text("在 Blackboard 中打开")
                    }
                }
                if (item.instructionsText.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.md))
                    Text(
                        text = item.instructionsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.files.forEach { file ->
                    BbAttachmentRow(file) { url, title -> onOpenWeb(url, title) }
                }
                item.attempts.forEach { attempt ->
                    if (attempt.files.isNotEmpty()) {
                        Spacer(Modifier.height(Dimens.sm))
                        Text(
                            text = "我的提交" + attempt.date.takeIf { it.isNotBlank() }?.let { "（" + it + "）" }.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        attempt.files.forEach { file ->
                            BbAttachmentRow(file) { url, title -> onOpenWeb(url, title) }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = "点击展开说明与附件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ———————————————————————————————— 成绩 Tab ————————————————————————————————

@Composable
private fun GradeTab(
    state: BbTabState<BbGradeSheet>,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
) {
    when (state) {
        is BbTabState.Loading -> LoadingState("正在加载成绩…")
        is BbTabState.NeedsLogin -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            BbNeedLoginState(onLogin = onLogin)
        }

        is BbTabState.NoAccess -> TabNoAccess(onRetry = onRetry)

        is BbTabState.Error -> ErrorState(
            message = state.message,
            onAction = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.xxxl),
        )

        is BbTabState.Ready -> {
            val sheet = state.data
            if (sheet.items.isEmpty()) {
                EmptyState(title = "暂无成绩", message = "该课程还没有成绩记录")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.screenPadding,
                        end = Dimens.screenPadding,
                        top = Dimens.md,
                        bottom = Dimens.xxxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
                ) {
                    if (sheet.totals.isNotEmpty()) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.cardSpacing)) {
                                sheet.totals.take(2).forEach { total ->
                                    MetricCard(
                                        label = total.item,
                                        value = total.grade.ifBlank { "—" },
                                        modifier = Modifier.weight(1f),
                                        caption = total.status.takeIf { it.isNotBlank() },
                                        warn = total.status.contains("Needs Grading", ignoreCase = true),
                                    )
                                }
                            }
                        }
                    }

                    if (sheet.details.isNotEmpty()) {
                        item {
                            SectionCard(title = "成绩明细（已出分 " + sheet.gradedCount + " / " + sheet.details.size + "）") {
                                sheet.details.forEachIndexed { index, grade ->
                                    GradeRow(grade)
                                    if (index < sheet.details.size - 1) {
                                        HorizontalDivider(
                                            Modifier.padding(vertical = Dimens.xs),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "成绩以 Blackboard 为准；未出分的项目显示为「—」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeRow(grade: BbGrade) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = grade.item,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                grade.category.takeIf { it.isNotBlank() },
                grade.activityType.takeIf { it.isNotBlank() },
                grade.lastActivity.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Dimens.md))
        Text(
            text = if (grade.hasGrade) grade.grade else "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (grade.hasGrade) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ———————————————————————————————— 回放 Tab ————————————————————————————————

@Composable
private fun RecordingTab(
    state: BbTabState<BbRecordingSheet>,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
) {
    when (state) {
        is BbTabState.Loading -> LoadingState("正在查找课堂回放…")
        is BbTabState.NeedsLogin -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            BbNeedLoginState(onLogin = onLogin)
        }

        is BbTabState.NoAccess -> TabNoAccess(onRetry = onRetry)

        is BbTabState.Error -> ErrorState(
            message = state.message,
            onAction = onRetry,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.xxxl),
        )

        is BbTabState.Ready -> {
            val sheet = state.data
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = Dimens.md,
                    bottom = Dimens.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
            ) {
                if (sheet.recordings.isNotEmpty()) {
                    item {
                        SectionCard(title = "课堂回放（" + sheet.recordings.size + "）") {
                            sheet.recordings.forEachIndexed { index, rec ->
                                BbRow(
                                    icon = Icons.Filled.PlayCircle,
                                    title = rec.title,
                                    subtitle = rec.url,
                                    onClick = { onOpenWeb(rec.url, rec.title) },
                                )
                                if (index < sheet.recordings.size - 1) {
                                    Spacer(Modifier.height(Dimens.sm))
                                }
                            }
                        }
                    }
                } else {
                    item {
                        EmptyState(
                            title = "没有找到课堂回放",
                            message = "该课程的 Panopto 回放可能未开放，或链接挂在其他页面下",
                        )
                    }
                }

                if (sheet.panoptoEntry.isNotBlank()) {
                    item {
                        BbRow(
                            icon = Icons.Filled.PlayCircle,
                            title = "打开 Panopto 工具页",
                            subtitle = "在课程里查看全部录像",
                            onClick = {
                                onOpenWeb(
                                    absoluteBbUrl(sheet.panoptoEntry),
                                    "Panopto",
                                )
                            },
                        )
                    }
                }

                item {
                    Text(
                        text = "回放由 Panopto 提供，点击会在应用内浏览器打开（已带登录状态）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun absoluteBbUrl(url: String): String = when {
    url.startsWith("http") -> url
    url.startsWith("/") -> BbCourse.BASE + url
    else -> BbCourse.BASE + "/" + url
}
