@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.blackboard

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.feature.blackboard.BbSession
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "BbSync"

/** 等待落到 BB 域的最长时间，超过就提示用户 */
private const val SYNC_TIMEOUT_MS = 25_000L

/**
 * Blackboard 会话同步弹层。
 *
 * ## 为什么不能复用 LoginActivity
 *
 * 用户点「去登录」打开的是统一认证 CAS 页，但 CAS 会话往往**本来就还在**，
 * 于是 CAS 直接 302 到登录落地页（网上服务大厅 ehall）。跑完一圈：
 * CAS 没刷新、**BB 也没有建立自己的会话**，回来还是未登录 —— 用户会以为登录坏了。
 *
 * BB 要的是「自己域的一次真实访问」，所以这里直接打开 BB 首页：
 *
 *     https://blackboard.jnu.edu.cn/
 *       -> 302 auth4（CAS 会话有效则自动放行）
 *       -> 302 回 BB，Set-Cookie: JSESSIONID / BbRouter
 *       -> 200 BB 首页
 *
 * ## 落地判定
 *
 * 只在 onPageFinished 判断（不能在 onPageStarted：BB 首页会立刻 302 到 CAS，
 * 那一刻 URL 虽是 BB 域但还没有任何会话，会误判提前收工）。
 * 另外**必须看页面内容**：非 JBJI 学生会停在 Access Denied 页，
 * 那也是「落到 BB 域」，但此时应该给「仅 JBJI 学生可用」，而不是假装同步成功。
 *
 * @param onSynced  会话已就绪，调用方应重试加载
 * @param onNoAccess 落到 BB 但被拒绝授权（非 JBJI 学生）
 * @param onDismiss 用户主动关闭
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BbSyncDialog(
    onSynced: () -> Unit,
    onNoAccess: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    var landedUrl by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }

    // —— 超时保护：一直没落到 BB 域就提示，避免界面永远停在 loading ——
    LaunchedEffect(Unit) {
        delay(SYNC_TIMEOUT_MS)
        if (!completed && !finished && failed == null) {
            Log.w(TAG, "sync timeout, url not landed")
            failed = "等待跳转超时（" + (SYNC_TIMEOUT_MS / 1000) + " 秒）。" +
                "如果页面已经显示 Blackboard 首页，请点左上角返回后重试一次。"
        }
    }

    // —— 已落到 BB 域：同步 Cookie -> OkHttp 验证 -> 关闭 ——
    LaunchedEffect(completed) {
        if (!completed) return@LaunchedEffect
        finished = true
        failed = null

        val outcome: String? = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            try {
                val count = BbSession.syncWebViewCookies(landedUrl ?: BbSession.HOME_URL)
                Log.d(TAG, "synced cookies = " + count)
                if (count == 0) {
                    return@withTimeoutOrNull "没能读到 Blackboard 的登录凭证。请等页面完全显示后，再关闭重试。"
                }
                val ok = BbSession.probeWithOkHttp()
                Log.d(TAG, "okhttp probe = " + ok)
                if (ok) null else "登录凭证已获取，但 Blackboard 仍未放行。请重试一次；若一直如此请反馈（日志：probe=false）。"
            } catch (t: kotlinx.coroutines.CancellationException) {
                // 超时由 withTimeoutOrNull 负责，这里必须原样抛，否则会被误判成同步出错
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "sync failed", t)
                "同步出错：" + (t.message ?: "未知错误")
            }
        }

        if (outcome == null) {
            onSynced()
        } else {
            finished = false
            completed = false
            failed = outcome
        }
    }

    // —— 被拒绝授权：直接交给调用方降级成「仅 JBJI 学生可用」 ——
    LaunchedEffect(denied) {
        if (!denied) return@LaunchedEffect
        finished = true
        onNoAccess()
    }

    Dialog(
        onDismissRequest = { if (!finished) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                            "同步 Blackboard",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (finished) "正在同步登录状态…" else "正在打开 Blackboard 首页",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (progress in 1..99 && !finished) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }

                if (finished) {
                    Row(
                        Modifier.fillMaxWidth().padding(Dimens.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Dimens.sm))
                        Text("已连上 Blackboard，正在同步登录状态…", style = MaterialTheme.typography.bodyMedium)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                failed?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.md, vertical = Dimens.sm),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(Dimens.md)) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(Dimens.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                                TextButton(onClick = { failed = null }) { Text("知道了") }
                                TextButton(onClick = onDismiss) { Text("关闭") }
                            }
                        }
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
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            // 把 OkHttp 侧已有的 CAS 会话推进 WebView，尽量免二次登录
                            BbSession.seedWebViewCookies()

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 100
                                    if (completed || finished) return
                                    if (!BbSession.isBbUrl(url)) return

                                    // 落到 BB 域了，但还要看内容：Access Denied 页也是 BB 域
                                    view?.evaluateJavascript(PAGE_PROBE_JS) { result ->
                                        val deniedPage = result?.contains("denied", ignoreCase = true) == true
                                        Log.d(TAG, "landed " + url + " denied=" + deniedPage)
                                        landedUrl = url
                                        if (deniedPage) {
                                            denied = true
                                        } else {
                                            completed = true
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
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

                            loadUrl(BbSession.HOME_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

/**
 * 在页面里判断这是不是「没有权限」页，返回 "denied" / "ok"。
 *
 * 与 BbApi.looksLikeAccessDenied 用同一套判据，只是搬到浏览器上下文里：
 * 只看 <title> 和 body 可见文本 —— 正常 BB 页面的 i18n 脚本里也有
 * page.bundle.addKey('accessDeniedMsg','Access Denied')，搜整页会 100% 误判。
 */
private val PAGE_PROBE_JS = """
    (function() {
        try {
            var title = (document.title || '').toLowerCase();
            var body = document.body ? (document.body.innerText || '') : '';
            var b = body.toLowerCase();
            if (title.indexOf('access denied') >= 0) return 'denied';
            if (title.indexOf('forbidden') >= 0) return 'denied';
            if (b.indexOf('access denied') >= 0) return 'denied';
            if (b.indexOf('do not have access') >= 0) return 'denied';
            if (b.indexOf('not enrolled') >= 0) return 'denied';
            if (body.indexOf('无权访问') >= 0) return 'denied';
            return 'ok';
        } catch (e) { return 'ok'; }
    })();
""".trimIndent()

/**
 * 需要同步 BB 会话时的引导页。
 *
 * 刻意**不**把按钮做成「去登录统一认证」：缺的是 BB 自己的业务会话，
 * 而不是 CAS 会话。去 CAS 跑一圈会落到网上服务大厅，回来依然没有 BB 会话，
 * 用户就会陷入「点了登录回来还是过期」的死循环（这正是本模块此前的 bug）。
 */
@Composable
fun BbNeedSyncScreen(
    onSync: () -> Unit,
    onOpenBrowser: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BbNoticePane(
        title = "需要同步 Blackboard 登录状态",
        message = "Blackboard 需要单独建立一次自己的登录会话，点下面的按钮，通常几秒就能完成。",
        detail = "会打开 Blackboard 首页；如果统一认证仍然有效，一般无需再输入密码。",
        icon = Icons.Filled.Sync,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(Dimens.md))
        }
        Button(onClick = onSync) { Text("同步 Blackboard") }
        Spacer(Modifier.width(Dimens.sm))
        TextButton(onClick = onOpenBrowser) { Text("浏览器打开") }
    }
}
