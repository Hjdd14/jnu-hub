@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.faculty

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.faculty.FacultyApi
import cn.jnu.hub.feature.faculty.FacultyCollege
import cn.jnu.hub.feature.faculty.TeacherSummary
import cn.jnu.hub.ui.components.EmptyState
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ───────────────────────── 职称筛选 ─────────────────────────

/**
 * 职称筛选项。
 *
 * 站点里职称共 33 种（含「研究员（自然科学）」这类带括号的长名字），
 * 全部平铺在 Chip 行里会难找，所以这里挑出人数最多的常用项作为快捷筛选，
 * 其余通过「学院」+ 关键词组合查询。
 *
 * 取值来自实测全量统计，必须与站点字符串**完全一致**才能命中。
 */
private val CAREER_FILTERS = listOf(
    "教授",
    "副教授",
    "研究员（自然科学）",
    "副研究员（自然科学）",
    "讲师（高校）",
    "主任医师",
    "副主任医师",
)

// ───────────────────────── 状态 ─────────────────────────

data class FacultySearchUiState(
    /** 首屏加载（还没有任何结果时） */
    val loading: Boolean = false,
    /** 加载下一页 */
    val loadingMore: Boolean = false,
    /** 首屏错误（有结果时刷新失败不进这里） */
    val error: String? = null,
    /** 加载更多失败 */
    val moreError: String? = null,

    val keyword: String = "",
    val collegeId: Int? = null,
    val career: String? = null,

    val colleges: List<FacultyCollege> = emptyList(),
    val collegesLoading: Boolean = false,
    val collegesError: String? = null,

    val items: List<TeacherSummary> = emptyList(),
    val total: Int = -1,
    val pageIndex: Int = 0,
    val hasMore: Boolean = false,
    /** 是否已经发过至少一次查询（区分「还没查」和「查了没结果」） */
    val searched: Boolean = false,
) {
    val selectedCollegeName: String
        get() = colleges.firstOrNull { it.collegeId == collegeId }?.name.orEmpty()

    val hasFilter: Boolean get() = collegeId != null || career != null || keyword.isNotBlank()

    val isFiltering: Boolean get() = collegeId != null || career != null
}

class FacultySearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(FacultySearchUiState())
    val state: StateFlow<FacultySearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var moreJob: Job? = null
    private var collegesLoaded = false

    /** 页面首次进入：拉学院 + 跑一次默认查询 */
    fun attach() {
        if (collegesLoaded) return
        collegesLoaded = true
        loadColleges()
        reload()
    }

    fun loadColleges() {
        if (_state.value.collegesLoading) return
        _state.value = _state.value.copy(collegesLoading = true, collegesError = null)
        viewModelScope.launch {
            runCatching { FacultyApi.colleges() }
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        collegesLoading = false,
                        colleges = list,
                        collegesError = null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        collegesLoading = false,
                        collegesError = e.message ?: "学院列表加载失败",
                    )
                }
        }
    }

    /** 关键词变化：由输入框实时驱动，防抖 350ms 后查询 */
    fun onKeywordChange(value: String) {
        _state.value = _state.value.copy(keyword = value)
        scheduleSearch()
    }

    fun onCollegeSelect(id: Int?) {
        if (_state.value.collegeId == id) return
        _state.value = _state.value.copy(collegeId = id)
        reload()
    }

    /** 点同一个 Chip 取消筛选 */
    fun onCareerToggle(career: String) {
        val cur = _state.value.career
        _state.value = _state.value.copy(career = if (cur == career) null else career)
        reload()
    }

    fun clearFilters() {
        _state.value = _state.value.copy(collegeId = null, career = null, keyword = "")
        reload()
    }

    /** 提交搜索（键盘「搜索」键）：立即查，不等防抖 */
    fun submit() {
        searchJob?.cancel()
        reload()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            reload()
        }
    }

    /** 从头查第 1 页 */
    fun reload() {
        moreJob?.cancel()
        searchJob?.cancel()
        val s = _state.value
        _state.value = s.copy(
            loading = s.items.isEmpty(),
            loadingMore = false,
            error = null,
            moreError = null,
        )
        launchPage(pageIndex = 1, append = false)
    }

    /** 加载下一页 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        _state.value = s.copy(loadingMore = true, moreError = null)
        moreJob = launchPage(pageIndex = s.pageIndex + 1, append = true)
    }

    fun retryMore() {
        val s = _state.value
        if (s.loadingMore) return
        _state.value = s.copy(loadingMore = true, moreError = null)
        moreJob = launchPage(pageIndex = s.pageIndex + 1, append = true)
    }

    private fun launchPage(pageIndex: Int, append: Boolean): Job = viewModelScope.launch {
        val s = _state.value
        runCatching {
            FacultyApi.search(
                keyword = s.keyword,
                collegeId = s.collegeId,
                career = s.career,
                pageIndex = pageIndex,
            )
        }
            .onSuccess { page ->
                val cur = _state.value
                val merged = if (append) {
                    // 分页数据可能因服务端排序抖动出现重复，按 teacherId 去重
                    (cur.items + page.items).distinctBy { it.teacherId }
                } else {
                    page.items
                }
                _state.value = cur.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    moreError = null,
                    items = merged,
                    total = page.total,
                    pageIndex = page.pageIndex,
                    hasMore = page.hasMore,
                    searched = true,
                )
            }
            .onFailure { e ->
                val cur = _state.value
                val msg = e.message ?: "查询失败，请稍后重试"
                _state.value = if (append) {
                    cur.copy(loadingMore = false, moreError = msg)
                } else {
                    cur.copy(
                        loading = false,
                        loadingMore = false,
                        error = msg,
                        searched = true,
                    )
                }
            }
    }
}

// ───────────────────────── 页面 ─────────────────────────

@Composable
fun FacultySearchRoute(
    onBack: () -> Unit,
    onOpenTeacher: (TeacherSummary) -> Unit,
    viewModel: FacultySearchViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.attach() }
    val state by viewModel.state.collectAsState()

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("师资查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FacultySearchBar(
                keyword = state.keyword,
                onKeywordChange = viewModel::onKeywordChange,
                onSearch = {
                    keyboard?.hide()
                    viewModel.submit()
                },
                onClear = { viewModel.onKeywordChange("") },
            )

            FacultyFilterBar(
                state = state,
                onCollegeSelect = viewModel::onCollegeSelect,
                onCareerToggle = viewModel::onCareerToggle,
                onClearFilters = viewModel::clearFilters,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )

            when {
                state.loading -> LoadingState(
                    text = "正在查询教师…",
                    modifier = Modifier.weight(1f),
                )

                state.error != null && state.items.isEmpty() -> ErrorState(
                    title = "查询失败",
                    message = state.error.orEmpty(),
                    onAction = { viewModel.reload() },
                    modifier = Modifier.weight(1f),
                )

                state.items.isEmpty() && state.searched -> EmptyState(
                    title = "没有找到匹配的教师",
                    message = buildString {
                        append("换个姓名关键词试试")
                        val college = state.selectedCollegeName
                        if (college.isNotEmpty()) append("，或清除「").append(college).append("」筛选")
                    },
                    modifier = Modifier.weight(1f),
                )

                else -> TeacherResultList(
                    state = state,
                    listState = listState,
                    onOpenTeacher = onOpenTeacher,
                    onLoadMore = viewModel::loadMore,
                    onRetryMore = viewModel::retryMore,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 列表滚到底自动翻页：用 LaunchedEffect 只在进入末尾附近时触发一次
        if (state.items.isNotEmpty()) {
            val shouldLoadMore by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
                }
            }
            LaunchedEffect(shouldLoadMore, state.hasMore, state.loadingMore) {
                if (shouldLoadMore && state.hasMore && !state.loadingMore) {
                    viewModel.loadMore()
                }
            }
        }
    }
}

/** 顶部搜索框 */
@Composable
private fun FacultySearchBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding, vertical = Dimens.md),
        placeholder = { Text("输入教师姓名，如：陈") },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (keyword.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** 学院下拉 + 职称 Chip 行 */
@Composable
private fun FacultyFilterBar(
    state: FacultySearchUiState,
    onCollegeSelect: (Int?) -> Unit,
    onCareerToggle: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    var collegeMenuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.screenPadding),
    ) {
        // —— 第一行：学院下拉 + 结果计数 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FilterChip(
                    selected = state.collegeId != null,
                    onClick = {
                        if (state.colleges.isEmpty() && state.collegesError != null) {
                            // 学院没拉到，点一下重试
                            collegeMenuOpen = false
                        } else {
                            collegeMenuOpen = true
                        }
                    },
                    label = {
                        Text(
                            text = state.selectedCollegeName.ifBlank { "全部学院" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        if (state.collegesLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.iconSm),
                            )
                        }
                    },
                )

                DropdownMenu(
                    expanded = collegeMenuOpen,
                    onDismissRequest = { collegeMenuOpen = false },
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    CollegeMenuContent(
                        state = state,
                        onCollegeSelect = {
                            collegeMenuOpen = false
                            onCollegeSelect(it)
                        },
                    )
                }
            }

            Spacer(Modifier.width(Dimens.sm))

            Text(
                text = when {
                    state.total >= 0 -> "共 ${state.total} 位教师"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            if (state.hasFilter) {
                TextButton(onClick = onClearFilters) {
                    Text("清除筛选", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // —— 第二行：职称 Chip ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = Dimens.sm),
            horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            CAREER_FILTERS.forEach { career ->
                FilterChip(
                    selected = state.career == career,
                    onClick = { onCareerToggle(career) },
                    label = {
                        Text(
                            text = career,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

/** 学院下拉内容：加载态 / 错误重试 / 列表 */
@Composable
private fun CollegeMenuContent(
    state: FacultySearchUiState,
    onCollegeSelect: (Int?) -> Unit,
) {
    when {
        state.collegesLoading && state.colleges.isEmpty() -> {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(Dimens.md))
                        Text("正在加载学院…")
                    }
                },
                onClick = {},
            )
        }

        state.collegesError != null && state.colleges.isEmpty() -> {
            Column(Modifier.padding(Dimens.md).width(260.dp)) {
                Text(
                    text = "学院列表加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = state.collegesError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    text = "点此重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCollegeSelect(null) }
                        .padding(vertical = Dimens.sm, horizontal = Dimens.xs),
                )
            }
        }

        else -> {
            DropdownMenuItem(
                text = { Text("全部学院") },
                onClick = { onCollegeSelect(null) },
                trailingIcon = {
                    if (state.collegeId == null) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            state.colleges.forEach { college ->
                DropdownMenuItem(
                    text = {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = college.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (college.count > 0) {
                                Spacer(Modifier.width(Dimens.sm))
                                Text(
                                    text = college.count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { onCollegeSelect(college.collegeId) },
                    trailingIcon = {
                        if (state.collegeId == college.collegeId) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** 结果列表 */
@Composable
private fun TeacherResultList(
    state: FacultySearchUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenTeacher: (TeacherSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(
            start = Dimens.sm,
            end = Dimens.sm,
            top = Dimens.xs,
            bottom = Dimens.xxl,
        ),
    ) {
        itemsIndexed(state.items, key = { index, t -> index.toString() + "-" + t.teacherId }) { index, teacher ->
            TeacherRow(teacher = teacher, onClick = { onOpenTeacher(teacher) })
            if (index < state.items.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Dimens.md),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                )
            }
        }

        // —— 底部：加载更多 / 错误 / 到底提示 ——
        if (state.loadingMore) {
            item(key = "loading_more") {
                Row(
                    Modifier.fillMaxWidth().padding(Dimens.lg),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(Dimens.md))
                    Text(
                        text = "正在加载更多…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (state.moreError != null) {
            item(key = "more_error") {
                Column(
                    Modifier.fillMaxWidth().padding(Dimens.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.moreError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(Dimens.sm))
                    FilledTonalButton(onClick = onRetryMore) { Text("重试") }
                }
            }
        } else if (!state.hasMore && state.items.size > 0) {
            item(key = "end") {
                Row(
                    Modifier.fillMaxWidth().padding(Dimens.lg),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "已显示全部 ${state.items.size} 位教师",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
