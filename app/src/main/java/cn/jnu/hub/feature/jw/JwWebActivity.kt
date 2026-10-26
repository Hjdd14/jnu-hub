@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.feature.jw

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.ui.theme.JnuHubTheme

/**
 * 教务系统内嵌页（我的课表 / 成绩 / 考试 / 学分 / 个人信息）。
 *
 * ## 为什么用 WebView 而不是原生解析
 *
 * 教务的 jwapp 子应用对「非浏览器请求」做二次校验：
 * 同样的会话 Cookie、同样的 URL，OkHttp 请求恒 403，而浏览器里点开一切正常。
 * 我们试过补 Referer 的 gid_、补所有 Cookie、在页面内发 fetch —— 都不稳定。
 *
 * 结论：**让教务页面在真正的浏览器环境里跑**。用户看到的是学校原生界面，
 * 功能完整（含导出、查看附件、切换学年等），也不会动不动 403。
 *
 * ## 单点登录
 *
 * 打开前把 OkHttp 侧已有的 CAS / jw Cookie 推进 WebView（[seedCookies]），
 * 用户通常无需再次输入密码；会话真过期时页面自己会跳登录页。
 */
class JwWebActivity : ComponentActivity() {

    /** 当前展示的 WebView，供返回键判断能否 goBack */
    private var currentWebView: android.webkit.WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { JwWebApps.TIMETABLE.url }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "教务系统" }

        seedCookies()

        // 返回键优先走 WebView 历史，历史空了才退出。
        // 否则用户在教务里翻了几层，一按返回直接关掉整个页面，体验很糟。
        // 返回键**直接关闭本页**，不再走 WebView 历史。
        //
        // 实测：门户的 SPA 会自己往 history 里塞记录，canGoBack() 恒为 true，
        // 于是「返回」只是在 portal 内部来回跳（还顺便重新加载一遍），用户点了像没反应。
        // 用户在教务页里想回的是 App，不是门户的上一屏，所以这里直接 finish()。
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    closeAndReturn()
                }
            },
        )

        setContent {
            JnuHubTheme {
                JwWebScreen(
                    url = url,
                    initialTitle = title,
                    onBack = {
                        finish()
                    },
                )
            }
        }
    }

    /**
     * 关闭教务页并回到 App 主界面。
     *
     * 只调 finish() 不够：若历史上叠了多个实例，会退到下面那个同样的页面，
     * 用户看到的就是「返回后又重新加载了一遍」。这里用 finishAndRemoveTask 把
     * 整个教务任务栈一次性清掉。
     */
    private fun closeAndReturn() {
        // 只 finish 当前 Activity：任务栈里下面就是 MainActivity，能正常回去。
        // 不要用 finishAndRemoveTask —— 配合独立 taskAffinity 时会把整个任务移除，
        // 系统没有可显示的界面，用户看到的就是一片纯色。
        finish()
    }

    /**
     * WebView 必须显式销毁，否则它会持有 Activity 的 Context，
     * 导致 finish() 之后 Activity 无法真正回收 —— 表现为「点返回后界面还在」。
     */
    override fun onDestroy() {
        runCatching {
            currentWebView?.let { wv ->
                wv.stopLoading()
                wv.webChromeClient = null
                wv.webViewClient = object : android.webkit.WebViewClient() {}
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            }
            currentWebView = null
        }
        Log.i(TAG, "onDestroy, webview released")
        super.onDestroy()
    }

    /** 把 OkHttp 侧的 cookie 推给 WebView，省掉一次重新登录 */
    private fun seedCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(WebView(this), true)
            val snapshot = HttpClient.cookieJar().snapshot()
            var pushed = 0
            snapshot.forEach { c ->
                val host = if (c.domain.startsWith(".")) c.domain.substring(1) else c.domain
                val value = buildString {
                    append(c.name).append('=').append(c.value)
                    if (c.path.isNotBlank()) append("; Path=").append(c.path)
                }
                listOf("https", "http").forEach { scheme ->
                    runCatching {
                        cm.setCookie("$scheme://$host/", value)
                        pushed++
                    }
                }
            }
            cm.flush()
            Log.i(TAG, "seeded $pushed cookies into webview")
        }.onFailure { Log.w(TAG, "seed cookies failed", it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun JwWebScreen(url: String, initialTitle: String, onBack: () -> Unit) {
        var progress by remember { mutableIntStateOf(0) }
        var title by remember { mutableStateOf(initialTitle) }
        var webRef = remember { mutableStateOf<WebView?>(null) }
        var failed by remember { mutableStateOf(false) }
        var reloadKey by remember { mutableIntStateOf(0) }
        // 门户首页才有应用入口；进到业务页后这条提示就没意义了
        var showGuide by remember { mutableStateOf(true) }
        val targetApp = remember { initialTitle }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    TopAppBar(
                        title = {
                            Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        },
                        // 不要在这里加 statusBars inset：
                        // 加了之后整个 TopAppBar 会被推到 y≈115px，正好压在系统状态栏/手势区，
                        // 点击（包括 adb tap 和真机手指）都被系统吃掉，按钮像失灵。
                        // 状态栏高度由外层 Column 的 windowInsetsPadding 统一处理。
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    android.util.Log.i("JwWebActivity", "BACK BUTTON CLICKED")
                                    onBack()
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { webRef.value?.reload() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (showGuide) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                "在下方门户里点开「" + targetApp + "」",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "教务要求应用必须从门户进入（直接访问会提示 403）。" +
                                    "点顶部的「27 个多类型服务」搜索框，找到「" + targetApp + "」点开即可。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            // 门户按视口宽度决定渲染「桌面版」还是「移动版」：
                            // 实测 screenWidth=412 时渲染移动版，主体是空白（只有顶部工具栏）；
                            // 桌面宽度（Chrome ~1000px+）才渲染「左侧可用应用 + 右侧卡片」的完整布局。
                            // 这里强制用桌面视口，让门户渲染出完整界面。
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            // 门户里混着 http 资源（登录链有多次 http/https 交替），
                            // COMPATIBILITY 会拦掉一部分，JS 初始化可能中途就断了。
                            // 放到最宽松，与普通浏览器一致。
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadsImagesAutomatically = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                            // 模拟器（MuMu/x86）上 WebView 的硬件加速渲染有 bug：
                            // DOM 完全正常（元素位置/可见性都对），但屏幕一片空白。
                            // 强制用软件渲染层绕过它。
                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                            // 教务页面按桌面版排版更完整（手机版会砍掉部分入口）
                            // UA 必须与 JwDataHarvester 用同一个：两边渲染不一致会让
                            // 「点得到的入口」和「抓得到的数据」对不上，是之前反复失败的隐患之一。
                            settings.userAgentString = cn.jnu.hub.core.network.HttpClient.USER_AGENT
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                    super.onPageStarted(v, u, f)
                                    failed = false
                                    // 门户按视口宽度切布局：412px 会渲染「移动版」，
                                    // 实测移动版主体空白（只留顶部工具栏）。注入 viewport
                                    // 把宽度固定成桌面宽度，门户才会渲染完整界面。
                                    if (u?.contains("/new/index.html") == true) {
                                        // 不要强制改 viewport：曾把页面缩成 0.34 倍，
                                        // 内容全挤在顶部 283px 的一条里，看起来就是空白。
                                    }
                                }

                                override fun onPageFinished(v: WebView?, u: String?) {
                                    super.onPageFinished(v, u)
                                    progress = 100
                                    v?.title?.takeIf { it.isNotBlank() }?.let { title = it }
                                    // 已经进入某个业务子应用（/jwapp/sys/...）就收起提示条
                                    if (u?.contains("/jwapp/sys/") == true) showGuide = false
                                    // 页面里产生的 cookie（route / JSESSIONID 会随请求刷新）回灌 OkHttp，
                                    // 让其它模块（首页聚合等）也能复用这份会话
                                    runCatching {
                                        val cm = CookieManager.getInstance()
                                        cm.flush()
                                        val map = mutableMapOf<String, String>()
                                        JnuHosts.cookieHosts.forEach { hostUrl ->
                                            val host = hostUrl.substringAfter("://").substringBefore('/')
                                            cm.getCookie(hostUrl)?.takeIf { it.isNotBlank() }
                                                ?.let { map[host] = it }
                                        }
                                        if (map.isNotEmpty()) {
                                            HttpClient.cookieJar().syncFromWebView(map)
                                        }
                                    }
                                    Log.d(TAG, "page finished " + u + " title=" + v?.title)
                                    // 诊断：门户主体空白，查清楚是渲染没完成还是 DOM 没生成
                                    if (u?.contains("/new/index.html") == true) {
                                        // 延迟到 JS 初始化稳定后再 dump，看真实 DOM
                                        v?.postDelayed({
                                            v.evaluateJavascript(DIAG_JS) { d ->
                                                Log.i(TAG, "portal diag = " + d.orEmpty().take(900))
                                            }
                                        }, 6000L)
                                        // 检查 jQuery / AMP 框架是否初始化完成
                                        v?.postDelayed({
                                            v.evaluateJavascript(FRAMEWORK_JS) { d ->
                                                Log.i(TAG, "framework diag = " + d.orEmpty().take(700))
                                            }
                                        }, 7000L)
                                    }
                                }

                                override fun onReceivedError(
                                    v: WebView?,
                                    req: WebResourceRequest?,
                                    err: android.webkit.WebResourceError?,
                                ) {
                                    if (req?.isForMainFrame != true) return
                                    failed = true
                                    Log.w(TAG, "main frame error " + err?.errorCode + " " + req.url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    req: WebResourceRequest?,
                                ): Boolean {
                                    val u = req?.url?.toString() ?: return false
                                    // 非 http（如 weixin://）交给系统处理
                                    if (!u.startsWith("http")) {
                                        runCatching {
                                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                        }
                                        return true
                                    }
                                    return false
                                }
                            }

                            webChromeClient = object : android.webkit.WebChromeClient() {
                                // 捕获页面 JS 报错：门户卡在「请耐心等待」不走，
                                // 最可能就是初始化脚本在这里抛了异常
                                override fun onConsoleMessage(
                                    msg: android.webkit.ConsoleMessage?,
                                ): Boolean {
                                    val lv = msg?.messageLevel()?.name ?: "?"
                                    Log.i(TAG, "console[" + lv + "] " + msg?.message())
                                    return true
                                }
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }

                            loadUrl(url)
                            webRef.value = this
                            currentWebView = this
                        }
                    },
                    update = { view ->
                        // reloadKey 变化时重新加载（错误页点重试）
                        if (reloadKey > 0) view.loadUrl(url)
                    },
                )

                if (failed) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("页面加载失败", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "可能是网络问题或教务系统维护中。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = {
                            failed = false
                            reloadKey++
                        }) { Text("重试") }
                    }
                }
                }   // Box
            }       // Column
        }           // Scaffold content
    }               // JwWebScreen

    companion object {
        private const val TAG = "JwWebActivity"

        /**
         * 门户渲染诊断。
         *
         * 现象：顶部红色工具栏出来了，但主体（可用应用/卡片区）是空白背景。
         * 要分清是「JS 没跑完」还是「WebComponents 没渲染」。
         */
        /**
         * 把门户强行按桌面宽度渲染。
         *
         * 实测差异：Chrome 桌面（宽 ~1000px+）渲染「左侧可用应用 + 右侧卡片」；
         * App 的 WebView（412px）被判为手机，渲染出主体空白的移动版。
         * 门户读的是 window.innerWidth 与 meta viewport，所以两处都要改。
         */
        private const val FORCE_DESKTOP_JS =
            "(function(){" +
                "try{" +
                "var metas=document.querySelectorAll('meta[name=viewport]');" +
                "for(var i=0;i<metas.length;i++){" +
                "metas[i].setAttribute('content','width=1280,initial-scale=0.34');}" +
                "if(!metas.length){" +
                "var m=document.createElement('meta');m.name='viewport';" +
                "m.setAttribute('content','width=1280,initial-scale=0.34');" +
                "document.head.appendChild(m);}" +
                // 强制重排，让依赖 innerWidth 的布局重新计算
                "document.documentElement.style.width='1280px';" +
                "document.body && (document.body.style.width='1280px');" +
                "window.dispatchEvent(new Event('resize'));" +
                "return 'w='+window.innerWidth;" +
                "}catch(e){return 'ERR '+e;}" +
                "})()"

        /** 检查门户依赖的前端框架/全局变量是否就绪 */
        private val FRAMEWORK_JS = buildString {
            append("(function(){try{var o={};")
            append("o.jQuery=typeof window.jQuery;")
            append("o.AmpUtils=typeof window.AmpUtils;")
            append("o.AMPConfigure=typeof window.AMPConfigure;")
            append("o.AMPConstant=typeof window.AMPConstant;")
            // 门户的初始化标记
            append("o.loading=document.querySelectorAll('.loading,.amp-loading,[class*=mask]').length;")
            append("o.hasAppItem=document.querySelectorAll('widget-app-item,app-item').length;")
            // 找「请耐心等待」所在元素
            append("var t=document.body?document.body.innerText:'';")
            append("o.waitingText=t.indexOf('耐心等待')>-1;")
            // 检查 script 标签数量与加载状态
            append("var ss=document.querySelectorAll('script[src]');")
            append("o.scripts=ss.length;")
            append("o.scriptsLoaded=0;")
            append("for(var i=0;i<ss.length;i++){if(ss[i].readyState==='loaded'||ss[i].readyState==='complete')o.scriptsLoaded++;}")
            append("return JSON.stringify(o);}catch(e){return 'ERR '+e;}})()")
        }

        private val DIAG_JS = buildString {
            append("(function(){try{var o={};")
            append("o.bodyLen=document.body?document.body.innerText.length:-1;")
            append("o.ready=document.readyState;")
            append("o.title=document.title;")
            // WebComponents 支持度
            append("o.customElements=typeof window.customElements!=='undefined';")
            append("o.shadydom=!!(window.ShadyDOM);")
            append("o.templates=!!(window.HTMLTemplateElement);")
            // 自定义元素数量
            append("var ce=0;var all=document.querySelectorAll('*');")
            append("for(var i=0;i<all.length;i++){if(all[i].tagName.indexOf('-')>0)ce++;}")
            append("o.customElCount=ce;")
            // 关键容器
            append("o.groups=document.querySelectorAll('.app-group,.app-card,.widget-app-item,[data-appid]').length;")
            append("o.iframes=document.querySelectorAll('iframe').length;")
            append("o.divCount=all.length;")
            // 页面文本前 150 字
            append("o.text=document.body?document.body.innerText.slice(0,150):'';")
            // 关键：找「我的课表」元素，看它的实际位置与可见性
            append("var hit=null;")
            append("var els=document.querySelectorAll('*');")
            append("for(var i=0;i<els.length;i++){")
            append("  var t=(els[i].textContent||'').trim();")
            append("  if(t==='我的课表'&&els[i].children.length===0){hit=els[i];break;}}")
            append("if(hit){var r=hit.getBoundingClientRect();var cs=getComputedStyle(hit);")
            append("o.hit={x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),")
            append("display:cs.display,visibility:cs.visibility,opacity:cs.opacity,")
            append("overflow:getComputedStyle(document.body).overflow,")
            append("bodyH:document.body.scrollHeight,innerW:window.innerWidth,innerH:window.innerHeight};}")
            append("else{o.hit='NOT_FOUND';}")
            append("return JSON.stringify(o);}catch(e){return 'ERR '+e;}})()")
        }
        const val EXTRA_URL = "extra_jw_url"
        const val EXTRA_TITLE = "extra_jw_title"

        /** 教务页面按桌面版渲染更完整 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun intent(activity: android.content.Context, url: String, title: String): Intent =
            Intent(activity, JwWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }
}

/**
 * 教务的 5 个应用入口。
 *
 * appName 与教务 jwapp 的子应用路径一一对应。
 * 注意：这里是**页面入口名**，不是接口地址；接口都是 `/jwapp/sys/{app}/modules/...`。
 */
enum class JwWebApps(val appName: String, val label: String) {
    TIMETABLE("wdkb", "我的课表"),
    GRADES("cjcx", "成绩查询"),
    EXAMS("studentWdksapApp", "考试安排"),
    CREDITS("xywccx", "学业完成查询"),
    PROFILE("xjxxbg", "学生个人信息"),
    ;

    /**
     * 入口地址 = 教务**门户首页**，不是业务页。
     *
     * 实测（2026-10-25）：直接用 jwapp 子应用首页地址访问，
     * 即使带着完整 Cookie（CASTGC / MOD_AMP_AUTH / JSESSIONID 全在）也恒 403；
     * 而从门户页点击「我的课表」进入就正常 —— 服务端要求业务页必须经由门户跳转
     * （那一跳会带上 gid_ 等令牌）。所以 App 只能打开门户，让用户在门户内点击。
     *
     * [appName] 仍保留：进入门户后用于高亮/提示用户该点哪个应用。
     */
    val url: String
        get() = JnuHosts.JW + "/new/index.html"

    companion object {
        fun of(label: String): JwWebApps = entries.firstOrNull { it.label == label } ?: TIMETABLE
    }
}