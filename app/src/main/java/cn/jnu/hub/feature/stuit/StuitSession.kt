package cn.jnu.hub.feature.stuit

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 学工系统（stuit.jnu.edu.cn / D-Campus）会话建立。
 *
 * ## 为什么需要单独一层
 *
 * 学工和校园网一样**不认 auth4 网关直通**，而是走标准 CAS 票据交换：
 *
 *     GET https://stuit.jnu.edu.cn/                     （业务首页）
 *       -> 302 https://icas.jnu.edu.cn/cas/login?service=<stuit 首页>
 *       -> 302 https://stuit.jnu.edu.cn/?ticket=ST-xxx-cas
 *       -> 200 首页，服务端 Set-Cookie: JSESSIONID（学工自己的业务会话）
 *
 * 也就是说：CAS 已登录 != stuit 有会话。第一次访问时 CAS 校验通过、回到 stuit
 * 并下发 JSESSIONID 之后，后续所有 .jsp 页面才可用。若只做 CAS 登录而没有完成
 * 这一次回跳，stuit 的每个请求都会被 302 回登录页 —— 这正是本模块原先的现象。
 *
 * ## 建立方式
 *
 * 1. 先试 [tryBootstrapWithHttp]：让 OkHttp 直接请求首页并跟随 302。
 *    如果 CAS 会话有效，服务端会在回跳时 Set-Cookie，**无需用户介入**。
 * 2. 失败（落到 CAS 登录表单 / 需要重新输密码）才由 UI 引导打开内嵌 WebView。
 *
 * 与 CampusNet 的区别：学工没有独立的「换票接口」，会话就是首页跳转链上的
 * Set-Cookie，因此不需要票据桥，只要保证完整走完一次回跳即可。
 */
object StuitSession {

    private const val TAG = "StuitSession"

    /** 站点根 */
    val BASE: String = JnuHosts.STUIT

    /** 首页：CAS 的 service 就是它，也是内嵌 WebView 要打开的地址 */
    val HOME_URL: String = BASE + "/"

    /** 学工业务域名，用于判定 WebView 是否已落到业务站 */
    private const val STUIT_HOST = "stuit.jnu.edu.cn"

    /** Accept 头，单独抽出来避免 "*" + "/" + "*" 被 Kotlin 当成块注释开头 */
    private val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9," + "*" + "/" + "*;q=0.8"

    /** 最近一次确认会话可用的时间戳，供 UI 做「刚同步完就不用再探」的优化 */
    @Volatile
    var lastVerifiedAt: Long = 0L
        private set

    fun markVerified() {
        lastVerifiedAt = System.currentTimeMillis()
    }

    fun invalidate() {
        lastVerifiedAt = 0L
    }

    /** 刚同步过（10 分钟内）就认为会话可用，省一次探测往返 */
    fun recentlyVerified(): Boolean =
        System.currentTimeMillis() - lastVerifiedAt in 0 until 10 * 60 * 1000L

    // ------------------------- 判定 CAS 登录页 -------------------------

    /** 最终 URL 是否落在统一认证域（OkHttp 已跟随重定向） */
    fun isCasUrl(url: String?): Boolean {
        val u = url ?: return false
        return u.contains("/cas/login") ||
            u.contains("icas.jnu.edu.cn") ||
            u.contains("auth4.jnu.edu.cn") ||
            u.contains("auth7.jnu.edu.cn")
    }

    /**
     * 从 HTML 内容判断这是不是 CAS 登录页。
     *
     * 为什么要看内容而不只看 URL：CAS 有时会以 200 直接把登录表单渲染在
     * 原 URL 上（auth4/auth7 网关尤其如此），此时 URL 仍是 stuit 域，
     * 只看 URL 会把「未登录」误判成「已登录」，页面就一直显示空白表格。
     */
    fun looksLikeCasLogin(html: String): Boolean {
        if (html.isBlank()) return false
        val head = html.take(6000)
        val lower = head.lowercase()
        if (lower.contains("icas.jnu.edu.cn") || lower.contains("/cas/login")) return true
        if (lower.contains("id=\"fm1\"") || lower.contains("loginform")) return true
        val hasExecution = lower.contains("name=\"execution\"")
        val hasPassword = lower.contains("name=\"password\"") || lower.contains("type=\"password\"")
        if (hasExecution && hasPassword) return true
        return head.contains("统一身份认证") && (hasPassword || head.contains("登录"))
    }

    // ------------------------- 探测 / 建立会话 -------------------------

    /**
     * 只读探测：当前 stuit 会话是否可用。
     *
     * 请求 4 个页面里最轻的 basicInfo.jsp；有会话就能拿到 HTML，
     * 没会话则会被 302 到 CAS（或被渲染成登录表单）。
     */
    suspend fun isSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(BASE + "/student/module/basicinfo/basicInfo.jsp")
                .header("Accept", ACCEPT_HTML)
                .get()
                .build()

            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                val alive = !isCasUrl(finalUrl) &&
                    resp.code in 200..299 &&
                    html.isNotBlank() &&
                    !looksLikeCasLogin(html)
                Log.d(TAG, "probe -> " + resp.code + " " + finalUrl + " alive=" + alive)
                if (alive) markVerified()
                alive
            }
        }.getOrElse { t ->
            Log.w(TAG, "probe failed", t)
            false
        }
    }

    /**
     * 尝试用 OkHttp 自己走完首页跳转链来建立会话（无需用户介入）。
     *
     * 原理：GET 首页 -> OkHttp 自动跟随 302 -> 落到 icas -> 若 CAS 会话有效，
     * CAS 会 302 回 stuit 首页并带票据 -> stuit 校验后 Set-Cookie: JSESSIONID。
     * 整个过程不需要解析表单，CookieJar 会把沿途 Cookie 全部收下。
     *
     * @return true 表示会话已建立
     */
    suspend fun tryBootstrapWithHttp(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val req = Request.Builder()
                .url(HOME_URL)
                .header("Accept", ACCEPT_HTML)
                .get()
                .build()

            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                Log.d(TAG, "bootstrap -> " + resp.code + " " + finalUrl + " cookies=" + cookieNames())
                if (isCasUrl(finalUrl) || looksLikeCasLogin(html)) {
                    false
                } else {
                    resp.code in 200..299 && html.isNotBlank()
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
     * 统一入口：确保 stuit 会话可用。
     *
     * @param reuseRecent 刚同步过就跳过探测
     * @return false 表示需要用户在 WebView 里登录一次
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

    // ------------------------- WebView 协作（三步方案） -------------------------

    /*
     * ## 三步方案（Lead 架构决策，2026-10-22）
     *
     * 关键事实：
     * - icas 的 CASTGC / JSESSIONID 是 HttpOnly，**WebView 与 OkHttp 都拿不到**，
     *   所以任何「让 OkHttp 自己去走 CAS 跳转」的方案都必然失败
     *   （它没有 CASTGC，跳到 auth4 时没有登录态，只会一路 302 回 icas 登录页，
     *    表现为 ERR_TOO_MANY_REDIRECTS）。
     * - 但**业务域**（stuit）自己的会话 Cookie 通常不是 HttpOnly，
     *   WebView 的 getCookie() 可以读到。
     *
     * 因此正确顺序是：
     *   1. WebView 让用户真正登录一次（不尝试自动跳过）
     *   2. 登录后停在业务站页面时，把**业务域** Cookie 同步到 CookieJar
     *   3. OkHttp 带着这些 Cookie 直接请求业务接口 —— 不经过 CAS
     */

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
            Log.d(TAG, "seeded webview cookies: " + HttpClient.cookieJar().snapshot().size + " from jar")
        }.onFailure { Log.w(TAG, "seed webview cookies failed", it) }
    }

    /** 只打印 Cookie 的 name 列表，绝不打印值（值是敏感信息） */
    private fun cookieNameList(raw: String): String =
        raw.split(';')
            .mapNotNull { it.trim().substringBefore('=', "").trim().takeIf { n -> n.isNotEmpty() } }
            .joinToString(",")

    /**
     * 第 2 步：把 **业务域** 的 Cookie 从 WebView 同步到 OkHttp。
     *
     * 只同步目标业务站（stuit.jnu.edu.cn），不同步 icas / auth4 ——
     * 那两个域的 CASTGC 是 HttpOnly，本来就拿不到，硬同步只会污染 CookieJar。
     *
     * @param businessUrl WebView 当前停留的业务站地址，默认学工首页
     * @return 同步成功的 Cookie 条数；0 表示业务域 Cookie 也读不到
     */
    fun syncBusinessCookies(businessUrl: String = HOME_URL): Int {
        return runCatching {
            val cm = CookieManager.getInstance()
            cm.flush()

            val url = if (businessUrl.startsWith("http")) businessUrl else "https://" + businessUrl
            val host = runCatching { Uri.parse(url).host }.getOrNull()
                ?: return@runCatching 0

            // 只接受业务域，CAS 域直接跳过
            if (host.startsWith("icas.") || host.startsWith("auth4.") || host.startsWith("auth7.")) {
                Log.w(TAG, "syncBusinessCookies: skip CAS host " + host)
                return@runCatching 0
            }

            val raw = cm.getCookie(url).orEmpty()
            Log.d(TAG, "webview cookie for " + host + " = len=" + raw.length + " names=[" + cookieNameList(raw) + "]")

            if (raw.isBlank()) {
                Log.w(TAG, "business domain cookie is EMPTY — 这条路不通，需要换方案")
                return@runCatching 0
            }

            HttpClient.cookieJar().syncFromWebView(mapOf(host to raw))
            val count = raw.split(';').count { it.contains('=') }
            Log.d(TAG, "synced business cookies: " + count + " from " + host)
            count
        }.onFailure { Log.w(TAG, "syncBusinessCookies failed", it) }.getOrDefault(0)
    }

    /**
     * WebView 当前地址是否表示「已经跳出 CAS、回到学工页面」。
     *
     * 判定：host 属于 jnu.edu.cn，但不是 icas / auth4 / auth7 这些统一认证域。
     * 用它决定何时开始第 2 步（同步业务域 Cookie）。
     */
    fun isStuitPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host.orEmpty()
        // 必须是学工自己的域，不能是统一认证域或别的业务域
        if (!host.equals(STUIT_HOST, ignoreCase = true)) return false
        return u.path?.contains("cas/login") != true
    }

    /**
     * WebView 每次页面加载完成时调用的轻量收集（只做日志与兜底）。
     *
     * 真正的同步由 [syncBusinessCookies] 在确认落到业务站后执行；
     * 这里只把当前页面的业务域 Cookie 顺手记一笔，不阻塞 UI。
     */
    fun harvestWebViewCookies(url: String?) {
        if (url.isNullOrBlank() || !isStuitPage(url)) return
        runCatching {
            val cm = CookieManager.getInstance()
            cm.flush()
            val raw = cm.getCookie(url).orEmpty()
            Log.d(TAG, "page cookie for " + url + " = len=" + raw.length + " names=[" + cookieNameList(raw) + "]")
        }.onFailure { Log.w(TAG, "harvestWebViewCookies failed", it) }
    }

    /** 学工域当前已有哪些 cookie 名（排障用） */
    fun jarCookieNames(): List<String> = cookieNames()

    /**
     * 第 3 步：同步完业务 Cookie 后，直接用 OkHttp 试一次业务接口。
     *
     * 关键：这一步**不再走任何 CAS 跳转**，就是带着 CookieJar 里的 stuit Cookie
     * 直接请求 basicInfo.jsp。成功说明业务域会话已可用。
     */
    suspend fun probeBusinessWithOkHttp(): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val req = Request.Builder()
                .url(BASE + "/student/module/basicinfo/basicInfo.jsp")
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()

            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                val ok = !isCasUrl(finalUrl) &&
                    resp.code in 200..299 &&
                    html.isNotBlank() &&
                    !looksLikeCasLogin(html)
                Log.d(TAG, "okhttp probe = " + ok + " (code=" + resp.code + " url=" + finalUrl + ")")
                ok
            }
        }.getOrElse { t ->
            Log.w(TAG, "okhttp probe failed", t)
            false
        }

        if (ok) markVerified()
        ok
    }

    /** 学工域当前有哪些 cookie（排障用） */
    fun cookieNames(): List<String> = runCatching {
        HttpClient.cookieJar().snapshot()
            .filter { it.domain.contains("stuit.jnu.edu.cn") }
            .map { it.name }
    }.getOrDefault(emptyList())

    /** 是否已有会话 cookie（仅日志/排障用，不作判据） */
    fun hasSessionCookie(): Boolean = cookieNames().any { it.contains("JSESSIONID", ignoreCase = true) }
}