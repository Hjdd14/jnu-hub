package cn.jnu.hub.ui.screens.jw

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.feature.jw.JwBaseViewModel
import cn.jnu.hub.feature.jw.JwContext
import cn.jnu.hub.feature.jw.JwSession
import cn.jnu.hub.feature.web.WebPageActivity
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.delay

private const val TAG = "JwSessionGate"

/** 弹层自身的会话建立状态 */
enum class JwGatePhase {
    /** 正在轮询 cookie：显示 spinner + 计时 */
    Polling,

    /** 已出现 JSESSIONID，接口复核中 */
    Established,

    /** 超时：不报错，只提示用户去页面上完成登录 */
    Timeout,
}

/**
 * 教务会话缺失时的引导页。
 *
 * 语义上刻意区分：这不是「统一认证过期」，而是「jw 域还没换票」。
 * jw 的 CAS service 是 amp-auth-adapter/loginSuccess?sessionToken=xxx，
 * CAS 已登录也没有用，必须由 jw 自己发起一次跳转才能建立会话。
 */
@Composable
fun JwSessionRequiredState(
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) = JwSessionRequiredState(onSync = onSync, permissionDenied = false, modifier = modifier)

/**
 * 教务会话引导页。
 *
 * @param permissionDenied true 表示会话其实已经建立，是教务系统**拒绝了访问**（403）。
 *   这时再让用户「重新同步」没有意义，文案要解释真实原因，并把「在浏览器确认」作为主操作。
 */
@Composable
fun JwSessionRequiredState(
    onSync: () -> Unit,
    permissionDenied: Boolean,
    modifier: Modifier = Modifier,
    onOpenBrowser: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.xl))
        Icon(
            imageVector = Icons.Filled.School,
            contentDescription = null,
            tint = if (permissionDenied) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(
            text = if (permissionDenied) "教务系统提示无权限访问" else "需要建立教务会话",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            text = if (permissionDenied) {
                "登录态已经同步成功，但教务系统仍拒绝了本次访问。" +
                    "可能原因：账号未开通该功能，或教务系统正在维护。"
            } else {
                "教务系统要单独换一次票。点下面的按钮，在打开的页面上完成登录后" +
                    "会自动返回并刷新数据。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
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
                    text = if (permissionDenied) "建议这样处理" else "同步教务账号",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.xs))
                Text(
                    text = if (permissionDenied) {
                        "先在浏览器里打开教务系统，确认这个账号能不能进「我的课表」。" +
                            "如果浏览器也打不开，这是账号权限问题，需要在教务系统或学院处理。"
                    } else {
                        "若已登录过统一认证，会自动放行；App 会持续检测教务会话，" +
                            "一旦建立就自动关闭弹层。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                if (permissionDenied) {
                    Button(onClick = { onOpenBrowser(JwSession.BOOTSTRAP_URL) }, modifier = Modifier.fillMaxWidth()) {
                        Text("在浏览器打开")
                    }
                    Spacer(Modifier.height(Dimens.sm))
                    OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                        Text("重新同步")
                    }
                } else {
                    Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                        Text("同步教务账号")
                    }
                }
            }
        }
    }
}

/**
 * 内嵌 WebView 登录弹层。
 *
 * 成功判定**不依赖 WebViewClient 回调**：CAS 的 302 链很快，
 * 旧实现在回调里判定「落到业务域即成功」，而实测业务域首页
 * 只会下发 JNU_AUTH_VERIFY_TOKEN（守卫 cookie），于是立刻误判成功、
 * 同步了一份没有 JSESSIONID 的 cookie，接口照样被弹回 CAS。
 *
 * 现在判定权交给调用方：调用方在弹层出现的同时启动 [JwSession.pollSession]，
 * 通过 [phase] 把进度带给这里渲染。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwSyncDialog(
    phase: JwGatePhase,
    onRetry: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var lastUrl by remember { mutableStateOf(JwSession.WEBVIEW_ENTRY_URL) }
    var elapsed by remember { mutableIntStateOf(0) }
    val startedAt by remember { mutableLongStateOf(0L) }

    // 计时器：只在轮询期间走动，让用户看到「确实在工作」
    LaunchedEffect(phase) {
        if (phase != JwGatePhase.Polling) return@LaunchedEffect
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed += 1
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.sm, vertical = Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "同步教务账号",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "请在下方页面里点「27 个多类型服务」，再点任意应用（如我的课表）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 用 App 内全屏 WebView 而不是外部浏览器：
                    // 外部浏览器的 cookie 与本 App 完全隔离，打开也帮不上忙
                    TextButton(
                        onClick = { onOpenBrowser(JwSession.WEBVIEW_ENTRY_URL) },
                        enabled = phase != JwGatePhase.Established,
                    ) {
                        Text("全屏打开")
                    }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }

                SessionPollingBar(
                    elapsed = elapsed,
                    sessionReady = phase == JwGatePhase.Established,
                    onRetry = onRetry,
                )

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
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

                            // 把 OkHttp 侧 CAS 会话推进 WebView，免二次登录
                            JwSession.seedWebViewCookies()

                            /**
                             * 记录导航、抓 gid_、并把 WebView 的 jw 域 Cookie 同步给 OkHttp。
                             *
                             * 为什么每次都要同步 Cookie：教务的 route / JSESSIONID 会在每次请求时刷新，
                             * WebView 里点进子应用后拿到的是**新**会话，不同步过去 OkHttp 就是旧会话 → 403。
                             */
                            fun note(url: String?) {
                                val u = url ?: return
                                lastUrl = u
                                // 1) 抓 gid_（本账号专属，抓到一次即可复用）
                                JwSession.captureGidFromUrl(u)
                                // 2) 同步 Cookie：WebView 才是登录态的权威来源
                                JwSession.harvestWebViewCookies()
                                Log.d(
                                    TAG,
                                    "nav " + u + " | jw cookies=" + JwSession.webViewCookieNames(),
                                )
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                    super.onPageStarted(v, u, f)
                                    note(u)
                                }

                                override fun onPageFinished(v: WebView?, u: String?) {
                                    super.onPageFinished(v, u)
                                    progress = 100
                                    note(u)
                                    // 不再自动点击：门户是 SPA，注入触摸坐标不可靠（多次实测落空）。
                                    // 改为让用户在这个**可见的** WebView 里手动点两下：
                                    //   ① 点「27 个多类型服务」搜索按钮
                                    //   ② 点任意应用（如「我的课表」）
                                    // 点完后 URL 会带 gid_，note() 会自动抓下来存进 JwGidStore。
                                }

                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    req: WebResourceRequest?,
                                ): Boolean {
                                    val u = req?.url?.toString() ?: return false
                                    if (!u.startsWith("http")) {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                        }
                                        return true
                                    }
                                    // 只在业务域记录，避免把 CAS 页面的 URL 当成结果
                                    if (JwSession.isBusinessUrl(u)) note(u)
                                    return false
                                }
                            }

                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(v: WebView?, p: Int) {
                                    progress = p
                                }
                            }

                            Log.d(TAG, "loading webview entry " + JwSession.WEBVIEW_ENTRY_URL)
                            loadUrl(JwSession.WEBVIEW_ENTRY_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

/** 弹层顶部状态条：轮询中显示计时细节，超时显示「完成登录后返回」+ 重试 */
@Composable
private fun SessionPollingBar(
    elapsed: Int,
    sessionReady: Boolean,
    onRetry: () -> Unit,
) {
    val timedOut = !sessionReady && elapsed >= JwSession.POLL_TOTAL_SECONDS
    val container = if (timedOut) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (timedOut) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(modifier = Modifier.fillMaxWidth(), color = container) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.md, vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!timedOut) {
                CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp)
                Spacer(Modifier.width(Dimens.sm))
                Text(
                    text = "正在建立教务会话… " + elapsed + "s / " + JwSession.POLL_TOTAL_SECONDS + "s",
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "会话建立超时，请在页面上完成登录后返回",
                        style = MaterialTheme.typography.bodySmall,
                        color = content,
                    )
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = "登录完成后点「重试」重新检测；也可以切到其他页面再回来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = content,
                    )
                }
                Spacer(Modifier.width(Dimens.sm))
                FilledTonalButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

/**
 * 会话同步编排：五个教务页面共用。
 *
 * 1. 点「同步教务账号」→ 先静默探测（CookieJar 里还留着 JSESSIONID 就直接开）
 * 2. 静默失败 → 弹内嵌 WebView，**同时**启动 cookie 轮询（不等任何回调）
 * 3. 轮询出现 jw 域 JSESSIONID → ViewModel 用接口复核 → 关弹层并刷新数据
 * 4. 超时 → 弹层保留 WebView 让用户继续操作，状态条切成超时提示，可重试
 */
@Composable
fun rememberJwSync(viewModel: JwBaseViewModel<*>): JwSyncHandle {
    var showDialog by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(JwGatePhase.Polling) }
    val context = LocalContext.current

    // JwPrefs 依赖 Context 初始化。五个页面都会调到这里，
    // 保证「读学号」发生在任何一次 JwPrefs 访问之前。
    LaunchedEffect(context) { JwContext.install(context) }

    /** 启动一轮 cookie 轮询（幂等：正在跑就不重复起） */
    fun startPolling() {
        phase = JwGatePhase.Polling
        viewModel.syncWithWebView { ok ->
            phase = if (ok) JwGatePhase.Established else JwGatePhase.Timeout
            if (ok) showDialog = false
        }
    }

    val handle = remember(viewModel) {
        JwSyncHandle(
            request = {
                viewModel.trySilentSync { ok ->
                    if (!ok) {
                        showDialog = true
                        startPolling()
                    }
                }
            },
        )
    }

    if (showDialog) {
        JwSyncDialog(
            phase = phase,
            onRetry = { startPolling() },
            onOpenBrowser = { url ->
                showDialog = false
                runCatching {
                    context.startActivity(WebPageActivity.intent(context, url, "同步教务"))
                }
            },
            onDismiss = { showDialog = false },
        )
    }

    return handle
}

/** [rememberJwSync] 的返回值：只暴露「发起同步」一个动作 */
class JwSyncHandle internal constructor(
    val request: () -> Unit,
)