@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.faculty

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.faculty.FacultyApi
import cn.jnu.hub.feature.faculty.TeacherDetail
import cn.jnu.hub.feature.faculty.TeacherSummary
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "FacultyDetail"

// ───────────────────────── 状态 ─────────────────────────

data class FacultyDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: TeacherDetail? = null,
    val summary: TeacherSummary? = null,
)

/**
 * 详情页 ViewModel。
 *
 * 详情优先抓教师主页 HTML（能拿到简介、研究方向、论文等全部内容）；
 * 主页抓不到时退回搜索结果里的字段，保证页面至少能显示姓名/职称/学院/邮箱/电话。
 */
class FacultyDetailViewModel : ViewModel() {

    private val _state = MutableStateFlow(FacultyDetailUiState())
    val state: StateFlow<FacultyDetailUiState> = _state.asStateFlow()

    private var loadedKey: String? = null

    /**
     * @param summary 搜索结果带回的信息（作为兜底与即时展示）
     */
    fun load(summary: TeacherSummary?, homeUrl: String) {
        val key = summary?.teacherId ?: homeUrl
        if (loadedKey == key && _state.value.detail != null) return
        loadedKey = key

        _state.value = FacultyDetailUiState(
            loading = true,
            summary = summary,
        )

        viewModelScope.launch {
            runCatching { FacultyApi.detail(homeUrl, fallbackName = summary?.name.orEmpty()) }
                .onSuccess { detail ->
                    _state.value = FacultyDetailUiState(
                        loading = false,
                        detail = merge(detail, summary),
                        summary = summary,
                    )
                }
                .onFailure { e ->
                    Log.w(TAG, "detail load failed: ${e.message}")
                    // 有兜底信息就降级展示，不把整页变成错误页
                    val fallback = summary?.let { s ->
                        TeacherDetail(
                            name = s.name,
                            career = s.career,
                            department = s.college,
                            email = s.email,
                            phone = s.phone,
                            sourceUrl = homeUrl,
                        )
                    }
                    _state.value = FacultyDetailUiState(
                        loading = false,
                        error = e.message ?: "加载失败，请稍后重试",
                        detail = fallback,
                        summary = summary,
                    )
                }
        }
    }

    fun retry(homeUrl: String) {
        loadedKey = null
        load(_state.value.summary, homeUrl)
    }

    /** 主页数据缺失的字段用搜索结果的兜底 */
    private fun merge(d: TeacherDetail, s: TeacherSummary?): TeacherDetail {
        if (s == null) return d
        return d.copy(
            name = d.name.ifBlank { s.name },
            career = s.career.ifBlank { d.career },
            department = s.college.ifBlank { d.department },
            email = s.email.ifBlank { d.email },
            phone = s.phone.ifBlank { d.phone },
            avatarUrl = d.avatarUrl.ifBlank { s.avatarPath },
        )
    }
}

// ───────────────────────── 页面 ─────────────────────────

@Composable
fun FacultyDetailRoute(
    onBack: () -> Unit,
    homeUrl: String,
    summary: TeacherSummary? = null,
    viewModel: FacultyDetailViewModel = viewModel(),
) {
    LaunchedEffect(homeUrl) { viewModel.load(summary, homeUrl) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val displayName = state.detail?.name?.takeIf { it.isNotBlank() }
        ?: summary?.name
        ?: "教师详情"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val url = state.detail?.sourceUrl?.takeIf { it.isNotBlank() } ?: homeUrl
                    if (url.isNotBlank()) {
                        IconButton(onClick = { openInBrowser(context, url) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "在浏览器打开主页",
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(
                text = "正在加载教师主页…",
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            // 完全没有兜底信息时才是整页错误
            state.detail == null -> ErrorState(
                title = "加载失败",
                message = state.error ?: "未获取到该教师的信息",
                onAction = { viewModel.retry(homeUrl) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> FacultyDetailContent(
                detail = state.detail!!,
                error = state.error,
                onRetry = { viewModel.retry(homeUrl) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun FacultyDetailContent(
    detail: TeacherDetail,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.lg,
            bottom = Dimens.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        // —— 头部：头像 + 姓名 + 职称 + 学院 ——
        item(key = "header") {
            TeacherHeader(detail)
        }

        // —— 联系方式 ——
        if (detail.hasContact || error == null) {
            item(key = "contact") {
                SectionCard(title = "联系方式") {
                    if (detail.email.isNotBlank()) {
                        ContactRow(
                            icon = Icons.Filled.Email,
                            label = "邮箱",
                            value = detail.email,
                            onClick = { sendEmail(context, detail.email) },
                        )
                    }
                    if (detail.phone.isNotBlank()) {
                        ContactRow(
                            icon = Icons.Filled.Call,
                            label = "电话",
                            value = detail.phone,
                            onClick = { dial(context, detail.phone) },
                        )
                    }
                    if (!detail.hasContact) {
                        Text(
                            text = "这位老师没有公开联系方式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // —— 主页抓取失败的提示（降级展示） ——
        if (error != null) {
            item(key = "degraded") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.padding(Dimens.cardPadding)) {
                        Text(
                            text = "主页详情加载失败",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(Dimens.xs))
                        Text(
                            text = buildString {
                                append(error)
                                append("\n下面只显示搜索结果里的基本信息。")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(Dimens.md))
                        FilledTonalButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        }

        // —— 结构化资料 ——
        val extras = detail.extraInfo
        if (extras.isNotEmpty()) {
            item(key = "info") {
                SectionCard(title = "个人资料") {
                    extras.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Dimens.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 64.dp),
                            )
                            Spacer(Modifier.width(Dimens.md))
                            Text(
                                text = row.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // —— 长文本分区：个人简介 / 研究方向 / 主要论文 … ——
        detail.sections.forEachIndexed { index, section ->
            item(key = "section_" + index + "_" + section.title) {
                SectionCard(title = section.title) {
                    Text(
                        text = section.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
                    )
                }
            }
        }

        // —— 底部：打开完整主页 ——
        item(key = "footer") {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (detail.sourceUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = { openInBrowser(context, detail.sourceUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                        Spacer(Modifier.width(Dimens.xs))
                        Text("在浏览器打开完整主页")
                    }
                    Spacer(Modifier.height(Dimens.sm))
                }
                Text(
                    text = "资料来自暨南大学教师主页，如有出入以官网为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 详情页头部：头像 + 姓名大标题 + 职称/学院 */
@Composable
private fun TeacherHeader(detail: TeacherDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TeacherAvatarLarge(name = detail.name, avatarUrl = detail.avatarUrl)

            Spacer(Modifier.height(Dimens.md))

            Text(
                text = detail.name.ifBlank { "未具名教师" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            if (detail.career.isNotBlank()) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = detail.career,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }

            if (detail.department.isNotBlank()) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = detail.department,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ───────────────────────── 系统跳转 ─────────────────────────

/** 发邮件：找不到邮件客户端时退化为浏览器打开 mailto */
private fun sendEmail(context: Context, email: String) {
    val uri = Uri.parse("mailto:" + Uri.encode(email))
    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no mail client for $email")
        openInBrowser(context, "mailto:" + Uri.encode(email))
    } catch (e: Exception) {
        Log.w(TAG, "send email failed", e)
    }
}

/** 拨号：用 ACTION_DIAL，页面预填号码但不自动拨出 */
private fun dial(context: Context, phone: String) {
    // 去掉括号/横线/空格，保留 + 和数字，避免系统拨号盘不识别
    val cleaned = phone.replace(Regex("[^0-9+*#]"), "")
    val target = cleaned.ifBlank { phone }
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target))))
    } catch (e: Exception) {
        Log.w(TAG, "dial failed: $phone", e)
    }
}

private fun openInBrowser(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.w(TAG, "open browser failed: $url", e)
    }
}
