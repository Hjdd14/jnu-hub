package cn.jnu.hub.feature.jw

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 用**隐藏 WebView**发教务接口请求（Jsoup/OkHttp 之后的最后一条路）。
 *
 * ## 为什么需要它
 *
 * 实测（2026-10-22）：jw 域的 cookie 已经齐了
 * （JNU_AUTH_VERIFY_TOKEN + CASTGC + MOD_AMP_AUTH + asessionid），
 * 但从 OkHttp 发 `POST /jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do` 仍然 **403**，
 * 且 403 响应自带 `Set-Cookie` —— 服务端认为这个「客户端」不是它认可的会话。
 *
 * 而在 WebView 里，页面自己发的 fetch 是**同一套同源请求**，
 * 浏览器把该带的东西（cookie、referer、会话指纹）都带齐了，服务端不会拒。
 *
 * ## 工作方式
 *
 * 1. 创建一个不可见 WebView，先 loadUrl 到 jw 的 wdkb 子应用页，拿到同源上下文；
 * 2. onPageFinished 后 evaluateJavascript 执行一段 fetch，
 *    结果通过 [Bridge]（`@JavascriptInterface`）回传到 Kotlin；
 * 3. 拿到 JSON 文本后销毁 WebView。
 *
 * ## 线程约定
 *
 * WebView 只能在主线程创建和操作，所以整个流程跑在 [Dispatchers.Main] 上；
 * 调用方（JwApi）本来就在 IO 线程，切换开销可以忽略。
 */
object JwJsFetcher {

    private const val TAG = "JwJsFetcher"

    /** 门户加载 + 点入口跳转 + 页内 fetch 的总超时 */
    private const val TIMEOUT_MS = 25_000L

    /**
     * 同源上下文页面：**门户首页**，不是业务页。
     *
     * 浏览器 HAR 显示：用户是「登录 → 落在 new/index.html → 从门户菜单点进课表」，
     * 业务页 URL 上带着服务端生成的加密会话参数 `gid_`：
     *     /jwapp/sys/wdkb/  +  "*" + default/index.do?t_s=…&amp_sec_version_=1&gid_=<base64>&EMAP_LANG=zh
     * 直接打开裸业务页（没有 gid_）会被应用层判 403。
     *
     * 所以这里先落到门户，再从门户里拿到带 gid_ 的课表入口再跳过去。
     */
    private val BASE_PAGE: String
        get() = JwSession.WEBVIEW_ENTRY_URL

    /** 登录成功后是否已经对业务页发过一次 fetch（避免 new/index.html 重入） */
    private var fetchIssued = false

    /** 是否已经从门户页尝试过一次「点菜单进课表」 */
    private var namedToWdk = false

    /** 门户首屏菜单异步渲染，等这么久再找入口链接 */
    private const val PORTAL_SETTLE_MS = 1_500L

    /**
     * 在隐藏 WebView 里发一个同源 POST，返回响应文本。
     *
     * @param path 接口路径（以 / 开头）
     * @param body form-urlencoded 请求体，可为空
     * @return 响应文本（可能是 JSON，也可能是错误页 HTML）
     * @throws JwException.Network 超时或 WebView 不可用
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(context: Context, path: String, body: String): String = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        val webView = runCatching { WebView(app) }.getOrElse { t ->
            throw JwException.Network("无法创建 WebView：${t.message}")
        }

        val channel = kotlinx.coroutines.channels.Channel<Result<String>>(1)
        var finished = false
        fun finish(result: Result<String>) {
            if (finished) return
            finished = true
            channel.trySend(result)
        }

        // 步骤状态机：门户 → 点入口 → 落地页 → 页内 fetch
        var stage = Stage.PORTAL

        runCatching {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.userAgentString = cn.jnu.hub.core.network.HttpClient.USER_AGENT
            webView.settings.mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            runCatching {
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
            JwViewHost.attach(webView)

            webView.addJavascriptInterface(
                Bridge { payload -> finish(runCatching { parseBridgePayload(payload) }) },
                BRIDGE_NAME,
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    val u = url ?: return
                    // 跳转 URL 上带 gid_ 时顺手记下来（本账号专属）
                    JwSession.captureGidFromUrl(u)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url ?: return
                    Log.d(TAG, "onPageFinished " + u)
                    JwSession.captureGidFromUrl(u)

                    val htmlLen = 0 // 由下一步的 JS 报告
                    when {
                        JwSession.isCasLanding(u) -> {
                            Log.w(TAG, "landed on CAS, session expired: " + u)
                            finish(Result.failure(JwException.SessionExpired))
                        }

                        // 已经落在课表子应用页：直接在这里 fetch（同源、同会话）
                        u.contains("/jwapp/sys/") -> {
                            Log.d(TAG, "stage=FETCH url=" + u.take(120))
                            view?.evaluateJavascript(SCRIPT_DUMP_PAGE) { info ->
                                Log.d(TAG, "landed page info = " + info.orEmpty().take(300))
                            }
                            if (!JwSession.isBusinessUrl(u) || isErrorPage(u)) {
                                // 业务页本身是错误页，无法 fetch
                            }
                            view?.evaluateJavascript(buildFetchScript(path, body), null)
                        }

                        // 落在门户：点开课表入口
                        else -> {
                            if (stage == Stage.PORTAL) {
                                stage = Stage.CLICK
                                Log.d(TAG, "stage=PORTAL url=" + u.take(110))
                                clickEntry(view, htmlLen)
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    req: WebResourceRequest?,
                    err: android.webkit.WebResourceError?,
                ) {
                    if (req?.isForMainFrame != true) return
                    Log.w(TAG, "main frame error " + err?.errorCode + " " + req.url)
                    finish(Result.failure(JwException.Network("教务页面加载失败：${err?.description}")))
                }

                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    JwSession.captureGidFromUrl(req?.url?.toString())
                    return false
                }
            }

            Log.d(TAG, "step1 load portal " + JwSession.PORTAL_URL)
            webView.loadUrl(JwSession.PORTAL_URL)
        }.onFailure { t ->
            finish(Result.failure(JwException.Network(t.message ?: "WebView 初始化失败")))
        }

        val result = try {
            withTimeoutOrNull(TIMEOUT_MS) { channel.receive() }
                ?: Result.failure(JwException.Network("教务取数超时（${TIMEOUT_MS / 1000}s）"))
        } finally {
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface(BRIDGE_NAME)
                JwViewHost.detach(webView)
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            Log.d(TAG, "fetch webview destroyed for " + path)
        }

        result.fold(
            onSuccess = { payload ->
                val trimmed = payload.trimStart()
                Log.d(TAG, "step4 fetch ok len=" + payload.length + " head=" + payload.take(100))
                if (trimmed.isEmpty()) throw JwException.Parse("服务器返回了空内容")
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) throw JwException.SessionExpired
                payload
            },
            onFailure = { t ->
                throw (t as? JwException) ?: JwException.Network(t.message ?: "教务页面内请求失败")
            },
        )
    }

    /** 取数所处的阶段 */
    private enum class Stage { PORTAL, CLICK, FETCH }

    /** 业务页 URL 里带这些特征就是错误页，不用再 fetch */
    private fun isErrorPage(url: String): Boolean =
        url.contains("error", ignoreCase = true)

    /**
     * 在门户页里找到课表入口并触发跳转。
     *
     * 依次尝试「我的课表」文本 → 「N 个多类型服务」搜索按钮；
     * 跳转后的 URL 会带 gid_，由 onPageStarted 抓取，随后进入 fetch 阶段。
     */
    private fun clickEntry(view: WebView?, htmlLen: Int) {
        // 门户是 SPA：onPageFinished 时 body 还只有「请耐心等待」，脚本尚未初始化。
        // 直接点「跳过」只会删 DOM 节点、不触发真正关闭。所以先等页面稳定，再点。
        val skipThenClick = object : Runnable {
            var attempt = 0
            override fun run() {
                attempt++
                view?.evaluateJavascript(SCRIPT_FIND_PAGE_INFO) { info ->
                    Log.d(TAG, "step2 stable check #" + attempt + " " + info.orEmpty().trim('"'))
                    view.evaluateJavascript(SCRIPT_SKIP_GUIDE) { res ->
                        val r = res.orEmpty().trim('"')
                        Log.d(TAG, "step2 skip guide #" + attempt + " = " + r)
                        if (r.startsWith("CLICKED:")) {
                            // 点掉引导后要**重新等页面渲染**（引导关闭会触发一次整页重绘），
                            // 直接点会点到旧的遮罩层，什么都点不中。
                            val clickAfterRender = object : Runnable {
                                var retry = 0
                                override fun run() {
                                    retry++
                                    view.evaluateJavascript(SCRIPT_FIND_PAGE_INFO) { fi ->
                                        val len = Regex("len=(-?\\d+)").find(fi.orEmpty())
                                            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                                        Log.d(TAG, "step3 after skip #" + retry + " bodyLen=" + len)
                                        if (len > 300) {
                                            // 先把页面真实内容打出来，确认遮罩是否真的关掉了
                                            view.evaluateJavascript(SCRIPT_DIAG_DOM) { d ->
                                                Log.d(TAG, "step3 dom after skip=" + d.orEmpty().take(600))
                                            }
                                            // 页面已有真实内容，可以找应用入口了
                                            view.evaluateJavascript(SCRIPT_CLICK_ENTRY) { res2 ->
                                                Log.d(
                                                    TAG,
                                                    "step3 click entry = " +
                                                        res2.orEmpty().trim('"'),
                                                )
                                            }
                                        } else if (retry < 10) {
                                            view.postDelayed(this, 1200L)
                                        }
                                    }
                                }
                            }
                            view.postDelayed(clickAfterRender, 2500L)
                        } else if (attempt < 8) {
                            view.postDelayed(this, 1500L)
                        }
                    }
                }
            }
        }
        view?.postDelayed(skipThenClick, 1500L)
        view?.evaluateJavascript(SCRIPT_FIND_PAGE_INFO) { info ->
            Log.d(TAG, "step2 portal info=" + info.orEmpty().take(240))
        }
        // 诊断：门户内容可能在 iframe / shadow DOM 里，这里一次性把所有线索打出来
        view?.evaluateJavascript(SCRIPT_DIAG_DOM) { d ->
            Log.d(TAG, "step2 dom diag=" + d.orEmpty().take(700))
        }
        view?.evaluateJavascript(SCRIPT_CLICK_ENTRY) { res ->
            Log.d(TAG, "step2 click result=" + res.orEmpty().trim('"') + " (htmlLen=" + htmlLen + ")")
        }
    }

    /** 一次性 DOM 诊断：iframe / shadowRoot / 可点击元素 / 页面文本 */
    private val SCRIPT_DIAG_DOM = buildString {
        append("(function(){try{")
        append("var r={title:document.title,")
        append("bodyLen:(document.body?document.body.innerText.length:-1),")
        append("htmlLen:(document.documentElement?document.documentElement.outerHTML.length:-1),")
        append("aList:(function(){var o=[];var a=document.querySelectorAll('a');for(var i=0;i<a.length;i++){o.push((a[i].textContent||'').trim().slice(0,20)+'@'+(a[i].href||'').slice(0,50));}return o;})(),")
        append("divN:document.querySelectorAll('div').length,")
        append("iframes:[],text:document.body?document.body.innerText.slice(0,100):''};")
        append("var f=document.querySelectorAll('iframe');")
        append("for(var i=0;i<f.length;i++){var t=-1;")
        append("try{t=f[i].contentDocument?f[i].contentDocument.body.innerText.length:-2}catch(e){t=-3}")
        append("r.iframes.push({s:(f[i].src||f[i].id||'').slice(0,80),n:t});}")
        append("return JSON.stringify(r);}catch(e){return 'ERR '+e;}})()")
    }

    /**
     * 首次登录时教务会弹「了解新平台 / 跳过 / 刷新页面」的欢迎引导遮罩。
     * 不点掉它，门户的真实内容（应用卡片、多类型服务按钮）不会渲染，实测 bodyLen 恒为 30。
     */
    private val SCRIPT_SKIP_GUIDE =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var els=document.querySelectorAll('a,button,span,div');" +
            "var want=['跳过','跳过引导','我知道了'];" +
            "for(var w=0;w<want.length;w++){" +
            "  for(var i=0;i<els.length;i++){" +
            "    var t=q(els[i].textContent);" +
            "    if(t===want[w]&&els[i].children.length===0){" +
            "      try{els[i].click();}catch(e){}" +
            "      try{els[i].dispatchEvent(new MouseEvent('click',{bubbles:true}));}catch(e){}" +
            "      return 'CLICKED:'+t;" +
            "    }" +
            "  }" +
            "}" +
            "return 'NO_GUIDE';}catch(e){return 'ERR '+e;}})()"
    /** 页面基本信息（排障：确认门户真的渲染出来了） */
    private val SCRIPT_DUMP_PAGE =
        "(function(){try{return 'len='+(document.body?document.body.innerText.length:-1)" +
            "+' html='+(document.documentElement?document.documentElement.outerHTML.length:-1)" +
            "+' title='+document.title;}catch(e){return 'ERR '+e;}})()"

    /** 门户页信息 */
    private val SCRIPT_FIND_PAGE_INFO = SCRIPT_DUMP_PAGE

    /**
     * 在门户页里触发「点开我的课表」。
     *
     * 优先找「最近使用」里的「我的课表」；没有就先点「N 个多类型服务」展开列表。
     * 只做一次点击，避免反复开关列表。
     */
    private val SCRIPT_CLICK_ENTRY =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            // 1) 先找「我的课表」文本节点（门户应用卡片是 div，不是 a）
            "var all=document.querySelectorAll('div,li,span,a,p');" +
            "for(var i=0;i<all.length;i++){" +
            "  var t=q(all[i].textContent);" +
            "  if(t==='我的课表'||t==='学生课程表'){" +
            "    var n=all[i];" +
            // 向上找到可点击的卡片容器，并优先取带 href 的祖先
            "    var p=n;" +
            "    for(var up=0;up<6;up++){" +
            "      if(!p) break;" +
            "      if(p.tagName==='A'&&p.getAttribute('href')&&p.getAttribute('href').indexOf('javascript')<0){" +
            "        return 'NAV:'+p.href.substring(0,90);" +
            "      }" +
            "      try{p.click();}catch(e){}" +
            "      try{p.dispatchEvent(new MouseEvent('click',{bubbles:true}));}catch(e){}" +
            "      p=p.parentElement;" +
            "    }" +
            "    return 'CLICKED_APP';" +
            "  }" +
            "}" +
            // 2) 没有课表就直接点任意应用卡片，进去后再跳转
            "var fav=document.querySelectorAll('div');" +
            "for(var j=0;j<fav.length;j++){" +
            "  var tt=q(fav[j].textContent);" +
            "  if(tt.length>2&&tt.length<20&&fav[j].children.length<=3){" +
            "    var cls=(fav[j].className||'')+'';" +
            "    if(cls.indexOf('card')>-1||cls.indexOf('app')>-1||cls.indexOf('item')>-1){" +
            "      try{fav[j].click();}catch(e){}" +
            "      return 'CLICKED_ANY:'+tt.slice(0,12);" +
            "    }" +
            "  }" +
            "}" +
            "return 'NOT_FOUND';}catch(e){return 'ERR '+e;}})()"

    /** 生成同源 fetch 脚本；失败时也把原因回传，方便排障 */
    private fun buildFetchScript(path: String, body: String): String {
        val bodyJs = JSONObject.quote(body)
        val pathJs = JSONObject.quote(path)
        return """
            (function() {
              try {
                var opts = {
                  method: 'POST',
                  credentials: 'include',
                  headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Accept': 'application/json, text/javascript, */*; q=0.01'
                  },
                  body: $bodyJs
                };
                fetch($pathJs, opts)
                  .then(function(r) {
                    return r.text().then(function(t) {
                      if (!r.ok) { JwBridge.onError('HTTP ' + r.status + ' ' + t.substring(0, 300)); return; }
                      JwBridge.onResult(t);
                    });
                  })
                  .catch(function(e) { JwBridge.onError('FETCH ' + e); });
              } catch (e) {
                JwBridge.onError('THROW ' + e);
              }
            })();
        """.trimIndent()
    }

    /** Bridge 回传的负载：正常 JSON 原样返回；错误以 ERROR: 前缀标记 */
    private fun parseBridgePayload(payload: String): String {
        if (payload.startsWith(ERROR_PREFIX)) {
            val msg = payload.removePrefix(ERROR_PREFIX)
            Log.w(TAG, "js fetch failed: " + msg.take(200))
            throw JwException.Network("教务页面内请求失败：${msg.take(120)}")
        }
        return payload
    }

    /**
     * 把 WebView 里 jw 域的同名重复 cookie 收敛成一条。
     *
     * 实测（2026-10-22）WebView 发给服务端的 Cookie 头是：
     *   [route, _WEU, JNU_AUTH_VERIFY_TOKEN, JNU_AUTH_VERIFY_TOKEN,
     *    CASTGC, MOD_AMP_AUTH, asessionid, _WEU, route]
     * route / _WEU / JNU_AUTH_VERIFY_TOKEN 各出现两次，顺序还是旧值在前。
     * 网关很可能因为同名重复（或旧值优先）判定会话无效并回 403。
     *
     * 这里逐个 path 查一遍，只保留每个名字的**第一个**值写回，
     * 其余置空（Max-Age=0），让下一次请求的 Cookie 头干净。
     */
    private fun normalizeJwCookies() {
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            val paths = listOf(
                "/",
                "/jwapp",
                "/jwapp/",
                "/jwapp/sys",
                "/jwapp/sys/",
                "/jwapp/sys/wdkb",
                "/jwapp/sys/wdkb/",
                "/amp-auth-adapter",
                "/amp-auth-adapter/",
            )
            val seen = LinkedHashMap<String, String>()
            listOf("https", "http").forEach { scheme ->
                paths.forEach { path ->
                    val url = scheme + "://" + JwSession.JW_HOST + path
                    val raw = runCatching { cm.getCookie(url) }.getOrNull().orEmpty()
                    if (raw.isBlank()) return@forEach
                    raw.split(';').forEach { pair ->
                        val trimmed = pair.trim()
                        val idx = trimmed.indexOf('=')
                        if (idx <= 0) return@forEach
                        val name = trimmed.substring(0, idx).trim()
                        val value = trimmed.substring(idx + 1).trim()
                        val exists = seen[name]
                        if (exists == null) {
                            seen[name] = value
                        } else if (exists != value && !value.contains("%")) {
                            // 同名不同值：把这条路径上的旧值删掉，只留第一次见到的
                            runCatching { cm.setCookie(url, "$name=; Max-Age=0; Path=$path") }
                            Log.d(TAG, "dropped duplicate cookie " + name + " at " + url)
                        }
                    }
                }
            }
            cm.flush()
            Log.d(TAG, "normalized jw cookies to " + seen.keys)
        }.onFailure { Log.w(TAG, "normalize cookies failed", it) }
    }

    /** cookie 头只打名字和长度（值是凭证） */
    private fun brief(raw: String): String {
        val names = raw.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
        return names.size.toString() + "个/" + raw.length + "B " + names
    }

    private const val BRIDGE_NAME = "JwBridge"
    private const val ERROR_PREFIX = "ERROR:"

    /** JS ↔ Kotlin 桥；只暴露两个回调，避免注入面扩大 */
    private class Bridge(private val onPayload: (String) -> Unit) {
        @JavascriptInterface
        fun onResult(json: String) = onPayload(json)

        @JavascriptInterface
        fun onError(message: String) = onPayload(ERROR_PREFIX + message)
    }
}