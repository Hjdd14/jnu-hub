package cn.jnu.hub.feature.jw

import android.util.Log
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 教务系统（jw.jnu.edu.cn / CampusSphere）会话。
 *
 * ## 实测的真实跳转链（2026-10-22 logcat 逐跳确认）
 *
 *     GET  https://jw.jnu.edu.cn/new/index.html            → 200（只是壳页面，不发 JSESSIONID）
 *     点「登录」或接口被拦截时：
 *      302 -> https://auth4.jnu.edu.cn/cas_auth/public/casLogin?verifyID=<hex>
 *      302 -> https://icas.jnu.edu.cn/cas/login?service=…auth4…
 *      （CAS 已登录则放行）
 *      302 -> auth4 …/doAuth?…
 *      302 -> http://jw.jnu.edu.cn/amp-auth-adapter/loginSuccess?sessionToken=<hex>
 *      200    jw 域 **这一步才 Set-Cookie: JSESSIONID=…** ← 会话真正建立
 *
 * ## 旧实现为什么必然失败
 *
 * 旧实现把「WebView 停在业务域」当成成功信号，然后立刻同步 cookie。
 * 但实测 new/index.html 自己返回 200 时 jw 域只有一个
 * JNU_AUTH_VERIFY_TOKEN 守卫 cookie，**没有 JSESSIONID**，
 * 于是同步-探测-失败-再同步，永远卡在「只有 JNU_AUTH_VERIFY_TOKEN」。
 *
 * ## 现在的判定标准
 *
 * **只看 jw 域的 JSESSIONID**（见 [isReadyMarker]），不看 URL、不看回调。
 * 由 [pollSession] 在调用方协程里主动轮询 WebView 的 CookieManager，
 * 出现 JSESSIONID 才同步给 OkHttp 并返回成功 —— 时序完全由我们自己控制。
 */
object JwSession {

    private const val TAG = "JwSession"

    /** 站点根（https） */
    val BASE: String = JnuHosts.JW

    val JW_HOST: String = JnuHosts.JW.substringAfter("://").substringBefore('/')

    /** 门户首页：WebView 引导页的起点 */
    val BOOTSTRAP_URL: String = JnuHosts.JW + "/new/index.html"

    /** 子应用索引页路径模板（appName 为 wdkb / cjcx / wdksap / xjxxbg / xywccx） */
    const val APP_PATH_TEMPLATE: String = "/jwapp/sys/%s/*default/index.do"

    /**
     * 门户首页：登录完成后的落地页，也是子应用入口的 Referer。
     */
    val PORTAL_URL: String get() = JnuHosts.JW + "/new/index.html"

    /**
     * 当前用户的 gid_（**运行时获取，严禁硬编码**）。
     *
     * ## 为什么不能写死
     *
     * gid_ 是**按账号绑定**的会话令牌：每个学生登录教务后拿到的值都不同。
     * 早期实现把某次抓包里的 gid_ 写成了常量，结果只有那一个账号能用，
     * 其他同学全部 403。
     *
     * ## 获取方式
     *
     * WebView 打开任意教务页面时，页面入口 URL 上会带 gid_（例如
     * /jwapp/sys/wdkb/ 加 "*" 加 default/index.do?…&gid_=…）。
     * 我们从 WebView 的导航 URL 里抓下来，经 [JwGidStore] 持久化，
     * 之后所有子应用的 Referer 复用它。
     *
     * 拿不到时 [appIndexUrl] 会退回**不含 gid_ 的地址**，业务接口随即 403，
     * 由 JwApi 触发一次刷新后重试 —— 不是硬失败。
     */
    @Volatile
    private var dynamicGid: String? = null

    /** 最近一次在 WebView 里看到的、带 gid_ 的业务页完整 URL（最终兜底 Referer） */
    @Volatile
    private var lastAppUrlWithGid: String? = null

    /** 匹配 URL 上的 gid_ 参数 */
    private val GID_REGEX = Regex("gid_=([A-Za-z0-9+/=%._-]+)")

    /** 当前已获取到的 gid_（排障/日志用，不要用于拼接） */
    val currentGid: String? get() = dynamicGid ?: JwGidStore.load()

    /**
     * 记录一次 WebView 导航 URL，并从中提取 gid_。
     *
     * 由登录弹层与隐藏 WebView 的 onPageStarted / onPageFinished 调用。
     * @return 是否新获取到了 gid_
     */
    fun captureGidFromUrl(url: String?): Boolean {
        val u = url ?: return false
        if (!u.contains(JW_HOST)) return false
        val m = GID_REGEX.find(u) ?: return false
        val gid = m.groupValues.getOrNull(1).orEmpty()
        if (gid.isBlank()) return false
        val changed = gid != dynamicGid
        dynamicGid = gid
        if (u.contains("/jwapp/sys/")) lastAppUrlWithGid = u
        // 每次都保存（不只是 changed）：避免「内存里已有旧值」时新账号的值写不进去
        JwGidStore.save(gid)
        Log.i(
            TAG,
            "captured gid_ = " + gid.take(20) + "… (" + gid.length + " chars) changed=" + changed,
        )
        return changed
    }

    /**
     * 主动为当前账号获取一次 gid_（隐藏 WebView 里点开应用，见 [JwGidProbe]）。
     *
     * 失败不会抛异常：返回 false 表示没拿到，调用方按「没有 gid_」继续，
     * 接口会 403 并给出可操作的提示。
     */
    suspend fun refreshGid(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        if (JwGidProbe.refresh(context)) {
            Log.i(TAG, "gid_ refreshed = " + currentGid?.take(20) + "…")
            true
        } else {
            Log.w(TAG, "gid_ refresh failed")
            false
        }
    }

    /** 冷启动时把已保存的 gid_ 注入内存 */
    fun preloadGid(): String? {
        val saved = dynamicGid ?: JwGidStore.load()
        if (!saved.isNullOrBlank()) dynamicGid = saved
        return dynamicGid
    }

    /** 清除 gid_（退出登录时调用） */
    fun clearGid() {
        dynamicGid = null
        lastAppUrlWithGid = null
        JwGidStore.clear()
    }

    /**
     * 构造子应用索引页地址，等价于用户在门户里点开某个应用：
     *
 *     https://jw.jnu.edu.cn/jwapp/sys/{appName}/"*"default/index.do
     *       ?t_s={毫秒时间戳}&amp_sec_version_=1&gid_={GID}&EMAP_LANG=zh&THEME=cherry
     *
     * @param appName 子应用名：wdkb 课表 / cjcx 成绩 / wdksap 考试 / xjxxbg 学籍 / xywccx 学分
     */
    fun appIndexUrl(appName: String, timestamp: Long = System.currentTimeMillis()): String {
        val path = String.format(APP_PATH_TEMPLATE, appName)
        val base = JnuHosts.JW + path +
            "?t_s=" + timestamp +
            "&amp_sec_version_=1" +
            "&EMAP_LANG=zh" +
            "&THEME=cherry"
        // gid_ 是本账号专属的；还没拿到时**绝不**用别人的值兜底，
        // 宁可先不带（接口会 403，再由 JwApi 触发刷新后重试）。
        val gid = preloadGid()
        return if (gid.isNullOrBlank()) base else base + "&gid_=" + gid
    }

    /**
     * 最近一次在 WebView 里看到的、带 gid_ 的业务页 URL（最终兜底 Referer）。
     * 若 gid_ 提取失败，但 WebView 确实打开过子应用页，用它当 Referer 也能过校验。
     */
    fun lastAppUrl(): String? = lastAppUrlWithGid

    /** 课表子应用入口页（带 gid_） */
    fun wdkbIndexUrl(): String = appIndexUrl("wdkb")

    /** 教务自己的登录入口：会触发 jnu_authentication/public/verify 换票 */
    private const val LOGIN_PATH = "/login?service="

    /**
     * 构造「从登录入口进、登录完成后落到目标页」的 URL。
     *
     * ## 为什么不能直接打开业务页
     *
     * 浏览器成功登录的 HAR（209 条）显示完整链路是：
     *     1. icas /cas/login?service=auth4…            → 302
     *     2. auth4 /cas_auth/public/casLogin?ticket=ST  → 302
     *     3. auth4 /cas_auth/public/casLogin?verifyID=  → 302
     *     4. **jw /jnu_authentication/public/verify?verifyID=xxx**   ← 关键一步
     *     5. jw /login?service=…/new/index.html         → 302
     *     6. jw /amp-auth-adapter/login?service=…       → 302
     *     7. icas /cas/login?service=…/loginSuccess     → 302
     *     8. jw /amp-auth-adapter/loginSuccess?sessionToken=…&ticket=ST-… (307)
     *     9. jw /login?service=…&ticket=…               → 302
     *    10. jw /login?service=…                        → 302
     *    11. jw /new/index.html                         → 200，这才是真登录
     *
     * 直接 loadUrl 到课表子应用首页（/jwapp/sys/wdkb/ + "*" + default/index.do）
     * 会跳过第 4 步的 jnu_authentication 校验，jw 认为客户端没完成认证 → **403**。
     * 所以必须从 /login?service=<目标页> 进，让 jw 自己把整条链走完。
     */
    fun loginEntryUrl(targetPath: String): String {
        val target = JnuHosts.JW + targetPath
        val encoded = android.net.Uri.encode(target)
        return JnuHosts.JW + LOGIN_PATH + encoded
    }

    /** WebView 登录弹层入口：从登录链进，落到门户首页 */
    val WEBVIEW_ENTRY_URL: String
        get() = loginEntryUrl("/new/index.html")

    /**
     * 隐藏 WebView 取数入口：登录链 → 课表子应用入口（带 gid_）。
     *
     * service 参数里放的是**带 gid_ 的完整 URL**，这样登录完成后 jw 会
     * 直接把浏览器送回「带 gid_ 的课表页」，而不是一个裸路径。
     */
    val FETCH_ENTRY_URL: String
        get() = JnuHosts.JW + LOGIN_PATH + android.net.Uri.encode(wdkbIndexUrl())

    /** 教务自己的「我是谁」探测地址（不依赖学号） */
    private const val WHOAMI_PATH = "/jwapp/sys/wdkb/modules/xskcb/cxxsjbxx.do"

    /** AMP 认证适配器落点特征 */
    private const val AMP_SUCCESS_MARK = "/amp-auth-adapter/loginSuccess"

    /**
     * jw 域「会话已建立」的 cookie 名（任一出现即算）。
     *
     * 实测（2026-10-22，模拟器 WebView 110）：CampusSphere 走
     *   GET /jwapp/sys/wdkb/index.do
     *     → /amp-auth-adapter/login → http://…/amp-auth-adapter/loginSuccess?ticket=ST-…
     *     → /jwapp/sys/wdkb/index.do
     * 换票成功后 jw 域下发的是 **MOD_AMP_AUTH**，全程**没有** JSESSIONID。
     * 所以只认 JSESSIONID 会把「其实已经换票成功」判成失败。
     *
     * 两个都认：MOD_AMP_AUTH 是金智 AMP 网关的票据，JSESSIONID 是部分部署下的会话 id。
     */
    private val SESSION_MARKERS = listOf("MOD_AMP_AUTH", "JSESSIONID", "SESSION", "ROUTE")

    /** 轮询节奏：每 [POLL_INTERVAL_MS] 查一次，共 [POLL_ATTEMPTS] 次 */
    const val POLL_INTERVAL_MS = 300L
    const val POLL_ATTEMPTS = 60

    /** 轮询总时长（秒），给 UI 文案用 */
    val POLL_TOTAL_SECONDS: Int = ((POLL_INTERVAL_MS * POLL_ATTEMPTS) / 1000).toInt()

    /**
     * 浏览器 Accept 头。
     *
     * 注意：**必须拼接，不能写字符串字面量**。
     * 字面量里的 "*" + "/" + "*" 会让 Kotlin 词法器把 "/"+"*" 认成块注释开头，
     * 从那里往后整段代码都被吞进注释，报出一堆莫名其妙的
     * "Expecting a top level declaration"。
     */
    private const val ANY_TYPE = "*" + "/" + "*"
    private const val ACCEPT_JSON = "application/json, text/javascript, " + ANY_TYPE + "; q=0.01"
    private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9," + ANY_TYPE + ";q=0.8"

    private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    // ———————————————————————————— 会话探测 ————————————————————————————

    /**
     * jw 域当前是否已有可用会话（只读探测，不做换票）。
     * 判据：请求 whoami 接口，返回 JSON 说明有会话，被 302 到 CAS 说明没有。
     */
    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        probeWhoAmI()
    }

    private fun probeWhoAmI(): Boolean {
        val req = Request.Builder()
            .url(BASE + WHOAMI_PATH)
            .post("XH=".toRequestBody(FORM))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", ACCEPT_JSON)
            .build()
        return runCatching {
            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val text = resp.body?.string().orEmpty().trimStart()
                Log.d(TAG, "whoami -> " + resp.code + " " + finalUrl + " | head=" + text.take(48))
                if (isCasLanding(finalUrl)) false else text.startsWith("{")
            }
        }.getOrDefault(false)
    }

    /** 判断一个最终 URL 是否落在 CAS 登录页 */
    fun isCasLanding(url: String?): Boolean {
        val u = url ?: return false
        return u.contains("/cas/login") ||
            u.contains("icas.jnu.edu.cn") ||
            u.contains("auth4.jnu.edu.cn") ||
            u.contains("auth7.jnu.edu.cn")
    }

    /**
     * 尝试用 OkHttp 静默探测一次会话。
     *
     * 只在**会话其实已经建立**时才有意义：此时直接返回 true，页面秒开。
     * 没有会话时 OkHttp 侧一定绕不回来（CASTGC 是 HttpOnly，CookieJar 读不到），
     * 必须交给 WebView 走完整链路。
     *
     * 注意这里**不主动 GET new/index.html**：那一步会换来一个全新的
     * JNU_AUTH_VERIFY_TOKEN 落进 CookieJar，和 WebView 刚换到的
     * MOD_AMP_AUTH 对不上，实测会让后续接口直接 403。
     */
    suspend fun tryBootstrapWithHttp(): Boolean = withContext(Dispatchers.IO) {
        val ok = probeWhoAmI()
        Log.d(TAG, "silent probe = " + ok + ", jw cookies=" + jwCookieNames())
        ok
    }

    // ———————————————————————————— Cookie 轮询（核心） ————————————————————————————

    /**
     * jw 域是否已有**真正的**会话 cookie。
     *
     * 判据只有一个：JSESSIONID。
     * JNU_AUTH_VERIFY_TOKEN 是网关守卫 cookie，任何一次访问 jw 都会有，
     * **不能**当作登录成功的证据（旧实现正是栽在这里）。
     */
    fun isReadyMarker(rawCookieHeader: String?): Boolean {
        val raw = rawCookieHeader ?: return false
        if (raw.isBlank()) return false
        return raw.split(';').any { pair ->
            val name = pair.trim().substringBefore('=').trim()
            name.isNotEmpty() && SESSION_MARKERS.any { it.equals(name, ignoreCase = true) }
        }
    }

    /** jw 域 cookie 头里是否含 MOD_AMP_AUTH（金智 AMP 票据） */
    fun hasModAmpAuth(rawCookieHeader: String?): Boolean {
        val raw = rawCookieHeader ?: return false
        return raw.split(';').any { pair ->
            pair.trim().substringBefore('=').trim().equals("MOD_AMP_AUTH", ignoreCase = true)
        }
    }

    /**
     * 读 WebView 的 CookieManager 里 jw 域的 cookie 头。
     *
     * **http 与 https 是两个不同 origin**：jw 的 loginSuccess 是 http 开头，
     * WebView 停在 http 上时 getCookie("https://…") 读不到，所以两边都查。
     */
    fun readWebViewCookies(): List<Pair<String, String>> {
        val cm = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        return runCatching {
            cm.flush()
            listOf("https://$JW_HOST/", "http://$JW_HOST/").mapNotNull { probe ->
                val raw = runCatching { cm.getCookie(probe) }.getOrNull()
                if (raw.isNullOrBlank()) null else probe to raw
            }
        }.getOrDefault(emptyList())
    }

    /**
     * jw 会话是否已建立。
     *
     * 两个来源都算数：
     * 1. 共享 CookieJar 里已有 jw 域 JSESSIONID（OkHttp 侧建立的）
     * 2. WebView 的 CookieManager 里已有 jw 域 JSESSIONID（浏览器侧建立的）
     */
    fun hasJwSessionCookie(): Boolean {
        if (jwCookieNames().any { name -> SESSION_MARKERS.any { it.equals(name, ignoreCase = true) } }) return true
        return readWebViewCookies().any { (_, raw) -> isReadyMarker(raw) }
    }

    /** jw 域已有哪些 cookie（排障用，只打名字不打值） */
    fun jwCookieNames(): List<String> = runCatching {
        HttpClient.cookieJar().snapshot()
            .filter { it.domain.contains(JW_HOST) }
            .map { it.name }
            .distinct()
    }.getOrDefault(emptyList())

    /** WebView 侧 jw 域 cookie 的名字列表（排障用） */
    fun webViewCookieNames(): List<String> = readWebViewCookies()
        .flatMap { (_, raw) -> raw.split(';').map { it.trim().substringBefore('=') } }
        .filter { it.isNotBlank() }
        .distinct()

    /**
     * **核心**：主动轮询 WebView 的 CookieManager，直到出现 jw 域 JSESSIONID。
     *
     * 每次读到合格 cookie 就立刻同步给共享 CookieJar 并返回 true，
     * 调用方拿到 true 后直接拉数据即可，不需要再做 URL 判定或 WebViewClient 回调。
     *
     * @param onTick 每次轮询回调一次，参数是已等待的毫秒数（UI 用来做进度提示）
     * @return true = 约定时间内拿到了 JSESSIONID
     */
    suspend fun pollSession(onTick: (elapsedMs: Long) -> Unit = {}): Boolean {
        val started = System.currentTimeMillis()
        repeat(POLL_ATTEMPTS) { attempt ->
            val elapsed = System.currentTimeMillis() - started
            onTick(elapsed)

            val cookies = readWebViewCookies()
            val ready = cookies.firstOrNull { (_, raw) -> isReadyMarker(raw) }
            if (ready != null) {
                val (origin, raw) = ready
                Log.i(TAG, "jw 会话 cookie 出现于 " + origin + " @ " + elapsed + "ms, " + briefCookies(raw))
                syncBusinessCookies(cookies)
                logJarCookies("after sync")
                return true
            }
            if (attempt % 10 == 0) {
                Log.d(TAG, "poll #" + attempt + " " + elapsed + "ms, jw webview cookies=" + webViewCookieNames())
            }
            delay(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "poll timeout after " + POLL_TOTAL_SECONDS + "s, webview=" + webViewCookieNames() + ", " + briefCookies(readWebViewCookies().joinToString(";") { it.second }))
        // 超时前最后再同步一次，避免「刚好晚 1 拍」的情况白白丢掉
        syncBusinessCookies(readWebViewCookies())
        return false
    }

    /**
     * 把已读到的 jw 域 cookie 同步进共享 CookieJar。
     * 只同步 jw 业务域，**不要试图同步 icas 的 CASTGC**：它是 HttpOnly，
     * getCookie 读不到；就算读到了也不该搬，它是 CAS 的凭证。
     */
    fun syncBusinessCookies(cookies: List<Pair<String, String>> = readWebViewCookies()): Int {
        if (cookies.isEmpty()) return 0
        val merged = LinkedHashMap<String, String>()
        val names = LinkedHashSet<String>()
        cookies.forEach { (_, raw) ->
            raw.split(';').forEach { pair ->
                val n = pair.trim().substringBefore('=').trim()
                if (n.isNotBlank()) names.add(n)
            }
            // http / https 两个 origin 的 cookie 合并成一份，交给 CookieJar 按域落盘
            merged[JW_HOST] = (merged[JW_HOST]?.plus("; ") ?: "") + raw
        }
        if (names.isEmpty()) return 0
        runCatching { HttpClient.cookieJar().syncFromWebView(merged) }
            .onFailure { Log.w(TAG, "sync cookies failed", it) }
        val ready = isReadyMarker(merged[JW_HOST])
        Log.d(TAG, "synced jw cookies " + names + " (" + names.size + "), JSESSIONID=" + ready)
        return names.size
    }

    /**
     * 把 WebView 当前的 jw 域 Cookie 收割进共享 CookieJar。
     *
     * 用户每在 WebView 里点一次子应用，教务就会刷新 route / JSESSIONID；
     * 不把这些新值同步给 OkHttp，后续接口调用仍会 403。
     * 返回同步到的 cookie 名数量，0 表示 WebView 里还没有 jw 会话。
     */
    fun harvestWebViewCookies(): Int = runCatching {
        syncBusinessCookies()
    }.getOrElse {
        Log.w(TAG, "harvest webview cookies failed", it)
        0
    }

    /**
     * 把 OkHttp 侧的 CAS cookie 推给 WebView，
     * 让弹层打开时能直接走 CAS 放行，不用再输一次密码。
     */
    fun seedWebViewCookies() {
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            HttpClient.cookieJar().snapshot()
                .filter { it.domain.endsWith("jnu.edu.cn") }
                .forEach { c ->
                    cm.setCookie("https://" + c.domain + "/", c.name + "=" + c.value + "; Path=/")
                    cm.setCookie("http://" + c.domain + "/", c.name + "=" + c.value + "; Path=/")
                }
            cm.flush()
            Log.d(TAG, "seeded webview cookies for jnu.edu.cn")
        }.onFailure { Log.w(TAG, "seed webview cookies failed", it) }
    }

    /** 只打印 cookie 的 name 列表和总长度，不打印值（值是凭证，不能进日志） */
    private fun briefCookies(raw: String): String {
        val names = raw.split(';').map { it.trim().substringBefore('=') }.filter { it.isNotBlank() }
        return names.size.toString() + "个/" + raw.length + "B " + names
    }

    /**
     * 试一次业务接口，验证 cookie 是否真的可用。
     * 对应「同步后立即用 OkHttp 试一次接口」这一步。
     */
    suspend fun probeSession(): Boolean = withContext(Dispatchers.IO) {
        val ok = probeWhoAmI()
        Log.d(TAG, "okhttp probe = " + ok + ", jw cookies=" + jwCookieNames())
        ok
    }

    /** jw 业务域根地址 */
    fun businessUrl(): String = BASE + "/"

    /** 该 URL 是否属于 jw 业务域（而不是 CAS / auth4） */
    fun isBusinessUrl(url: String?): Boolean {
        val u = url ?: return false
        if (!u.contains(JW_HOST)) return false
        return !isCasLanding(u) && !u.contains("auth4.jnu.edu.cn")
    }

    /** 学号：优先本地记住的，其次尝试从 whoami 接口解析 */
    suspend fun resolveStudentId(): String? = withContext(Dispatchers.IO) {
        JwPrefs.studentId.takeIf { it.isNotBlank() }?.let { return@withContext it }
        runCatching { JwApi.tryResolveXh() }.getOrNull()?.let { resolved ->
            JwPrefs.studentId = resolved
            resolved
        }
    }

    /**
     * WebView 侧 jw 域当前已有的登录态标记是否足够（排障与兜底判定用）。
     *
     * 实测（2026-10-22，模拟器 WebView 110）：
     *   CAS 跳转链在 WebView 里是**完整走通**的：
     *     /jwapp/sys/wdkb/index.do → amp-auth-adapter/login → loginSuccess?ticket=ST-…
     *     → /jwapp/sys/wdkb/index.do（此时 jw 域有 JNU_AUTH_VERIFY_TOKEN + CASTGC + MOD_AMP_AUTH）
     *   但 jw 域**始终不下发 JSESSIONID**，紧接着访问 jwapp 接口返回 403。
     *
     * 所以「换票成功」和「jwapp 会话可用」是两件事：
     * 前者看 MOD_AMP_AUTH，后者只能看接口是否返回 JSON。
     */
    fun webViewAuthMarkers(): List<String> = webViewCookieNames()

    /** WebView 是否已经换到了票（MOD_AMP_AUTH / CASTGC / JSESSIONID 任一存在） */
    fun hasWebViewTicket(): Boolean {
        val names = webViewCookieNames().map { it.lowercase() }
        return names.any { it == "mod_amp_auth" || it == "castgc" || it == "jsessionid" }
    }

    /** 排障：打印 OkHttp 侧 jw 域 cookie 的名字列表（不打印值） */
    fun logJarCookies(tag: String) {
        runCatching {
            val jar = HttpClient.cookieJar().snapshot()
                .filter { it.domain.contains(JW_HOST) }
                .map { if (it.httpOnly) it.name + "(httpOnly)" else it.name }
            Log.d(TAG, tag + " jar jw cookies = " + jar)
            Log.d(TAG, tag + " webview jw cookies = " + webViewCookieNames())
        }
    }
}