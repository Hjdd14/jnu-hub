package cn.jnu.hub.feature.jw

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 教务数据获取层：在隐藏 WebView 里「像用户一样操作」，旁路捕获页面自己发出的接口响应。
 *
 * ## 为什么不能直接用 OkHttp
 *
 * 实测（多轮确认）：jw 域 cookie 全部齐全（CASTGC / MOD_AMP_AUTH / asessionid / JSESSIONID…），
 * 网关接口 getAppConfig 也返回 200 并认得用户身份，
 * 但业务接口（dqxnxq / xskcb …）对非浏览器请求一律 **403**。
 * 因此改为「让页面自己去请求，我们从旁路把响应捞出来」。
 *
 * ## 流程
 *
 *   1. 隐藏 WebView 加载门户 new/index.html（不是业务页 —— 直接开业务页必然 403）
 *   2. 每次 onPageStarted 都注入 fetch / XHR hook（新页面是新的 JS 上下文，必须重注）
 *   3. onPageFinished 后：跳过「了解新平台 / 跳过」引导，等页面真正渲染（bodyLen > 300）
 *   4. 点开目标应用（多级兜底选择器）
 *   5. 页面自己发起的请求命中 [targetMarker] 时，经 Bridge 回传原始响应
 *
 * 全程 25 秒总超时；WebView 用完必定 detach + destroy。
 */
object JwDataHarvester {

    private const val TAG = "JwDataHarvester"

    /** 总超时：门户加载 + 跳引导 + 摊开面板 + 点应用 + 页面请求往返（实测 4~5s，留足余量） */
    private const val TIMEOUT_MS = 35_000L

    /** 轮询节奏 */
    private const val STEP_DELAY_MS = 600L

    private const val BRIDGE_NAME = "JwBridge"

    /**
     * 串行化：同一时刻只允许一个 harvester 在跑。
     *
     * 实测问题：一个页面的 fetch() 里连着调两次（先取学期、再取数据）时，
     * 第二个 WebView 会在第一个还没销毁完时创建，随后**永远拿不到回调**，
     * 页面就一直转圈。加锁后第二次会排队等第一次完全退出。
     */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Bridge 回传错误时用的前缀 */
    private const val ERROR_PREFIX = "ERROR:"

    /** 一次抓取任务的状态 */
    private class Session(
        val marker: String,
        /** 落到子应用页后要页面自己发起的请求路径 */
        val requestPath: String = "",
        /** 该请求的表单体 */
        val requestBody: String = "",
        /**
         * 要进入的子应用名称（门户「最近使用」里显示的文字），
         * 例如 我的课表 / 成绩查询 / 我的考试安排 / 学业完成查询 / 学生个人信息。
         *
         * 不同应用的数据只能从**它自己的页面**里取，所以这个必须按需传入。
         */
        val appLabel: String = "我的课表",
        /** 子应用名（wdkb / cjcx / studentWdksapApp / xywccx / xjxxbg），用于直接构造入口 */
        val appPath: String = "",
    ) {
        var hooked = false
        var guideDismissed = false
        var serviceTapped = false
        var searched = 0
        var appTapped = false
        var requested = false
        var directTried = false
        var waitAfterTap = 0
        var finished = false
    }

    /**
     * 抓取指定接口的原始 JSON 响应。
     *
     * @param context     任意 Context（内部取 applicationContext）
     * @param targetMarker 接口路径片段，例如 "xskcb"、"dqxnxq"
     * @return 页面自己拿到的原始响应文本（JSON）
     * @throws JwException.SessionExpired 超时或始终拿不到（上层据此降级到引导页）
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun harvest(
        context: Context,
        targetMarker: String,
        requestPath: String = "",
        requestBody: String = "",
        appLabel: String = "我的课表",
        appPath: String = "",
    ): String = lock.withLock {
        Log.d(TAG, "harvest acquire lock marker=" + targetMarker)
        val r = harvestLocked(context, targetMarker, requestPath, requestBody, appLabel, appPath)
        Log.d(TAG, "harvest release lock marker=" + targetMarker)
        r
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvestLocked(
        context: Context,
        targetMarker: String,
        requestPath: String,
        requestBody: String,
        appLabel: String,
        appPath: String,
    ): String = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        val session = Session(targetMarker, requestPath, requestBody, appLabel, appPath)
        val webView = runCatching { WebView(app) }.getOrElse { t ->
            Log.e(TAG, "harvest step0 create webview failed: " + t.message)
            throw JwException.Network("无法创建 WebView：${t.message}")
        }
        val channel = kotlinx.coroutines.channels.Channel<Result<String>>(1)
        var graceScheduled = false
        var pendingHook: String? = null
        var pendingDirect: String? = null

        fun finish(result: Result<String>) {
            if (session.finished) return
            session.finished = true
            channel.trySend(result)
        }

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
            // 隐藏 WebView 必须挂到窗口，否则 onPageFinished 可能一次都不触发
            JwViewHost.attach(webView)

            webView.addJavascriptInterface(
                Bridge(
                    sink = { payload ->
                        Log.d(TAG, "harvest step5 captured len=" + payload.length + " head=" + payload.take(80))
                        when {
                            payload.startsWith(ERROR_PREFIX) ->
                                Log.w(TAG, "harvest js error: " + payload.removePrefix(ERROR_PREFIX).take(160))
                            payload.startsWith("DIRECT:") -> {
                                // 我们自己发起的请求最可信：立即采用，并让 grace 逻辑让位
                                Log.d(TAG, "harvest captured DIRECT payload len=" + payload.length)
                                pendingDirect = payload.removePrefix("DIRECT:")
                                finish(Result.success(pendingDirect!!))
                            }
                            payload.startsWith("HOOK:") -> {
                                val body = payload.removePrefix("HOOK:")
                                Log.d(TAG, "harvest captured HOOK payload len=" + body.length + ", grace 900ms for DIRECT")
                                // 页面自己发的可能不是目标接口；给自己发起的请求留一点时间先到
                                if (!graceScheduled) {
                                    graceScheduled = true
                                    pendingHook = body
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val direct = pendingDirect
                                        val shown = direct ?: pendingHook
                                        Log.d(TAG, "harvest grace ended, using " + (if (direct != null) "DIRECT" else "HOOK"))
                                        if (shown != null) finish(Result.success(shown))
                                    }, 900L)
                                } else {
                                    // 已经有 hook 在等待，保留更长的那个（通常是课表列表）
                                    if (pendingHook == null || body.length > pendingHook!!.length) pendingHook = body
                                }
                            }
                            else -> finish(Result.success(payload))
                        }
                    },
                ),
                BRIDGE_NAME,
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    val u = url ?: return
                    JwSession.captureGidFromUrl(u)
                    Log.d(TAG, "harvest step2 page started " + u.take(120))
                    // 新页面 = 新 JS 上下文，hook 必须重注
                    session.hooked = false
                    injectHook(view, session.marker)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url ?: return
                    JwSession.captureGidFromUrl(u)
                    Log.d(TAG, "harvest step3 page finished " + u.take(120))

                    if (JwSession.isCasLanding(u)) {
                        Log.w(TAG, "harvest landed on CAS, session expired")
                        finish(Result.failure(JwException.SessionExpired))
                        return
                    }
                    injectHook(view, session.marker)
                    // 已经落在课表子应用页：让页面自己发请求，直接拿数据
                    if (u.contains("/jwapp/sys/") && !session.requested) {
                        session.requested = true
                        Log.d(TAG, "harvest step4 on app page, trigger request " + session.requestPath)
                        view?.let { triggerPageRequest(it, session.requestPath, session.requestBody) }
                        return
                    }
                    if (session.appTapped) return
                    // 门户已就绪：开始「跳引导 → 点应用」的轮询
                    view?.let { drivePortal(it, session, ::finish, 0) }
                }

                override fun onReceivedError(
                    view: WebView?,
                    req: WebResourceRequest?,
                    err: android.webkit.WebResourceError?,
                ) {
                    if (req?.isForMainFrame != true) return
                    Log.w(TAG, "harvest main frame error " + err?.errorCode + " " + req.url)
                    finish(Result.failure(JwException.Network("教务页面加载失败：${err?.description}")))
                }

                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    JwSession.captureGidFromUrl(req?.url?.toString())
                    return false
                }
            }

            Log.d(TAG, "harvest step1 load portal " + JwSession.PORTAL_URL)
            webView.loadUrl(JwSession.PORTAL_URL)
        }.onFailure { t ->
            Log.e(TAG, "harvest init failed: " + t.message)
            finish(Result.failure(JwException.Network(t.message ?: "WebView 初始化失败")))
        }

        val result = try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { channel.receive() }
            if (r == null) {
                Log.w(
                    TAG,
                    "harvest TIMEOUT marker=" + targetMarker + " after " + TIMEOUT_MS +
                        "ms (url=" + webView.url?.take(120) + ")",
                )
            }
            r ?: Result.failure(JwException.SessionExpired)
        } finally {
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface(BRIDGE_NAME)
                JwViewHost.detach(webView)
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            Log.d(TAG, "harvest webview destroyed, marker=" + targetMarker)
        }

        result.fold(
            onSuccess = { payload ->
                Log.i(TAG, "harvest OK marker=" + targetMarker + " len=" + payload.length)
                payload
            },
            onFailure = { t ->
                Log.w(TAG, "harvest failed marker=" + targetMarker + ": " + t.message)
                throw (t as? JwException) ?: JwException.SessionExpired
            },
        )
    }

    /**
     * 向 WebView 注入一次真实触摸（内核级 MotionEvent）。
     *
     * 坐标换算：JS 给的是 CSS 像素，需要乘以 density，再加上 WebView 在窗口中的位置。
     *
     * 为什么必须走这一层：门户入口是 Web Component，对 click / dispatchEvent / PointerEvent
     * 全部不响应（实测合成几十次页面纹丝不动），只有内核级触摸能触发它的绑定。
     */
    private fun tapAt(view: WebView, cssX: Float, cssY: Float) {
        runCatching {
            val scale = view.resources.displayMetrics.density
            // 夹到 WebView 自身尺寸内：门户算出的坐标可能超出可视区（实测出现过 y=2606 而屏高 2400）
            val maxX = (view.width - 2).coerceAtLeast(4)
            val maxY = (view.height - 2).coerceAtLeast(4)
            val x = (cssX * scale + view.left).coerceIn(2f, maxX.toFloat())
            val y = (cssY * scale + view.top).coerceIn(2f, maxY.toFloat())
            val down = android.os.SystemClock.uptimeMillis()
            val d = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            view.dispatchTouchEvent(d)
            d.recycle()
            val up = android.os.SystemClock.uptimeMillis()
            val u = android.view.MotionEvent.obtain(down, up + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
            u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            view.dispatchTouchEvent(u)
            u.recycle()
            Log.i(
                TAG,
                "harvest step4 real touch at " + x.toInt() + "," + y.toInt() +
                    " (css=" + cssX.toInt() + "," + cssY.toInt() +
                    " scale=" + scale + " view=" + view.width + "x" + view.height + ")",
            )
        }.onFailure { Log.w(TAG, "tapAt failed", it) }
    }

    /**
     * 门户页上的推进循环。
     *
     * 实测的有效顺序（缺一步都点不动）：
     *   1. 关掉「了解新平台 / 请耐心等待」引导遮罩（否则 bodyLen 只有 30）
     *   2. 点顶部「N 个多类型服务」展开面板（不展开时应用卡片尺寸为 0）
     *   3. 找「我的课表」并用**内核级触摸**点开（Web Component 不认 JS 合成事件）
     *   4. 跳转后 URL 带 gid_，hook 捕获目标接口
     */
    private fun drivePortal(
        view: WebView,
        session: Session,
        finish: (Result<String>) -> Unit,
        round: Int,
    ) {
        if (round > 22) {
            Log.w(TAG, "harvest step4 portal drive exhausted")
            finish(Result.failure(JwException.SessionExpired))
            return
        }

        view.evaluateJavascript(scriptProbe(session.appLabel)) { raw ->
            val res = raw.orEmpty().trim('"')
            Log.d(TAG, "harvest step4 round=" + round + " probe=" + res.take(120))

            when {
                res.startsWith("GUIDE") -> {
                    view.evaluateJavascript(SCRIPT_SKIP_GUIDE) { r ->
                        Log.d(TAG, "harvest step4 skip guide -> " + r.orEmpty().trim('"').take(60))
                        view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS * 2)
                    }
                }

                res.startsWith("HAS_APP") -> {
                    if (!session.directTried && session.appPath.isNotBlank()) {
                        // 首选：按 appName 直接构造子应用入口并导航（不依赖门户点击）
                        session.directTried = true
                        val url = JwSession.appIndexUrl(session.appPath)
                        Log.i(TAG, "harvest step4 direct navigate to " + url.take(140))
                        view.loadUrl(url)
                        view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS * 3)
                        return@evaluateJavascript
                    }
                    if (!session.serviceTapped) {
                        // 第 2 步：展开多类型服务面板
                        view.evaluateJavascript(SCRIPT_SERVICE_RECT) { sr ->
                            val rect = sr.orEmpty().trim('"')
                            Log.d(TAG, "harvest step4 service rect -> " + rect)
                            tapCssPoint(view, rect)
                            session.serviceTapped = true
                            view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS * 3)
                        }
                    } else if (session.searched < 1 && !session.appTapped) {
                        // 第 2.5 步：在搜索框输入应用名，逼门户把卡片渲染出来
                        session.searched++
                        view.evaluateJavascript(scriptSearch(session.appLabel)) { sr ->
                            Log.d(TAG, "harvest step4 search#" + session.searched + " -> " + sr.orEmpty().trim('"').take(40))
                            view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS * 3)
                        }
                    } else if (!session.appTapped) {
                        // 第 3 步：点开应用（优先内核触摸，取不到坐标再退回 JS 合成）
                        view.evaluateJavascript(scriptAppRect(session.appLabel)) { ar ->
                            val rect = ar.orEmpty().trim('"')
                            Log.d(TAG, "harvest step4 app rect -> " + rect)
                            if (tapCssPoint(view, rect)) {
                                session.appTapped = true
                                view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS * 2)
                            } else {
                                view.evaluateJavascript(scriptTapApp(session.appLabel)) { tr ->
                                    Log.d(TAG, "harvest step4 tap app(js) -> " + tr.orEmpty().trim('"').take(60))
                                    view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS)
                                }
                            }
                        }
                    } else {
                        // 已点过：等跳转，期间继续注入 hook 以防新页面漏注入
                        injectHook(view, session.marker)
                        view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS)
                    }
                }

                else -> view.postDelayed({ drivePortal(view, session, finish, round + 1) }, STEP_DELAY_MS)
            }
        }
    }

    /**
     * 按 "x,y"（CSS 像素）注入一次内核级触摸。
     *
     * @return 坐标有效且已注入返回 true
     */
    private fun tapCssPoint(view: WebView, rect: String): Boolean {
        if (!rect.contains(",")) return false
        val parts = rect.split(",")
        val cx = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return false
        val cy = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return false
        if (cx <= 0f || cy <= 0f) return false
        tapAt(view, cx, cy)
        return true
    }

    /**
     * 注入 fetch / XHR hook：页面自己发出的请求命中 marker 时，把响应文本回传给 Kotlin。
     *
     * 只读旁路，不改动页面行为；重复注入会被 window.__jnuHooked 拦掉。
     */
    private fun injectHook(view: WebView?, marker: String) {
        view?.evaluateJavascript(hookScriptFor(marker)) { r ->
            if (r.orEmpty().contains("HOOKED")) {
                Log.d(TAG, "harvest step2 hook injected for marker=" + marker)
            }
        }
    }

    /** JS ↔ Kotlin 桥，只暴露一个回调，缩小注入面 */
    private class Bridge(private val sink: (String) -> Unit) {
        @JavascriptInterface
        fun onData(json: String) {
            sink(json)
        }
    }

    /**
     * 生成带 marker 的 hook 脚本。marker 在注入前用 JSONObject.quote 转义。
     */
    private fun hookScriptFor(marker: String): String =
        SCRIPT_HOOK_TEMPLATE.replace("__MARKER__", org.json.JSONObject.quote(marker))

    /** hook 脚本模板；__MARKER__ 会被替换成 JSON 字符串字面量 */
    private val SCRIPT_HOOK_TEMPLATE =
        "(function(){" +
            "if(window.__jnuHooked)return 'ALREADY';" +
            "window.__jnuHooked=true;" +
            "var M=__MARKER__;" +
            "var send=function(t){try{if(t&&t.length>20){JwBridge.onData('HOOK:'+t);}}catch(e){}};" +
            "var of=window.fetch;" +
            "if(of){window.fetch=function(u,o){return of.apply(this,arguments).then(function(r){" +
            "try{var url=(typeof u==='string')?u:((u&&u.url)||'');" +
            "if(url.indexOf(M)>-1){r.clone().text().then(send).catch(function(){});}}catch(e){}" +
            "return r;});};}" +
            "var os=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.send=function(){" +
            "var self=this;" +
            "this.addEventListener('load',function(){" +
            "try{var u=self.responseURL||'';if(u.indexOf(M)>-1){send(self.responseText);}}catch(e){}});" +
            "return os.apply(this,arguments);};" +
            "return 'HOOKED';})()"

    /**
     * 取「我的课表」入口的跳转地址。
     *
     * 实测：门户对 `element.click()` 与合成 MouseEvent 都不响应（SPA 绑定的不是 DOM 原生事件），
     * 所以拿到 href / onclick 里的 URL 后由 Kotlin 侧直接 loadUrl 更可靠。
     */
    private val SCRIPT_APP_ENTRY_URL =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/[ \\t\\n\\r]+/g,'');};" +
            "var els=document.querySelectorAll('a,li,div,span');" +
            "for(var i=0;i<els.length;i++){var t=q(els[i].textContent);" +
            "if(els[i].children.length===0&&(t==='我的课表'||t==='学生课程表')){" +
            "var n=els[i];" +
            "for(var up=0;up<5&&n;up++){" +
            "var h=n.getAttribute&&(n.getAttribute('href')||'');" +
            "if(h&&h!=='#'&&h.indexOf('javascript')!==0){return 'URL:'+h;}" +
            "var oc=n.getAttribute&&(n.getAttribute('onclick')||'');" +
            "if(oc&&oc.length>8){var a=oc.indexOf('/jwapp');var b=oc.indexOf('gid_');" +
            "if(a>-1||b>-1){return 'ONCLICK:'+oc.substring(0,200);}}" +
            "var du=n.getAttribute&&(n.getAttribute('data-url')||n.getAttribute('data-href')||'');" +
            "if(du){return 'URL:'+du;}" +
            "n=n.parentElement;}" +
            "return 'NO_URL';}}" +
            "return 'NO_APP';}catch(e){return 'ERR '+e;}})()"

    /** 排障：打印「我的课表」元素的祖先链（tag/id/class/href/onclick），定位跳转方式 */
    private val SCRIPT_DIAG_APP =
        "(function(){try{var out=[];var els=document.querySelectorAll('*');var n=0;" +
            "for(var i=0;i<els.length;i++){var e=els[i];" +
            "var t=(e.textContent||'').replace(/[ \\t\\n\\r]+/g,'');" +
            "if(t==='我的课表'||t==='学生课程表'){" +
            "var chain=[];var c=e;" +
            "for(var d=0;d<5&&c;d++){" +
            "chain.push(c.tagName+'|id='+(c.id||'')+'|cls='+(typeof c.className==='string'?c.className:'')" +
            "+'|href='+((c.getAttribute&&c.getAttribute('href'))||'')" +
            "+'|oc='+(((c.getAttribute&&c.getAttribute('onclick'))||'').substring(0,50)));" +
            "c=c.parentElement;}" +
            "out.push(chain.join(' >> '));if(++n>=1)break;}}" +
            "return out.join(' ### ')||'NOT_FOUND';}catch(e){return 'ERR '+e;}})()"

    /**
     * 返回「N 个多类型服务」触发按钮的坐标（CSS 像素）。
     *
     * 这是门户顶部的搜索入口。实测：不先点开它，应用卡片处于折叠状态，
     * 「我的课表」能查到但 getBoundingClientRect() 尺寸为 0 —— 自然点不动。
     */
    private val SCRIPT_SERVICE_RECT =
        "(function(){try{var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var all=[];var walk=function(root){var ns=root.querySelectorAll?root.querySelectorAll('*'):[];" +
            "for(var i=0;i<ns.length;i++){all.push(ns[i]);try{if(ns[i].shadowRoot)walk(ns[i].shadowRoot);}catch(e){}}};" +
            "walk(document);" +
            "for(var i=0;i<all.length;i++){var t=q(all[i].textContent);" +
            "if(t.indexOf('多类型服务')>-1&&t.length<26){" +
            "var r=all[i].getBoundingClientRect();" +
            "if(r.width>4&&r.height>4){return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);}}}" +
            "return '';}catch(e){return '';}})()"

    /**
     * 返回「我的课表」在页面上的可视位置（CSS 像素）：x,y,w,h。
     *
     * 需要它是因为门户入口对 JS 合成事件（click / dispatchEvent）完全不响应，
     * 只有内核级真实触摸才能触发；Kotlin 侧拿到坐标后用 MotionEvent 注入。
     */
    private fun scriptAppRect(label: String): String =
        "(function(){try{var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var want=" + jsQuote(label) + ";" +
            "var all=[];var walk=function(root){var ns=root.querySelectorAll?root.querySelectorAll('*'):[];" +
            "for(var i=0;i<ns.length;i++){all.push(ns[i]);try{if(ns[i].shadowRoot)walk(ns[i].shadowRoot);}catch(e){}}};" +
            "walk(document);" +
            "for(var i=0;i<all.length;i++){var t=q(all[i].textContent);" +
            "if(t===want){" +
            "var n=all[i],box=n;" +
            "for(var u=0;u<6&&n;u++){if(n.tagName&&n.tagName.indexOf('WIDGET-APP')>-1){box=n;break;}" +
            "n=n.parentElement||n.host;}" +
            // 先滚动到可见区再取坐标：面板内卡片常在折叠区域，不滚动尺寸为 0
            "try{box.scrollIntoView({block:'center'});}catch(e){}" +
            "var r=box.getBoundingClientRect();" +
            "if(!(r.width>4&&r.height>4)){" +
            // 仍然不可见：把可能的滚动容器滚到底，再取一次
            "try{var sc=document.querySelectorAll('[class*=scroll],[class*=content],main,section');" +
            "for(var s=0;s<sc.length;s++){try{sc[s].scrollTop=sc[s].scrollHeight;}catch(e){}}}catch(e){}" +
            "try{box.scrollIntoView({block:'center'});}catch(e){}" +
            "r=box.getBoundingClientRect();}" +
            "if(r.width>4&&r.height>4){" +
            "var vw=window.innerWidth||document.documentElement.clientWidth||0;" +
            "var vh=window.innerHeight||document.documentElement.clientHeight||0;" +
            "var cx=r.left+r.width/2, cy=r.top+r.height/2;" +
            // 元素在视口外：逐级滚动父容器，直到中心点落进可视区
            "for(var g=0;g<6&&(cy<8||cy>vh-8||cx<8||cx>vw-8);g++){" +
            "  try{box.scrollIntoView({block:'center',inline:'center'});}catch(e){}" +
            "  var pr=box.parentElement;" +
            "  for(var h=0;h<4&&pr;h++){try{if(pr.scrollHeight>pr.clientHeight){pr.scrollTop+=88;}}catch(e){}pr=pr.parentElement;}" +
            "  r=box.getBoundingClientRect();cx=r.left+r.width/2;cy=r.top+r.height/2;}" +
            "if(cx<4)cx=4; if(cy<4)cy=4;" +
            "if(vw>8&&cx>vw-4)cx=vw-4; if(vh>8&&cy>vh-4)cy=vh-4;" +
            "var vpw=window.innerWidth||0, vph=window.innerHeight||0;" +
            "var dpr=window.devicePixelRatio||1;" +
            "return Math.round(cx)+','+Math.round(cy)+','+vpw+'x'+vph+'@'+dpr;}}}" +
            "return '';}catch(e){return '';}})()"

    /** 把字符串安全地变成 JS 字面量 */
    private fun jsQuote(s: String): String = org.json.JSONObject.quote(s)

    /**
     * 在课表子应用页里**让页面自己发起**目标请求。
     *
     * 之所以能成：此时页面本身处在 jwapp 同源上下文，fetch 带的是页面自己的会话指纹，
     * 服务端不会再按「非浏览器请求」拒掉（OkHttp 直连则会 403）。
     */
    private fun triggerPageRequest(view: WebView, path: String, body: String) {
        val script = "(function(){try{" +
            "var b=" + org.json.JSONObject.quote(body) + ";" +
            "window.__jnuDirect=true;" +
            "fetch(" + org.json.JSONObject.quote(path) + ",{method:'POST',credentials:'include'," +
            "headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'," +
            "'X-Requested-With':'XMLHttpRequest'},body:b})" +
            ".then(function(r){return r.text();})" +
            ".then(function(t){try{window.__jnuDirect=false;JwBridge.onData('DIRECT:'+t);}catch(e){}})" +
            ".catch(function(e){try{JwBridge.onData('ERROR:'+e);}catch(x){}});" +
            "}catch(e){try{JwBridge.onData('ERROR:'+e);}catch(x){}}})()"
        view.evaluateJavascript(script) {
            Log.d(TAG, "harvest step5 triggered page request " + path)
        }
    }
    /**
     * 在「多类型服务」面板的搜索框里输入应用名并回车。
     *
     * 实测：面板展开后应用卡片仍是懒渲染（getBoundingClientRect 全为 0），
     * 只有输入关键字筛选后，匹配结果才会真正渲染出来并具备可点坐标。
     */
    private fun scriptSearch(label: String): String =
        "(function(){try{" +
            "var want=" + jsQuote(label) + ";" +
            // 门户搜索用简称更容易命中（如「成绩查询」→「成绩」）
            "var kw=want.replace(/查询|我的|安排|完成|学生/g,'');" +
            "if(!kw||kw.length<2){kw=want.substring(1,3);}" +
            "var box=null;" +
            "var all=[];var walk=function(root){var ns=root.querySelectorAll?root.querySelectorAll('*'):[];" +
            "for(var i=0;i<ns.length;i++){all.push(ns[i]);try{if(ns[i].shadowRoot)walk(ns[i].shadowRoot);}catch(e){}}};" +
            "walk(document);" +
            "for(var i=0;i<all.length;i++){var el=all[i];" +
            "if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){" +
            "var ph=(el.placeholder||'')+(el.getAttribute&&(el.getAttribute('placeholder')||'')||'');" +
            "if(ph.indexOf('搜索')>-1||ph.indexOf('服务')>-1||el.type==='search'||el.type==='text'){box=el;break;}}}" +
            "if(!box){return 'NO_INPUT';}" +
            "try{box.focus();}catch(e){}" +
            "box.value=kw;" +
            "try{box.dispatchEvent(new Event('input',{bubbles:true}));}catch(e){}" +
            "try{box.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}" +
            "try{box.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:'Enter',keyCode:13}));}catch(e){}" +
            "try{box.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Enter',keyCode:13}));}catch(e){}" +
            "return 'SEARCHED';}catch(e){return 'ERR '+e;}})()"

    /** 门户状态探测：GUIDE / HAS_APP / WAIT */
    private fun scriptProbe(label: String): String =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var want=" + jsQuote(label) + ";" +
            "var len=document.body?document.body.innerText.length:-1;" +
            "var txt=q(document.body?document.body.innerText:'');" +
            "var els=document.querySelectorAll('a,li,div,span');" +
            "for(var i=0;i<els.length;i++){var t=q(els[i].textContent);" +
            "if(els[i].children.length===0&&t===want){return 'HAS_APP len='+len;}}" +
            "if(txt.indexOf('了解新平台')>-1||txt.indexOf('跳过')>-1||txt.indexOf('请耐心等待')>-1){" +
            "return 'GUIDE len='+len;}" +
            "return 'WAIT len='+len;}catch(e){return 'ERR '+e;}})()"

    /**
     * 跳过引导遮罩：命中「跳过 / 了解新平台 / 知道了」时，
     * 同时调用 click() 与派发 MouseEvent（实测单用 click 有时被框架忽略）。
     */
    private val SCRIPT_SKIP_GUIDE =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var els=document.querySelectorAll('a,button,span,div,i');" +
            // 按优先级找：跳过 > 我知道了 > 关闭 > 了解新平台
            // 「了解新平台」会打开教程弹窗，不是关闭动作，必须放最后
            "var want=['跳过','跳过引导','我知道了','知道了','关闭','了解新平台'];" +
            "for(var w=0;w<want.length;w++){" +
            "  for(var i=0;i<els.length;i++){" +
            "    var t=q(els[i].textContent);" +
            "    if(t===want[w]&&els[i].children.length===0){" +
            "      try{els[i].click();}catch(e){}" +
            "      try{var r=els[i].getBoundingClientRect();" +
            "        var o={bubbles:true,cancelable:true," +
            "          clientX:r.left+r.width/2,clientY:r.top+r.height/2,view:window};" +
            "        els[i].dispatchEvent(new MouseEvent('mousedown',o));" +
            "        els[i].dispatchEvent(new MouseEvent('mouseup',o));" +
            "        els[i].dispatchEvent(new MouseEvent('click',o));}catch(e){}" +
            "      return 'CLICKED:'+t;" +
            "    }" +
            "  }" +
            "}" +
            "return 'NOT_FOUND';}catch(e){return 'ERR '+e;}})()"

    /**
     * 点开目标应用，多级兜底：
     *   1. 文本就是「我的课表 / 学生课程表」的叶子节点，向上找可点容器
     *   2. class 含 card/app/item 且文本较短的元素
     *   3. 「N 个多类型服务」搜索入口
     */
    private fun scriptTapApp(label: String): String {
        val want = jsQuote(label)
        return "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var want=" + want + ";" +
            "var all=[];" +
            "var walk=function(root){var ns=root.querySelectorAll?root.querySelectorAll('*'):[];" +
            "for(var i=0;i<ns.length;i++){var el=ns[i];all.push(el);" +
            "try{if(el.shadowRoot){walk(el.shadowRoot);}}catch(e){}}};" +
            "walk(document);" +
            "var fire=function(el){if(!el)return false;" +
            "try{el.scrollIntoView({block:'center'});}catch(e){}" +
            "try{el.focus();}catch(e){}" +
            "var r=el.getBoundingClientRect();" +
            "var o={bubbles:true,cancelable:true,composed:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,view:window,button:0,pointerId:1,isPrimary:true,pointerType:'touch'};" +
            "try{el.dispatchEvent(new PointerEvent('pointerdown',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mousedown',o));}catch(e){}" +
            "try{el.dispatchEvent(new PointerEvent('pointerup',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mouseup',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('click',o));}catch(e){}" +
            "try{el.click();}catch(e){}" +
            "return true;};" +
            "var target=null,host=null;" +
            "for(var i=0;i<all.length;i++){var t=q(all[i].textContent);" +
            "if(t===want){target=all[i];" +
            "var n=target;for(var u=0;u<6&&n;u++){" +
            "if(n.tagName&&n.tagName.indexOf('WIDGET-APP')>-1){host=n;break;}" +
            "if(n.className&&(''+n.className).indexOf('widget-app-item')>-1){host=n;break;}" +
            "n=n.parentElement||(n.host);}" +
            "break;}}" +
            "if(target){" +
            "fire(host||target);" +
            "if(host&&host!==target){fire(target);}" +
            "return 'TAPPED'+(host?'_HOST':'');}" +
            "for(var j=0;j<all.length;j++){var ct=q(all[j].textContent);" +
            "if(ct.length>=2&&ct.length<=12&&ct.indexOf(want.substring(1,want.length-1))>-1){fire(all[j]);return 'TAPPED_FALLBACK';}}" +
            "return 'NOT_FOUND';" +
            "}catch(e){return 'ERR '+e;}})()"
    }
}