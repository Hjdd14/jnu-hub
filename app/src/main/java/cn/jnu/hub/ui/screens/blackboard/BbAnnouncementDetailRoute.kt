@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.blackboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.feature.blackboard.BbAnnouncement
import cn.jnu.hub.feature.blackboard.BbApi
import cn.jnu.hub.feature.blackboard.BbAttachment
import cn.jnu.hub.feature.blackboard.BbCourse
import cn.jnu.hub.feature.blackboard.BbCourseHome
import cn.jnu.hub.feature.blackboard.toBbMessage
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 通知详情状态 */
sealed interface BbAnnouncementState {
    data object Loading : BbAnnouncementState
    data object NeedsLogin : BbAnnouncementState
    /** 已登录但没有该课程/通知的访问权限（非 JBJI 学生常见） */
    data object NoAccess : BbAnnouncementState
    data class Error(val message: String) : BbAnnouncementState
    data class Ready(val announcement: BbAnnouncement) : BbAnnouncementState
}

class BbAnnouncementDetailViewModel(
    private val courseId: String,
    private val announcementId: String,
) : ViewModel() {

    var state by mutableStateOf<BbAnnouncementState>(BbAnnouncementState.Loading)
        private set

    private var job: Job? = null

    init {
        load()
    }

    /** 同步弹层里被拒绝授权时直接切到无权限态 */
    fun markNoAccess() {
        state = BbAnnouncementState.NoAccess
    }

    fun load() {
        job?.cancel()
        state = BbAnnouncementState.Loading
        job = viewModelScope.launch {
            state = try {
                val home: BbCourseHome? = runCatching { BbApi.fetchCourseHome(courseId) }.getOrNull()
                BbAnnouncementState.Ready(
                    BbApi.fetchAnnouncementDetail(courseId, announcementId, home),
                )
            } catch (t: Throwable) {
                when {
                    t.isBbNoAccess() -> BbAnnouncementState.NoAccess
                    t.isBbSessionExpired() -> BbAnnouncementState.NeedsLogin
                    else -> BbAnnouncementState.Error(t.toBbMessage())
                }
            }
        }
    }
}

/**
 * 通知详情：标题 / 发布时间 / 发布人 + 正文 + 附件。
 *
 * 正文是 BB 富文本（`div.vtbegenerated` 里的一堆硬编码样式 span），
 * 这里转成纯文本展示 —— 比塞 WebView 轻，也不会被正文里的 color:#000000 拖进暗色主题的坑里。
 * 正文里的图片走 `/bbcswebdav/`，需要带 BB Cookie，用 Coil 手动加 Cookie 头加载。
 */
@Composable
fun BbAnnouncementDetailRoute(
    onBack: () -> Unit,
    courseId: String,
    announcementId: String,
    onOpenWeb: (url: String, title: String) -> Unit,
    // 兼容旧调用点；BB 不再跳 CAS，缺会话时统一走内嵌同步弹层
    onLogin: (() -> Unit)? = null,
    vm: BbAnnouncementDetailViewModel = viewModel(key = "bb_ann_" + announcementId) {
        BbAnnouncementDetailViewModel(courseId, announcementId)
    },
) {
    val ready = vm.state as? BbAnnouncementState.Ready
    var showSync by rememberSaveable { mutableStateOf(false) }
    BbScaffold(
        title = ready?.announcement?.title ?: "通知详情",
        onBack = onBack,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.state) {
                is BbAnnouncementState.Loading -> LoadingState("正在加载通知…")
                is BbAnnouncementState.NeedsLogin -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    BbNoticePane(
                        title = "需要同步 Blackboard 登录状态",
                        message = "Blackboard 需要单独建立一次自己的登录会话，点下面的按钮即可。",
                        icon = Icons.Filled.Sync,
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
                        OutlinedButton(onClick = onBack) { Text("返回") }
                        Spacer(Modifier.width(Dimens.md))
                        Button(onClick = { showSync = true }) { Text("同步 Blackboard") }
                    }
                }

                is BbAnnouncementState.NoAccess -> BbNoAccessState(
                    onBack = onBack,
                    onOpenBrowser = {
                        onOpenWeb(
                            BbCourse.BASE + "/webapps/blackboard/execute/courseMain?course_id=" + courseId,
                            "Blackboard",
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )

                is BbAnnouncementState.Error -> ErrorState(
                    message = s.message,
                    onAction = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )

                is BbAnnouncementState.Ready -> AnnouncementBody(s.announcement, onOpenWeb)
            }
        }
    }

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

@Composable
private fun AnnouncementBody(
    announcement: BbAnnouncement,
    onOpenWeb: (String, String) -> Unit,
) {
    val body = remember(announcement.bodyHtml) {
        htmlToPlainAnnotated(announcement.bodyHtml)
    }
    val images = remember(announcement.attachments) {
        announcement.attachments.filter { it.isImage() }
    }
    val files = remember(announcement.attachments) {
        announcement.attachments.filter { !it.isImage() }
    }
    // 正文与附件同时为空 = 发布者确实没写内容；只有其中一项为空则如实说明
    val noBodyHint = when {
        announcement.attachments.isNotEmpty() ->
            "这条通知没有正文文字，具体内容请看下面的附件。"
        else ->
            "这条通知没有正文，发布者只发布了标题。"
    }

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
        item {
            Column {
                Text(
                    text = announcement.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(Dimens.sm))
                val meta = listOfNotNull(
                    announcement.postedBy.takeIf { it.isNotBlank() }?.let { "发布：" + it },
                    announcement.postedOn.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (announcement.postedTo.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "发布到：" + announcement.postedTo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (body.isNotBlank()) {
            item {
                SectionCard(title = "正文") {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            // 正文确实为空：不装作没事，直接说明「发布者没有写正文」，
            // 并给出回原页面的出口 —— 可能是 App 没抓到的富文本形态。
            item {
                SectionCard(title = "正文") {
                    Text(
                        text = noBodyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.md))
                    OutlinedButton(
                        onClick = {
                            onOpenWeb(
                                BbCourse.BASE + "/webapps/blackboard/execute/announcement" +
                                    "?method=search&context=course&course_id=" +
                                    java.net.URLEncoder.encode(announcement.courseId, "UTF-8") +
                                    "&handle=announcements",
                                "Blackboard 通知",
                            )
                        },
                    ) { Text("在 Blackboard 中查看") }
                }
            }
        }

        if (images.isNotEmpty()) {
            item {
                SectionCard(title = "图片（" + images.size + "）") {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                        images.forEach { image ->
                            BbCookieImage(image)
                        }
                    }
                }
            }
        }

        if (files.isNotEmpty()) {
            item {
                SectionCard(title = "附件（" + files.size + "）") {
                    files.forEach { file ->
                        BbAttachmentRow(file) { url, title -> onOpenWeb(url, title) }
                    }
                    Spacer(Modifier.height(Dimens.sm))
                    Text(
                        text = "附件需要 Blackboard 登录状态，点击会用应用内浏览器打开并自动下载。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val links = announcement.links.filter { !it.url.contains("bbcswebdav") }
        if (links.isNotEmpty()) {
            item {
                SectionCard(title = "相关链接（" + links.size + "）") {
                    links.take(20).forEach { link ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWeb(link.absoluteUrl, link.text) }
                                .padding(vertical = Dimens.sm),
                        ) {
                            Text(
                                text = link.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 正文 HTML -> 纯文本（保留段落换行）。
 *
 * 不用 Html.fromHtml 直接渲染的原因：BB 正文里面是大量嵌套的 `<span style>`，
 * 在 Material 主题下会出现深色主题看不清的硬编码颜色（如 color:#000000），
 * 转成纯文本反而更稳、可读性也更好。
 */
private fun htmlToPlainAnnotated(html: String): String {
    if (html.isBlank()) return ""
    val cleaned = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
        .replace(Regex("(?i)</li\\s*>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "· ")
        .replace(Regex("(?i)<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")) { m ->
            val label = stripTags(m.groupValues[2]).trim()
            val href = m.groupValues[1]
            if (label.isBlank()) href else label + " <" + href + ">"
        }
    return stripTags(cleaned)
        .replace('\u00A0', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()
}

private fun stripTags(input: String): String =
    input.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

private fun BbAttachment.isImage(): Boolean {
    val lower = (name + " " + url).lowercase()
    return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
        lower.contains(".gif") || lower.contains(".webp") || lower.contains(".bmp")
}

/**
 * 正文内嵌图片。
 *
 * `/bbcswebdav/` 受 BB 鉴权保护，Coil 默认不带 Cookie，
 * 所以这里用 Coil 的 OkHttp 集成手动注入共享 CookieJar 里的 BB Cookie。
 */
@Composable
private fun BbCookieImage(image: BbAttachment) {
    val context = LocalContext.current
    val cookie by produceState(initialValue = "", image.absoluteUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                cn.jnu.hub.core.network.HttpClient.cookieJar()
                    .loadForRequest(image.absoluteUrl.toHttpUrl())
                    .joinToString("; ") { it.name + "=" + it.value }
            }.getOrDefault("")
        }
    }

    val model = remember(image.absoluteUrl, cookie) {
        ImageRequest.Builder(context)
            .data(image.absoluteUrl)
            .apply {
                if (cookie.isNotBlank()) {
                    addHeader("Cookie", cookie)
                    addHeader("Referer", cn.jnu.hub.feature.blackboard.BbCourse.BASE + "/")
                }
            }
            .crossfade(true)
            .build()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.md))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = model,
            contentDescription = image.name,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (image.name.isNotBlank()) {
        Spacer(Modifier.height(Dimens.xs))
        Text(
            text = image.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
