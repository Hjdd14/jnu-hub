@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.jnu.hub.core.session.Service
import cn.jnu.hub.core.session.SessionManager
import cn.jnu.hub.core.session.SessionState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionDebugRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by SessionManager.sessions.collectAsState()
    var probing by remember { mutableStateOf(false) }
    var debugText by remember { mutableStateOf(readCookieDebug(context)) }

    // 进入页面自动探测一次
    LaunchedEffect(Unit) {
        if (sessions.values.all { it.state == SessionState.UNKNOWN }) {
            probing = true
            SessionManager.probeAll()
            probing = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("登录状态与会话") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            probing = true
                            scope.launch {
                                SessionManager.probeAll()
                                probing = false
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新探测")
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(Dimens.cardPadding)) {
                        Text(
                            "一次登录，多站可用",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(Dimens.xs))
                        Text(
                            "暨南大学各业务系统共用统一身份认证（ICAS）会话。" +
                                "在 App 内登录一次后，水电、Blackboard、校园网会自动放行；" +
                                "会话到期需要重新登录，App 会提前提示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "各系统状态") {
                    if (probing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Dimens.md))
                    }
                    Service.entries.forEachIndexed { i, s ->
                        val st = sessions[s]
                        SessionRow(
                            label = s.label,
                            state = st?.state ?: SessionState.UNKNOWN,
                            lastOkAt = st?.lastOkAt ?: 0L,
                        )
                        if (i < Service.entries.size - 1) {
                            HorizontalDivider(
                                Modifier.padding(vertical = Dimens.sm),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Cookie 诊断", actionText = "重新读取", onAction = {
                    debugText = readCookieDebug(context)
                }) {
                    Text(
                        debugText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionCard(title = "操作") {
                    OutlinedButton(
                        onClick = {
                            probing = true
                            scope.launch {
                                SessionManager.probeAll()
                                probing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("立即探测全部系统") }

                    Spacer(Modifier.height(Dimens.sm))
                    OutlinedButton(
                        onClick = { SessionManager.clearAll(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Dimens.xs))
                        Text("清除登录数据")
                    }
                }
            }
        }
    }
}

/** 只列出 Cookie 的「域名 + 名称」，绝不输出值，避免隐私泄露 */
private fun readCookieDebug(context: android.content.Context): String {
    val sb = StringBuilder()
    val manager = android.webkit.CookieManager.getInstance()
    manager.flush()
    sb.append("【WebView getCookie（不含 HttpOnly）】\n")
    cn.jnu.hub.core.network.JnuHosts.cookieHosts.forEach { url ->
        val raw = manager.getCookie(url)
        val host = url.substringAfter("://").substringBefore('/')
        if (raw.isNullOrBlank()) {
            sb.append(host).append(": (空)\n")
        } else {
            val names = raw.split(';').mapNotNull {
                it.trim().substringBefore('=').takeIf { n -> n.isNotBlank() }
            }
            sb.append(host).append(": ").append(names.joinToString(", ")).append('\n')
        }
    }
    sb.append("\n【OkHttp CookieJar】\n")
    val jar = cn.jnu.hub.core.network.HttpClient.cookieJar().snapshot()
    if (jar.isEmpty()) {
        sb.append("(空)")
    } else {
        jar.groupBy { it.domain }.forEach { (d, list) ->
            sb.append(d).append(": ").append(list.joinToString(", ") { it.name }).append('\n')
        }
    }
    return sb.toString()
}

@Composable
private fun SessionRow(label: String, state: SessionState, lastOkAt: Long) {
    val (color, text) = when (state) {
        SessionState.LOGGED_IN -> Color(0xFF2E7D32) to "已登录"
        SessionState.EXPIRED -> Color(0xFFBA1A1A) to "已过期"
        SessionState.ERROR -> Color(0xFFED8B00) to "探测失败"
        SessionState.UNKNOWN -> MaterialTheme.colorScheme.outline to "未探测"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Dimens.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (lastOkAt > 0) {
                Text(
                    "上次成功 " + SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(lastOkAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}