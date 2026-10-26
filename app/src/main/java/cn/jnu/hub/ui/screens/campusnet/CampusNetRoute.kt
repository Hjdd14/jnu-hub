@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.campusnet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.core.session.SessionManager
import cn.jnu.hub.feature.campusnet.CampusAccount
import cn.jnu.hub.feature.campusnet.CampusMenu
import cn.jnu.hub.feature.campusnet.CampusNetApi
import cn.jnu.hub.feature.campusnet.CampusNetException
import cn.jnu.hub.feature.campusnet.CampusNetOverview
import cn.jnu.hub.feature.campusnet.CampusNetTicketBridge
import cn.jnu.hub.ui.components.ErrorState
import cn.jnu.hub.ui.components.LoadingState
import cn.jnu.hub.ui.components.SectionCard
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CampusNet"

// ───────────────────────── 状态 ─────────────────────────

data class CampusNetUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val overview: CampusNetOverview? = null,
    val error: String? = null,
    /**
     * 需要建立 mynet 会话（拿 ticket 交换）。
     *
     * 注意语义：这是「校园网会话缺失」，**不是**「统一认证过期」。
     * mynet 走完整 CAS 票据流程，CAS 已登录也一样需要同步一次。
     */
    val needsSession: Boolean = false,
    /** 票据交换中 */
    val exchanging: Boolean = false,
    /** 同步失败原因（弹层内提示） */
    val syncError: String? = null,
)

class CampusNetViewModel : ViewModel() {

    private val _state = MutableStateFlow(CampusNetUiState())
    val state: StateFlow<CampusNetUiState> = _state.asStateFlow()

    private var loadedOnce = false

    fun attach() {
        if (!loadedOnce) load()
    }

    fun load() {
        val first = !loadedOnce
        _state.value = _state.value.copy(
            loading = first,
            refreshing = !first,
            error = null,
            needsSession = false,
        )
        viewModelScope.launch {
            try {
                val overview = CampusNetApi.loadOverview()
                loadedOnce = true
                _state.value = CampusNetUiState(
                    loading = false,
                    refreshing = false,
                    overview = overview,
                )
                // 会话已建立 —— 顺手让会话页的「校园网」变绿
                runCatching { SessionManager.probeCampusNet() }
            } catch (e: CampusNetException.SessionExpired) {
                loadedOnce = false
                _state.value = CampusNetUiState(
                    loading = false,
                    refreshing = false,
                    needsSession = true,
                    error = "校园网需要一次统一认证票据交换，点下方按钮同步即可",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = friendlyMessage(e),
                )
            }
        }
    }

    /** 内嵌 WebView 捕获到 ticket 后调用：交换 → 重新加载 */
    fun onTicketCaptured(ticket: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(exchanging = true, syncError = null)
            Log.d(TAG, "ticket captured, exchanging…")
            val ok = CampusNetApi.exchangeTicket(ticket)
            Log.d(TAG, "exchange result = $ok")
            if (!ok) {
                // 票据是一次性的，换失败只能让用户重来一次
                _state.value = _state.value.copy(
                    exchanging = false,
                    syncError = "换票失败，请重新点「同步校园网账号」再试一次",
                )
                return@launch
            }
            _state.value = _state.value.copy(exchanging = false)
            load()
        }
    }

    /** 用户在弹层里取消 */
    fun cancelSync() {
        _state.value = _state.value.copy(syncError = null)
    }

    fun dismissSyncError() {
        _state.value = _state.value.copy(syncError = null)
    }
}

private fun friendlyMessage(e: Throwable): String = when (e) {
    is CampusNetException.SessionExpired -> "校园网会话未建立"
    is CampusNetException -> e.message ?: "加载失败"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
}

// ───────────────────────── 页面 ─────────────────────────

/**
 * 校园网主页。
 *
 * @param onBack 返回
 * @param onLogin 统一认证登录（仅在确定 CAS 真的失效时才用得到）
 */
@Composable
fun CampusNetRoute(
    onBack: () -> Unit,
    onLogin: () -> Unit = {},
    viewModel: CampusNetViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showSyncDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.attach() }

    // 弹层关闭后若拿到了票据，ViewMode 已经在处理，这里只负责收尾

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("校园网") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.load() },
                        enabled = !state.loading && !state.refreshing,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState("正在连接校园网…")

                state.needsSession -> NeedsSessionScreen(
                    message = state.error.orEmpty(),
                    onSync = {
                        CampusNetTicketBridge.clear()
                        showSyncDialog = true
                    },
                    onLogin = onLogin,
                )

                state.error != null && state.overview == null -> ErrorState(
                    title = "校园网加载失败",
                    message = state.error.orEmpty(),
                    actionText = "重试",
                    onAction = { viewModel.load() },
                )

                state.overview != null -> CampusNetContent(
                    overview = state.overview!!,
                    refreshing = state.refreshing,
                    onOpenPage = { url, title -> openCampusPage(context, url, title) },
                    onResync = {
                        CampusNetTicketBridge.clear()
                        showSyncDialog = true
                    },
                )

                else -> ErrorState(
                    title = "暂无数据",
                    message = "点重试重新加载校园网信息",
                    onAction = { viewModel.load() },
                )
            }

            if (state.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (showSyncDialog) {
        CampusNetLoginDialog(
            onTicket = { ticket ->
                showSyncDialog = false
                viewModel.onTicketCaptured(ticket)
            },
            onDismiss = {
                showSyncDialog = false
                viewModel.cancelSync()
            },
        )
    }

    state.syncError?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSyncError() },
            title = { Text("同步失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSyncError() }) { Text("知道了") }
            },
        )
    }
}

// ───────────────────────── 内嵌 CAS 换票弹层 ─────────────────────────

/**
 * 内嵌 WebView：打开 mynet 登录入口，捕获 CAS 回跳的 ?ticket=ST-xxx。
 *
 * 为什么用 Dialog 而不是 WebPageActivity：
 * - WebPageActivity 的票据捕获硬编码写入 UtilityTicketBridge，
 *   而水电页 ON_RESUME 时会 consume 掉票据，两个模块会互相抢。
 * - 自建 Activity 又要改 AndroidManifest（不在本模块职权内）。
 * 所以这里用 Compose 的 Dialog + AndroidView(WebView) 自包含实现，
 * 捕获到票据就关闭弹层，把票交给调用方换 JSESSIONID。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CampusNetLoginDialog(
    onTicket: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var captured by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // —— 顶栏 ——
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.sm, vertical = Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "同步校园网账号",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "登录后会自动完成同步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (progress in 1..99 && !captured) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }

                if (captured) {
                    Row(
                        Modifier.fillMaxWidth().padding(Dimens.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Dimens.sm))
                        Text(
                            "已获取统一认证票据，正在同步校园网账号…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.userAgentString = HttpClient.USER_AGENT
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            // 把 OkHttp 侧已有的 CAS 会话推进 WebView，
                            // 用户登录过统一认证时这里可免二次登录
                            runCatching {
                                HttpClient.cookieJar().snapshot()
                                    .filter { it.domain.endsWith("jnu.edu.cn") }
                                    .forEach { c ->
                                        cm.setCookie(
                                            "https://${c.domain}/",
                                            "${c.name}=${c.value}; Path=/",
                                        )
                                    }
                                cm.flush()
                            }

                            fun tryCapture(url: String?) {
                                if (captured) return
                                val ticket = CampusNetTicketBridge.extract(url) ?: return
                                captured = true
                                Log.d(TAG, "captured ticket from webview: $ticket")
                                // 回灌 Cookie（mynet JSESSIONID 可能已由 WebView 拿到）
                                syncWebViewCookies(url)
                                onTicket(ticket)
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    tryCapture(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 100
                                    syncWebViewCookies(url)
                                    tryCapture(url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    // 重定向链上每一跳都检查
                                    tryCapture(u)
                                    if (!u.startsWith("http")) {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                        }
                                        return true
                                    }
                                    return false
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(v: WebView?, p: Int) {
                                    progress = p
                                }
                            }

                            loadUrl(CampusNetApi.LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

/** 把 WebView 当前地址的 Cookie 回灌到 OkHttp（保持两边会话一致） */
private fun syncWebViewCookies(url: String?) {
    if (url.isNullOrBlank()) return
    runCatching {
        val cm = CookieManager.getInstance()
        cm.flush()
        val host = url.substringAfter("://").substringBefore('/')
        if (host.isBlank()) return
        val raw = cm.getCookie(url).orEmpty()
        if (raw.isBlank()) return
        HttpClient.cookieJar().syncFromWebView(mapOf(host to raw))
        // mynet 的 JSESSIONID 是 HttpOnly，getCookie 拿不到；
        // 但换票请求本身由 OkHttp 发出，服务端 Set-Cookie 会直接进 CookieJar，
        // 这里只是兜底把非 HttpOnly 的附属 Cookie 补齐。
    }.onFailure { Log.w(TAG, "sync cookies failed", it) }
}

// ───────────────────────── 主内容 ─────────────────────────

@Composable
private fun CampusNetContent(
    overview: CampusNetOverview,
    refreshing: Boolean,
    onOpenPage: (String, String) -> Unit,
    onResync: () -> Unit,
) {
    val entries = remember(overview.menu) {
        flattenMenus(overview.menu).map { (menu, depth) ->
            CampusEntry(
                name = menu.name,
                depth = depth,
                url = CampusNetApi.pageUrl(menu.url),
                title = menu.name,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.screenPadding,
            end = Dimens.screenPadding,
            top = Dimens.sm,
            bottom = Dimens.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.cardSpacing),
    ) {
        item { AccountCard(overview.account) }

        if (entries.isNotEmpty()) {
            item {
                SectionCard(title = "校园网服务") {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            MenuRow(
                                entry = entry,
                                onClick = {
                                    val url = entry.url ?: return@MenuRow
                                    onOpenPage(url, entry.title)
                                },
                            )
                            if (index < entries.size - 1) {
                                HorizontalDivider(
                                    Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "校园网账号") {
                Text(
                    "校园网使用完整 CAS 票据流程：先用统一认证登录换取校园网会话，之后才能查询账号。"
                        + "如果页面提示会话失效，点下面的按钮重新同步一次即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                OutlinedButton(
                    onClick = onResync,
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Dimens.xs))
                    Text("重新同步校园网账号")
                }
            }
        }
    }
}

/** 账户信息卡 */
@Composable
private fun AccountCard(account: CampusAccount) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(Dimens.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(account.userFlagLabel)
                            account.userName?.takeIf { it.isNotBlank() && it != account.realName }
                                ?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                if (account.balance != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "¥${"%.2f".format(account.balance)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "余额",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            val rows = buildList {
                account.packageName?.let { add("套餐" to it) }
                account.accountStatus?.let { add("状态" to it) }
                account.phone?.let { add("手机" to it) }
                account.email?.let { add("邮箱" to it) }
                addAll(account.fields.take(6))
            }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(Dimens.md))
                rows.forEach { (label, value) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(Dimens.sm))
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(2f),
                        )
                    }
                }
            }
        }
    }
}

/** 菜单项：相对路径已拼成绝对地址，url 为 null 表示分组标题（不可点） */
private data class CampusEntry(
    val name: String,
    val depth: Int,
    val url: String?,
    val title: String,
)

/** 菜单行（可点击） */
@Composable
private fun MenuRow(entry: CampusEntry, onClick: () -> Unit) {
    val depth = entry.depth
    val clickable = entry.url != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (depth == 0) 0.dp else Dimens.lg,
                top = Dimens.md,
                bottom = Dimens.md,
                end = Dimens.xs,
            )
            .defaultMinSize(minHeight = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (depth == 0) {
            Icon(
                menuIcon(entry.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Spacer(Modifier.width(Dimens.md))
        } else {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(Dimens.md))
        }
        Text(
            entry.name,
            style = if (depth == 0) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (clickable) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.iconSm),
            )
        }
    }
}

/** 菜单 → 分组标题 + 子项；分组节点本身不可点 */
private fun flattenMenus(menus: List<CampusMenu>): List<Pair<CampusMenu, Int>> {
    val out = mutableListOf<Pair<CampusMenu, Int>>()
    menus.forEach { m ->
        if (m.hasChildren) {
            out += m to 0
            m.children.forEach { c -> out += c to 1 }
        } else {
            out += m to 0
        }
    }
    return out
}

private fun menuIcon(name: String): ImageVector = when {
    name.contains("账号") || name.contains("用户") || name.contains("个人信息") -> Icons.Filled.Person
    name.contains("订单") -> Icons.Filled.Receipt
    name.contains("邮箱") || name.contains("邮件") -> Icons.Filled.Email
    name.contains("报修") || name.contains("故障") || name.contains("报障") -> Icons.Filled.Build
    name.contains("服务") -> Icons.Filled.Settings
    else -> Icons.Filled.Info
}

// ───────────────────────── 引导页 ─────────────────────────

/**
 * 需要同步账号时的引导页。
 *
 * 这里刻意**不**把「去登录统一认证」放在主按钮：
 * mynet 缺的是业务会话而不是 CAS 会话，去登录一圈回来依然没有 JSESSIONID，
 * 用户会陷入「点了登录回来还是过期」的死循环。
 */
@Composable
private fun NeedsSessionScreen(
    message: String,
    onSync: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.xxl))
        Icon(
            Icons.Filled.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(
            "需要同步校园网账号",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            message.ifBlank { "校园网需要一次统一认证票据交换" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Dimens.xl))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                Text(
                    "一键同步",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    "点下面的按钮会打开校园网登录页。若已登录统一认证，会自动带着票据回跳，"
                        + "App 截获票据后换取校园网会话，成功即可看到账号信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                    Text("同步校园网账号")
                }
                Spacer(Modifier.height(Dimens.sm))
                TextButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("统一认证未登录？先登录一次")
                }
            }
        }
    }
}

// ───────────────────────── 打开站点页面 ─────────────────────────

/** 用通用 WebView 容器打开校园网子页面 */
private fun openCampusPage(context: Context, url: String, title: String) {
    runCatching {
        context.startActivity(
            cn.jnu.hub.feature.web.WebPageActivity.intent(context, url, title),
        )
    }.onFailure { Log.w(TAG, "open page failed: $url", it) }
}
