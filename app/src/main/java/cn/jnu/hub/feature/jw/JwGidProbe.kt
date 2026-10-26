package cn.jnu.hub.feature.jw

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在隐藏 WebView 里「走一遍用户点应用的操作」，从而拿到**本账号**的 gid_。
 *
 * ## 为什么需要它
 *
 * 教务首页（new/index.html）本身**不带 gid_**。只有真的「点开某个应用」时，
 * jw 才会生成 gid_ 并拼在子应用入口 URL 上：
 *
 *     /jwapp/sys/wdkb/ 的子应用首页（路径中间含星号目录）?t_s=…&gid_=<本账号专属值>
 *
 * 早期版本把某次抓包里的值写死成常量，导致只有抓包那个账号能登录（其他用户全 403）。
 * gid_ 必须**每个账号登录后现取一次**。
 *
 * ## 流程
 *
 *   1. 打开门户首页（复用已有登录态，通常不会跳 CAS）
 *   2. 等页面真正渲染完（bodyLen 够大，且出现「最近使用 / 我的课表」等字样）
 *   3. 依次尝试：
 *        a. 页面里 **已有** 带 gid_ 的链接 → 直接取 href（最快，不用跳转）
 *        b. 点「🔍 N 个多类型服务」搜索按钮 → 应用列表出现 → 找「我的课表」项
 *        c. 点击「我的课表」→ 子应用页 URL 上带 gid_ → 从 onPageStarted 里抓
 *   4. 任何一步拿到 gid_ 就立刻结束（不要求一定跳转成功）
 *
 * 全程有超时兜底，失败只是返回 false，不影响主流程（接口会 403 并给出提示）。
 */
object JwGidProbe {

    private const val TAG = "JwGidProbe"

    /** 整体超时：门户渲染 + 展开列表 + 点击跳转 */
    private const val TIMEOUT_MS = 25_000L

    /** 每轮轮询间隔 */
    private const val STEP_DELAY_MS = 700L

    /**
     * 尝试为当前账号获取一次 gid_ 并写入 [JwGidStore]。
     *
     * @return true 表示拿到了新值（或本次已确认可用）
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.Main) {
        val app = context.applicationContext
        val webView = runCatching { WebView(app) }.getOrElse { t ->
            Log.w(TAG, "cannot create webview: " + t.message)
            return@withContext false
        }

        val done = Channel<Boolean>(1)
        var finished = false
        fun finish(ok: Boolean, reason: String) {
            if (finished) return
            finished = true
            Log.d(TAG, "probe finished: " + reason + " ok=" + ok)
            done.trySend(ok)
        }

        runCatching {
            // 这些设置必须与登录弹层里那个**能正常渲染门户**的 WebView 保持一致。
            // 实测缺 useWideViewPort/loadWithOverviewMode/mixedContentMode 时，
            // 门户会一直停在「请耐心等待……」（bodyLen=30），首页永远渲染不出来。
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.setSupportZoom(true)
            webView.settings.builtInZoomControls = true
            webView.settings.displayZoomControls = false
            webView.settings.userAgentString = cn.jnu.hub.core.network.HttpClient.USER_AGENT
            webView.settings.mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            runCatching {
                android.webkit.CookieManager.getInstance()
                    .setAcceptThirdPartyCookies(webView, true)
            }
            JwSession.seedWebViewCookies()
            JwViewHost.attach(webView)
            // 排障：确认隐藏 WebView 到底带了哪些 jw cookie
            runCatching {
                val ck = android.webkit.CookieManager.getInstance()
                    .getCookie(JwSession.PORTAL_URL).orEmpty()
                val names = ck.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
                Log.d(TAG, "probe webview cookies(" + names.size + ") = " + names)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // 点开应用后，子应用 URL 上就带 gid_ —— 这是最直接的获取点
                    if (JwSession.captureGidFromUrl(url)) finish(true, "gid captured on navigation")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url ?: return
                    if (JwSession.captureGidFromUrl(u)) {
                        finish(true, "gid captured on page finished")
                        return
                    }
                    if (!JwSession.isBusinessUrl(u)) {
                        Log.d(TAG, "page finished (non business): " + u.take(90))
                        return
                    }
                    // 页面就绪后开始「找入口 → 点进去」的轮询
                    view?.let { startPortalProbe(it, ::finish) }
                }

                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    JwSession.captureGidFromUrl(req?.url?.toString())
                    return false
                }
            }

            Log.d(TAG, "opening portal to fetch gid_")
            webView.loadUrl(JwSession.PORTAL_URL)
        }.onFailure { t ->
            Log.w(TAG, "probe init failed", t)
            finish(false, "init failed: " + t.message)
        }

        val ok = try {
            withTimeoutOrNull(TIMEOUT_MS) { done.receive() } ?: false
        } finally {
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface("x")
                JwViewHost.detach(webView)
                webView.destroy()
            }
            Log.d(TAG, "probe webview destroyed")
        }
        if (!ok) Log.w(TAG, "gid probe timed out or failed")
        ok
    }

    private val SCRIPT_DUMP_AFTER_CLICK =
        "(function(){try{var out=[];" +
            "var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<as.length&&i<25;i++){out.push('A:'+(as[i].textContent||'').trim().substring(0,20)+'|'+(as[i].href||'').substring(0,110));}" +
            "var ifs=document.querySelectorAll('iframe');" +
            "for(var j=0;j<ifs.length;j++){out.push('IF:'+(ifs[j].src||'').substring(0,110));}" +
            "out.push('BODYLEN='+(document.body?document.body.innerText.length:-1));" +
            "out.push('TEXT='+(document.body?document.body.innerText.replace(/\\s+/g,' ').substring(0,320):''));" +
            "return out.join(' ~~ ');}catch(e){return 'ERR '+e;}})()"

    private val SCRIPT_PROBE_PORTAL =
        "(function(){try{" +
            "var q=function(s){return (s||'').replace(/\\s+/g,'');};" +
            "var tap=function(el){if(!el)return false;var r=el.getBoundingClientRect();" +
            "var x=r.left+r.width/2,y=r.top+r.height/2;" +
            "var o={bubbles:true,cancelable:true,clientX:x,clientY:y,view:window,button:0};" +
            "try{el.dispatchEvent(new PointerEvent('pointerdown',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mousedown',o));}catch(e){}" +
            "try{el.dispatchEvent(new PointerEvent('pointerup',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('mouseup',o));}catch(e){}" +
            "try{el.dispatchEvent(new MouseEvent('click',o));}catch(e){}" +
            "return true;};" +
            "window.__diag=function(el){var r=el.getBoundingClientRect();return Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height);};" +
            "var __vw=(window.innerWidth||0)+'x'+(window.innerHeight||0)+'@'+(window.devicePixelRatio||0);" +
            "var html=document.documentElement.outerHTML;" +
            "var gm=html.match(/gid_=" + "[A-Za-z0-9+/=%]{40,}/);" +
            "if(gm){return 'GID_HREF:'+location.origin+'/jwapp/sys/wdkb/x?'+gm[0];}" +
            "try{var __k=[];for(var s in localStorage){__k.push(s);}var __v=localStorage.getItem('gid_')||localStorage.getItem('gid');" +
            "if(__v&&__v.length>40){return 'GID_RAW:'+__v;}" +
            "for(var si=0;si<__k.length;si++){var vv=localStorage.getItem(__k[si]);" +
            "if(vv&&vv.indexOf('gid')>-1&&vv.length>40&&vv.length<400){return 'GID_RAW:'+vv;}}}catch(e){}" +
            "var ifr=document.querySelectorAll('iframe');" +
            "for(var fi=0;fi<ifr.length;fi++){var fs=ifr[fi].src||'';" +
            "if(fs.indexOf('/jwapp/sys/')>-1){return 'APP_HREF:iframe|'+fs;}}" +
            "var items=document.querySelectorAll('a,li,div,span');" +
            "for(var i=0;i<items.length;i++){var t=q(items[i].textContent);" +
            "if(items[i].children.length===0&&(t==='我的课表'||t==='学生课程表')){" +
            "var n=items[i];" +
            "for(var up=0;up<5&&n;up++){" +
            "if(n.tagName==='A'&&n.getAttribute('href')&&n.getAttribute('href')!=='#'){return 'APP_HREF:'+t+'|'+n.href;}" +
            "n=n.parentElement;}" +
            "var __el=items[i].parentElement||items[i];tap(__el);return 'APP_TAPPED:'+t+' vw='+__vw+' rect='+window.__diag(__el);}}" +
            "var as=document.querySelectorAll('a[href*=gid_]');" +
            "for(var j=0;j<as.length;j++){if((as[j].href||'').indexOf('/jwapp/sys/')>-1){return 'GID_HREF:'+as[j].href;}}" +
            "var els=document.querySelectorAll('div,span,button,a');" +
            "for(var k=0;k<els.length;k++){var u=q(els[k].textContent);" +
            "if(u.indexOf('多类型服务')>-1&&u.length<24){" +
            "var target=els[k];" +
            "for(var d=0;d<4&&target.parentElement;d++){" +
            "var pu=q(target.parentElement.textContent);" +
            "if(pu===u){target=target.parentElement;}else{break;}}" +
            "tap(target);return 'TAPPED_SERVICE';}}" +
            "return 'NONE';" +
            "}catch(e){return 'ERR '+e;}})()"

    /**
     * 门户页就绪后的循环：先找「我的课表」，再点「N 个多类型服务」，最后看页面上的 gid_ 链接。
     *
     * 每轮都重新采样页面，因为金智门户是 SPA：
     * 点击后列表/路由是异步渲染的，需要给它几轮时间。
     */
    private fun startPortalProbe(view: WebView, finish: (Boolean, String) -> Unit) {
        var round = 0
        var serviceTapped = false
        // 首轮先看门户原始状态，便于排障
        view.evaluateJavascript(SCRIPT_DUMP_AFTER_CLICK) { d ->
            Log.d(TAG, "portal initial dump = " + d.orEmpty().take(1000))
        }

        fun tick() {
            if (round++ > 14) {
                finish(false, "portal probe exhausted")
                return
            }
            view.evaluateJavascript(SCRIPT_PROBE_PORTAL) { raw ->
                val result = raw.orEmpty().trim('"')
                Log.d(TAG, "probe round " + round + " -> " + result.take(120))
                when {
                    // 已经拿到带 gid_ 的链接：直接取参数
                    result.startsWith("GID_HREF:") -> {
                        val href = result.removePrefix("GID_HREF:")
                        if (JwSession.captureGidFromUrl(href)) {
                            finish(true, "gid from existing link")
                        } else {
                            view.postDelayed({ tick() }, STEP_DELAY_MS)
                        }
                    }
                    // 拿到应用入口 href：直接用 URL 导航，URL 上就会带 gid_
                    result.startsWith("APP_HREF:") -> {
                        val href = result.removePrefix("APP_HREF:").substringAfter('|')
                        Log.d(TAG, "app entry href = " + href.take(120))
                        if (JwSession.captureGidFromUrl(href)) {
                            finish(true, "gid from app href")
                        } else {
                            view.loadUrl(if (href.startsWith("/")) JwSession.BASE + href else href)
                            view.postDelayed({ tick() }, STEP_DELAY_MS)
                        }
                    }
                    // 已经真实触摸过应用项/搜索按钮：等路由变化（URL 变化会在 onPageStarted 抓到 gid_）
                    result.startsWith("APP_TAPPED:") || result == "TAPPED_SERVICE" -> {
                        if (result == "TAPPED_SERVICE") {
                            if (serviceTapped) {
                                // 列表已展开过，重复触摸只会反复开关，改为等待
                                view.postDelayed({ tick() }, STEP_DELAY_MS * 2)
                            } else {
                                serviceTapped = true
                                Log.d(TAG, "service button tapped, waiting for app list")
                                view.postDelayed({ tick() }, STEP_DELAY_MS)
                            }
                        } else {
                            Log.d(TAG, "app item tapped, waiting for route change")
                            view.postDelayed({ tick() }, STEP_DELAY_MS)
                        }
                    }
                    else -> view.postDelayed({ tick() }, STEP_DELAY_MS)
                }
            }
        }
        tick()
    }
}