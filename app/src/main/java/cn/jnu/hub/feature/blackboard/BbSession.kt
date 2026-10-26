package cn.jnu.hub.feature.blackboard

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Blackboard 会话建立。
 *
 * ## 为什么需要单独一层（踩过的坑）
 *
 * 用户点「去登录」→ 打开的是统一认证 CAS 页 → 但 CAS 会话**本来就还在**，
 * 于是 CAS 直接 302 到登录落地页（网上服务大厅 ehall）。整条链跑完：
 * - CAS 会话没刷新
 * - **Blackboard 的会话也没建立**
 *
 * 回来以后 BB 依然是未登录，用户以为「登录坏了」。
 *
 * 根因：BB 需要一个**自己域的会话**（BbRouter / JSESSIONID / session Cookie），
 * 光有 CAS 会话不够。必须让浏览器真实访问一次 BB 首页，
 * 由 auth4 网关 + BB 自己的 CAS 回跳链把 Cookie 发下来。
 *
 * ## 本类提供的两步
 *
 * 1. [tryBootstrapWithHttp]：先让 OkHttp 直接请求 BB 首页并跟随 302。
 *    CAS 会话有效时服务端会在回跳时 Set-Cookie，**多数情况零点击即可完成**。
 * 2. 失败（CAS 失效 / 需要重新输密码）才由 UI 打开内嵌 WebView；
 *    落到 BB 域后再由 [syncWebViewCookies] 把 BB 域 Cookie 灌进共享 CookieJar。
 *
 * ## 与 StuitSession 的差别
 *
 * BB 的 Cookie 域名是 blackboard.jnu.edu.cn，所以只同步这一个域，
 * 不碰 icas / auth4（那边的 CASTGC 是 HttpOnly，同步只会污染 CookieJar）。
 */
object BbSession {

    private const val TAG = "BbSession"

    /** 站点根，也是 WebView 要打开的地址 */
    val BASE: String = JnuHosts.BLACKBOARD

    /** 首页：CAS 的 service 指向它，也是让 BB 下发自己会话 Cookie 的入口 */
    val HOME_URL: String = BASE + "/"

    /** BB 自身的域名 */
    private const val BB_HOST = "blackboard.jnu.edu.cn"

    /** Accept 头拆开拼，避免 "*" + "/" + "*" 被 Kotlin 当成块注释开头 */
    private val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9," + "*" + "/" + "*;q=0.8"

    /** 刚同步成功的时间戳，供 UI 做「刚同步完就不用再探」的优化 */
    @Volatile
    var lastVerifiedAt: Long = 0L
        private set

    fun markVerified() {
        lastVerifiedAt = System.currentTimeMillis()
    }

    fun invalidate() {
        lastVerifiedAt = 0L
    }

    /** 刚同步过（5 分钟内）就认为可用，省一次往返 */
    fun recentlyVerified(): Boolean =
        System.currentTimeMillis() - lastVerifiedAt in 0 until 5 * 60 * 1000L

    // ------------------------- URL 判定 -------------------------

    /** WebView 当前地址是否已经是 BB 自己的页面（不是 CAS，也不是 ehall 等别的站） */
    fun isBbUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host.orEmpty()
        if (!host.equals(BB_HOST, ignoreCase = true)) return false
        // BB 自己的 CAS 回跳入口也算「还在认证中」，不算落地的业务页
        val path = parsed.path.orEmpty()
        if (path.contains("/cas/login")) return false
        if (path.contains("/bb-auth-provider")) return false
        return true
    }

    /** 是否停在统一认证域 */
    fun isCasUrl(url: String?): Boolean {
        val u = url ?: return false
        return u.contains("/cas/login") ||
            u.contains("icas.jnu.edu.cn") ||
            u.contains("auth4.jnu.edu.cn") ||
            u.contains("auth7.jnu.edu.cn")
    }

    // ------------------------- 建立会话 -------------------------

    /**
     * 让 OkHttp 自己走一遍 BB 首页的 302 链。
     *
     * GET https://blackboard.jnu.edu.cn/
     *   -> 302 auth4/icas（CookieJar 里若已有 CAS 会话，会一路放行）
     *   -> 302 回 BB 并 Set-Cookie: JSESSIONID / BbRouter ...
     *
     * @return true = 会话已建立；false = 需要用户在内嵌 WebView 里操作
     */
    suspend fun tryBootstrapWithHttp(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val req = Request.Builder()
                .url(HOME_URL)
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()

            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                Log.d(TAG, "bootstrap -> " + resp.code + " " + finalUrl + " cookies=" + cookieNames())
                when {
                    // 落到 CAS 登录表单：CAS 本身失效了
                    isCasUrl(finalUrl) || BbApi.looksLikeCasLogin(html) -> false
                    // BB 明确拒绝授权：这不算「没登录」，但也不需要再让用户折腾
                    resp.code == 403 || BbApi.isGrantDeniedUrl(finalUrl) -> false
                    else -> resp.code in 200..299 && html.isNotBlank()
                }
            }
        }.getOrElse { t ->
            Log.w(TAG, "bootstrap failed", t)
            false
        }

        if (ok) markVerified()
        ok
    }

    /**
     * 统一入口：确保 BB 会话可用。
     *
     * @param reuseRecent 刚同步过（5 分钟内）就跳过请求
     */
    suspend fun ensureSession(reuseRecent: Boolean = true): Boolean {
        if (reuseRecent && recentlyVerified()) {
            Log.d(TAG, "ensureSession: recently verified, skip")
            return true
        }
        if (tryBootstrapWithHttp()) {
            Log.d(TAG, "ensureSession: ok via http bootstrap")
            return true
        }
        Log.d(TAG, "ensureSession: failed, need webview")
        return false
    }

    // ------------------------- WebView 协作 -------------------------

    /** 把 OkHttp 侧已有的 jnu 域 Cookie 推进 WebView，尽量免二次输入密码 */
    fun seedWebViewCookies() {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            HttpClient.cookieJar().snapshot()
                .filter { it.domain.endsWith("jnu.edu.cn") }
                .forEach { c ->
                    cm.setCookie("https://" + c.domain + "/", c.name + "=" + c.value + "; Path=/")
                }
            cm.flush()
            Log.d(TAG, "seeded webview cookies from " + HttpClient.cookieJar().snapshot().size + " jar entries")
        }.onFailure { Log.w(TAG, "seed webview cookies failed", it) }
    }

    /** 只打印 Cookie 名，绝不打印值 */
    private fun cookieNameList(raw: String): String =
        raw.split(';')
            .mapNotNull { it.trim().substringBefore('=', "").trim().takeIf { n -> n.isNotEmpty() } }
            .joinToString(",")

    /**
     * 把 WebView 里 BB 域的 Cookie 同步到共享 CookieJar。
     *
     * @param pageUrl WebView 当前停留的地址（必须是 BB 域，否则直接跳过）
     * @return 同步成功的 Cookie 条数；0 表示没读到
     */
    fun syncWebViewCookies(pageUrl: String = HOME_URL): Int = runCatching {
        val cm = CookieManager.getInstance()
        cm.flush()

        val url = if (pageUrl.startsWith("http")) pageUrl else "https://" + pageUrl
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return@runCatching 0

        // 只接受 BB 域；CAS 域的票是 HttpOnly，同步只会污染 CookieJar
        if (!host.equals(BB_HOST, ignoreCase = true)) {
            Log.w(TAG, "syncWebViewCookies: skip non-BB host " + host)
            return@runCatching 0
        }

        val raw = cm.getCookie(url).orEmpty()
        Log.d(TAG, "webview cookie for " + host + " len=" + raw.length + " names=[" + cookieNameList(raw) + "]")
        if (raw.isBlank()) return@runCatching 0

        HttpClient.cookieJar().syncFromWebView(mapOf(host to raw))
        val count = raw.split(';').count { it.contains('=') }
        Log.d(TAG, "synced " + count + " cookies from " + host)
        if (count > 0) markVerified()
        count
    }.onFailure { Log.w(TAG, "syncWebViewCookies failed", it) }.getOrDefault(0)

    /** 同步后用 OkHttp 再验一次，确认真的能拿到 BB 页面 */
    suspend fun probeWithOkHttp(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val req = Request.Builder()
                .url(BASE + "/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_1_1")
                .header("Accept", ACCEPT_HTML)
                .get()
                .build()

            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                val good = !isCasUrl(finalUrl) &&
                    resp.code in 200..299 &&
                    html.isNotBlank() &&
                    !BbApi.looksLikeCasLogin(html)
                Log.d(TAG, "okhttp probe = " + good + " (code=" + resp.code + " url=" + finalUrl + ")")
                good
            }
        }.getOrElse { t ->
            Log.w(TAG, "okhttp probe failed", t)
            false
        }

        if (ok) markVerified()
        ok
    }

    /** BB 域当前有哪些 cookie（排障用，不打印值） */
    fun cookieNames(): List<String> = runCatching {
        HttpClient.cookieJar().snapshot()
            .filter { it.domain.contains(BB_HOST) }
            .map { it.name }
    }.getOrDefault(emptyList())

    /** 是否已有 BB 域会话 cookie（仅日志/排障用，不作判据） */
    fun hasAnySessionCookie(): Boolean = cookieNames().isNotEmpty()
}
