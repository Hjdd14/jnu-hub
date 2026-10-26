@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui.screens.stuit

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.School
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
import cn.jnu.hub.feature.stuit.StuitSession
import cn.jnu.hub.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "StuitSync"

/**
 * 同步弹层的最长等待时间。
 * 超过这个时间仍未落到业务页（或同步/探测一直没返回），就提示用户，
 * 避免界面永远停在 loading。
 */
private const val SYNC_TIMEOUT_MS = 20_000L

/**
 * 学工会话同步弹层：内嵌 WebView 打开学工首页，走完 CAS 回跳后自动收工。
 *
 * 为什么不直接开外部浏览器 / WebPageActivity：
 * - 外部浏览器完成后 Cookie 进的是浏览器自己的存储，回不到本 App 的 CookieJar；
 * - WebPageActivity 的票据捕获逻辑是为水电写的，写死到 UtilityTicketBridge，
 *   学工不需要票据，只需要「完整走一次回跳」，用自包含 Dialog 更干净。
 *
 * 与 CampusNetLoginDialog 的差别：校园网靠截获 ?ticket= 再换 JSESSIONID；
 * 学工会话就是回跳链上的 Set-Cookie，所以这里只要检测到「URL 已跳出 CAS 域」
 * 就可以关掉弹层，由 [StuitSession.completeWebViewSession] 让 OkHttp 再走一遍。
 *
 * @param onSynced 会话已在 OkHttp 侧就绪，调用方应重试加载
 * @param onDismiss 用户主动关闭
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StuitSyncDialog(
    onSynced: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }

    // ---- 超时保护：整页停留超过 SYNC_TIMEOUT_MS 仍未落到业务页，就提示用户 ----
    LaunchedEffect(Unit) {
        delay(SYNC_TIMEOUT_MS)
        if (!completed && !finished) {
            Log.w(TAG, "sync timeout after " + SYNC_TIMEOUT_MS + "ms, url not landed")
            failed = "等待登录超时（" + (SYNC_TIMEOUT_MS / 1000) + " 秒）。" +
                "如果已登录但页面没有自动返回，请点左上角返回后再重试一次。"
        }
    }

    // 已经跳出 CAS、回到学工页面：同步业务域 Cookie，再用 OkHttp 试一次接口
    LaunchedEffect(completed) {
        if (!completed) return@LaunchedEffect
        finished = true
        failed = null

        // 第 2 步 + 第 3 步整体加超时与异常兜底：
        // 任何一步抛异常都必须把 finished 复位，否则界面会永远停在 loading。
        val outcome: String? = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            try {
                val synced = StuitSession.syncBusinessCookies(StuitSession.HOME_URL)
                Log.d(TAG, "syncBusinessCookies -> " + synced + " cookies")

                if (synced == 0) {
                    // 业务域 Cookie 也读不到 —— 明确告诉用户和开发者，这条路不通
                    return@withTimeoutOrNull "没能读到学工系统登录后的凭证。" +
                        "请确认页面已经完全显示出学工首页后，再关闭重试。"
                }

                val ok = StuitSession.probeBusinessWithOkHttp()
                Log.d(TAG, "okhttp probe = " + ok)
                if (ok) null
                else "登录凭证已获取，但接口仍未通过校验。请把这个情况反馈给开发者（日志：probe=false）。"
            } catch (t: kotlinx.coroutines.CancellationException) {
                // 超时由外层 withTimeoutOrNull 负责，这里必须原样抛出，
                // 否则会被误判成「同步出错」，提示信息就误导用户了。
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "sync failed", t)
                "同步出错：" + (t.message ?: "未知错误")
            }
        }

        if (outcome == null) {
            // 成功：接口已通过校验
            onSynced()
        } else {
            // 失败或超时：复位，让用户可以点「我已登录，重试同步」
            finished = false
            completed = false
            failed = outcome
        }
    }

    Dialog(
        onDismissRequest = { if (!finished) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
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
                            "同步学工账号",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (finished) "正在同步…" else "登录后会自动完成同步",
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
                        Text(
                            "已登录，正在同步学工数据…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                                TextButton(
                                    onClick = {
                                        // 复位所有状态，重新走一遍三步流程
                                        failed = null
                                        completed = false
                                        finished = false
                                    },
                                ) { Text("我已登录，重试同步") }
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

                            // 把 OkHttp 侧已有的 CAS 会话推进 WebView，免二次登录
                            StuitSession.seedWebViewCookies()

                            /**
                             * 判定「已落到学工业务页」。
                             *
                             * 只在 onPageFinished 调用 —— 不能在 onPageStarted，
                             * 因为 stuit 首页会立刻 302 到 CAS，onPageStarted 的 URL
                             * 虽然是 stuit 域，但此刻并没有任何会话，会误判提前收工。
                             */
                            fun checkLanded(url: String?) {
                                if (completed || finished) return
                                if (!StuitSession.isStuitPage(url)) return
                                Log.d(TAG, "landed on stuit page: " + url)
                                completed = true
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 100
                                    StuitSession.harvestWebViewCookies(url)
                                    checkLanded(url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    checkLanded(u)
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

                            loadUrl(StuitSession.HOME_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

/**
 * 需要同步学工会话时的引导页。
 *
 * 刻意不把「去登录统一认证」做成主按钮：学工缺的是业务会话而不是 CAS 会话，
 * 去登录一圈回来依然没有 JSESSIONID，用户会陷入「点了登录回来还是过期」的死循环。
 */
@Composable
fun StuitNeedsSessionScreen(
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.xxl))
        Icon(
            Icons.Filled.School,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(
            "需要同步学工账号",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            "学工系统需要单独同步一次登录状态。点下面的按钮，一般几秒就能完成。",
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
                    "会打开学工首页并自动完成统一认证跳转。若统一认证仍然有效，通常无需再输入密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Dimens.md))
                Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                    Text("同步学工账号")
                }
            }
        }
    }
}