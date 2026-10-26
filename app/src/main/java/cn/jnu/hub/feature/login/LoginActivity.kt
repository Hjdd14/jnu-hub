@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.feature.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.ui.theme.Dimens
import cn.jnu.hub.ui.theme.JnuHubTheme

/**
 * 统一登录页。
 *
 * 为什么必须用 WebView：
 * ICAS 的登录页强制网易易盾滑块（页面脚本 var captcha = 1），
 * 且 NECaptchaValidate 由易盾 SDK 在浏览器内生成，无法在 App 内离线构造。
 * 因此：让真实浏览器完成登录 → 之后所有请求复用同一份 Cookie 即可。
 */
class LoginActivity : ComponentActivity() {

    private var webViewRef: WebView? = null
    private var loginConfirmed = false

    /** 登录成功后短暂展示的提示，避免用户困惑「点了登录怎么什么都没发生」 */
    private var successNotice: androidx.compose.runtime.MutableState<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JnuHubTheme {
                LoginScreen(
                    onWebViewReady = { webViewRef = it },
                    onSuccess = { finishWithResult(true) },
                    onCancel = { finishWithResult(false) },
                )
            }
        }
    }

    private fun finishWithResult(success: Boolean) {
        if (success) {
            syncCookies()
            // 先让用户看到「登录成功」，300ms 后再关闭，避免出现"点了没反应"的错觉
            successNotice?.value = true
            window.decorView.postDelayed({
                setResult(RESULT_OK)
                finish()
            }, 650L)
            return
        }
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * 把 WebView 里的 Cookie 回灌到 OkHttp 的 CookieJar。
     *
     * 两条通道，取并集：
     * 1. CookieManager.getCookie(url)：能拿到普通 Cookie，但会过滤 HttpOnly
     * 2. WebView 内 document.cookie：同样拿不到 HttpOnly
     * 因此对 CASTGC 这类 HttpOnly 票据，改为「直接让 OkHttp 走 WebView 已建立的
     * 业务会话」——即登录后由 OkHttp 跟随一次业务页请求，由服务端下发业务 Cookie。
     */
    private fun syncCookies() {
        val manager = CookieManager.getInstance()
        manager.flush()
        val map = mutableMapOf<String, String>()
        JnuHosts.cookieHosts.forEach { url ->
            val host = url.substringAfter("://").substringBefore('/')
            manager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { map[host] = it }
        }
        HttpClient.cookieJar().syncFromWebView(map)
        Log.i("LoginActivity", "synced hosts: " + map.keys.joinToString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun LoginScreen(
        onWebViewReady: (WebView) -> Unit,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        var progress by remember { mutableIntStateOf(0) }
        var title by remember { mutableStateOf("登录暨南大学统一身份认证") }
        val noticeState = remember { successNotice ?: mutableStateOf(false).also { successNotice = it } }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                if (noticeState.value) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "✓ 已登录暨大统一身份认证，正在返回…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 关键：登录前清掉所有业务域的旧 Cookie。
                        // 否则 WebView 里残留的过期 JSESSIONID 会与 CAS 新发的 ticket 冲突，
                        // 表现为 https://blackboard.jnu.edu.cn/?ticket=ST-xxx 报 ERR_TOO_MANY_REDIRECTS。
                        HttpClient.cookieJar().clearWebViewDomain(
                            listOf(
                                JnuHosts.BLACKBOARD, JnuHosts.UTILITY, JnuHosts.CAMPUS_NET,
                                JnuHosts.JW, JnuHosts.STUIT, JnuHosts.MICRO,
                            ),
                        )
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString = HttpClient.USER_AGENT
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.mediaPlaybackRequiresUserGesture = false

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 0
                                    view?.title?.takeIf { it.isNotBlank() }?.let { title = it }
                                    // 只做「弱检测」：DOM 里出现业务站首页特征时再确认
                                    view?.evaluateJavascript(DETECT_JS) { result ->
                                        if (result != null && result != "null" && result.contains("true")) {
                                            confirmLogin()
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (!url.startsWith("http")) {
                                        runCatching {
                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                        return true
                                    }
                                    return false
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(JnuHosts.loginEntry)
                            onWebViewReady(this)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }

    /**
     * 登录成功的强判据（多选一，全部依赖「真实发生的事」而非 URL 猜测）：
     * 1. WebView 里能读到 CASTGC cookie
     * 2. 当前页面已不在 CAS 域，且页面标题/内容显示已登录
     */
    private fun confirmLogin() {
        if (loginConfirmed) return
        loginConfirmed = true
        // 先把 WebView 的 Cookie 灌给 OkHttp，再用服务端响应做「真登录」判据
        syncCookies()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { probeBlackboard() }
            Log.i("LoginActivity", "server probe ehall = " + ok)
            if (ok) {
                finishWithResult(true)
            } else {
                // 服务端说没登录：说明只是页面跳转过去了但会话没建起来，继续等用户操作
                loginConfirmed = false
            }
        }
    }

    /**
     * 用 OkHttp 请求教务门户，返回是否真的是登录态。
     *
     * 为什么不查 Blackboard：BB 只对 JBJI 学生开放，其他学院用户会被判为未登录，
     * 造成「明明登录成功却一直提示需要登录」的死循环。
     * ehall 是全体师生都有权限的入口，且同属 auth4 网关，能代表 CAS 会话是否有效。
     */
    private fun probeBlackboard(): Boolean = runCatching {
        val req = okhttp3.Request.Builder()
            .url(JnuHosts.CAS_PROBE)
            .get()
            .build()
        HttpClient.get().newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            // 只判「有没有被踢回 CAS」。
            // 不再要求 code in 200..399：ehall 首页自身可能有多跳重定向或返回 3xx/403，
            // 之前那样判会让「明明登录成功」的用户卡在重定向循环里（ERR_TOO_MANY_REDIRECTS）。
            val redirectedToCas = finalUrl.contains("icas.jnu.edu.cn") ||
                finalUrl.contains("/cas/login") ||
                finalUrl.contains("/cas_auth/") ||
                finalUrl.contains("auth4.jnu.edu.cn") ||
                finalUrl.contains("auth7.jnu.edu.cn")
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            // 页面内容里不该还有登录表单
            val hasLoginForm = body.contains("loginForm") || body.contains("id=\"pwdLogin\"")
            // 关键：CookieJar 里必须真有某个业务域的会话 cookie。
            // 只看 HTTP 跳转不够 —— auth4 网关在部分场景会自动放行并 302 回业务域，
            // 让「看着 200 其实没登录」混过去，用户就陷入「点登录→秒关→回来仍未登录」的死循环。
            val sessionHosts = HttpClient.cookieJar().snapshot()
                .filter { c ->
                    val n = c.name.lowercase()
                    n == "castgc" || n == "mod_amp_auth" || n == "jsessionid" ||
                        n == "asessionid" || n == "session_id" || n == "session"
                }
            val ok = !redirectedToCas && !hasLoginForm && sessionHosts.isNotEmpty()
            android.util.Log.i(
                "LoginActivity",
                "probe: url=" + finalUrl + " code=" + resp.code +
                    " casRedirect=" + redirectedToCas + " loginForm=" + hasLoginForm +
                    " sessionCookies=" + sessionHosts.map { it.name } + " -> " + ok,
            )
            ok
        }
    }.getOrElse {
        Log.w("LoginActivity", "probe failed", it)
        false
    }

    companion object {
        const val REQUEST_CODE = 1001

        /**
         * 在页面里执行：判断当前是否已经离开 CAS 登录页。
         * 返回字符串 true/false。
         */
        val DETECT_JS = """
            (function() {
                try {
                    var href = location.href;
                    var cas = href.indexOf('icas.jnu.edu.cn') >= 0 ||
                              href.indexOf('auth4.jnu.edu.cn') >= 0 ||
                              href.indexOf('auth7.jnu.edu.cn') >= 0;
                    if (cas) return 'false';
                    var body = document.body ? document.body.innerText : '';
                    var isLoginForm = body.indexOf('立即登录') >= 0 && body.indexOf('JNUID') >= 0;
                    return isLoginForm ? 'false' : 'true';
                } catch (e) { return 'false'; }
            })();
        """.trimIndent()

    }
}