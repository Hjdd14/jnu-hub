@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.blackboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.jnu.hub.feature.blackboard.BbAnnouncement
import cn.jnu.hub.feature.blackboard.BbAttachment
import cn.jnu.hub.feature.blackboard.BbException
import cn.jnu.hub.ui.theme.Dimens

/**
 * Blackboard 三个页面共用的 Scaffold。
 * 遵守项目约定：contentWindowInsets = WindowInsets(0,0,0,0)，TopAppBar 吃状态栏。
 */
@Composable
fun BbScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null && subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Dimens.iconSm),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        }
                    }
                    actions()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                windowInsets = WindowInsets.statusBars,
            )
        },
        content = content,
    )
}

/** 「未登录」专用提示：BB 不需要换票，重新走一次登录页即可 */
@Composable
fun BbNeedLoginState(
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BbNoticePane(
        title = "需要登录统一认证",
        message = "Blackboard 使用暨大统一身份认证。登录一次后，所有系统都会复用同一份登录状态。",
        icon = Icons.Filled.LockPerson,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Button(onClick = onLogin) { Text("去登录") }
    }
}

/**
 * 「已登录但没有访问权限」专用提示。
 *
 * 这是**权限边界，不是错误**：Blackboard 只对伯明翰联合学院（JBJI）的学生开放，
 * 其他学院账号看到这一页是预期行为。所以文案要说明原因并给出浏览器兜底，
 * 而不是「加载失败 + 重试」—— 重试多少次都不会变。
 */
@Composable
fun BbNoAccessState(
    onBack: (() -> Unit)?,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BbNoticePane(
        title = "Blackboard 仅对伯明翰联合学院（JBJI）学生开放",
        message = "你的账号可能没有访问权限。",
        detail = "如果确认自己是 JBJI 学生，可以在浏览器中用同一账号登录后再试。",
        icon = Icons.Filled.LockPerson,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(Dimens.md))
        }
        Button(onClick = onOpenBrowser) { Text("在浏览器中打开") }
    }
}

/** 三种非正常态共用的版式：图标 + 标题 + 说明 + 按钮行 */
@Composable
fun BbNoticePane(
    title: String,
    message: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.xl, vertical = Dimens.xxxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(Dimens.xl),
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(Dimens.minTouchTarget + Dimens.md),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(Dimens.iconMd),
                    )
                }
            }
            Spacer(Modifier.height(Dimens.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Dimens.xl))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 圆角小标签 */
@Composable
fun BbTag(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = 3.dp),
        )
    }
}

/** 左图标 + 主标题 + 副标题 + 右箭头的通用行 */
@Composable
fun BbRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(Dimens.md),
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(Dimens.iconSm),
                    )
                }
            }
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!trailing.isNullOrBlank()) {
                Spacer(Modifier.width(Dimens.sm))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.iconSm),
                )
            }
        }
    }
}

/** 通知 / 作业列表里的一项 */
@Composable
fun BbListItem(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    preview: String? = null,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(Dimens.cardPadding),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!badge.isNullOrBlank()) {
                    Spacer(Modifier.width(Dimens.sm))
                    BbTag(text = badge, color = badgeColor)
                }
            }
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 附件行：点击在 WebView 里打开（带 BB Cookie，鉴权下载） */
@Composable
fun BbAttachmentRow(
    attachment: BbAttachment,
    onOpen: (String, String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(attachment.absoluteUrl, attachment.name) }
            .padding(vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconSm),
        )
        Spacer(Modifier.width(Dimens.sm))
        Text(
            text = attachment.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (attachment.size.isNotBlank()) {
            Spacer(Modifier.width(Dimens.sm))
            Text(
                text = attachment.size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空态（项目统一 EmptyState 的语义包装，避免各页文案不一致） */
@Composable
fun BbEmpty(title: String, message: String, modifier: Modifier = Modifier) {
    cn.jnu.hub.ui.components.EmptyState(title = title, message = message, modifier = modifier)
}

/** 是否是「需要重新登录」类异常 */
fun Throwable.isBbSessionExpired(): Boolean =
    this is BbException.SessionExpired || message.orEmpty().contains("登录状态已过期")

/**
 * 是否是「已登录但账号没权限」类异常。
 *
 * 注意不能只看异常类型：某些路径下 BB 会把拒绝页面当成普通解析失败抛出来，
 * 所以文案也兜一层。
 */
fun Throwable.isBbNoAccess(): Boolean =
    this is BbException.NoAccess ||
        message.orEmpty().contains("JBJI") ||
        message.orEmpty().contains("没有 Blackboard 访问权限")

/** 通知的元信息：作者 · 时间 */
fun BbAnnouncement.metaLine(): String =
    listOfNotNull(
        postedBy.takeIf { it.isNotBlank() }?.let { "发布：" + it },
        postedOn.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
