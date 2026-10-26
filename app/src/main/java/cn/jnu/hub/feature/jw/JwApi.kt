
package cn.jnu.hub.feature.jw

import android.util.Log
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 教务系统 CampusSphere 接口客户端。
 *
 * 约定（见 docs/06_教务与学工接口实测.md 第一节）：
 * - 所有接口均为 POST + application/x-www-form-urlencoded; charset=UTF-8
 * - 响应包裹为 datas.[action].rows 结构，code 为字符串 "0"
 * - 少数接口（个人培养方案 / 学分完成情况）直接把 rows 放在顶层，需两种都兼容
 * - 会话过期时会跟随 302 落到 CAS 登录页，此时抛出 [JwException.SessionExpired]
 */
object JwApi {

    private val BASE: String = JnuHosts.JW
    private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    private const val ANY_TYPE = "*" + "/" + "*"
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** 从 /jwapp/sys/{appName}/… 里取出 appName */
    private val APP_NAME_REGEX = Regex("/jwapp/sys/([^/?]+)")

    private const val MAX_REDIRECTS = 8

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ———————————————————————————— 底层 ————————————————————————————

    /**
     * Application Context，供 [JwJsFetcher] 创建隐藏 WebView。
     * 由 JwContext（页面首次组合时）与 JnuHubApp（冷启动兜底）注入。
     */
    @Volatile
    private var appContext: android.content.Context? = null

    /**
     * OkHttp 走不通时是否允许退回 WebView 内 fetch。
     *
     * 之所以保留开关：隐藏 WebView 开销较大，批量拉数据时若确定 OkHttp 可用就不必退回。
     */
    @Volatile
    var jsFallbackEnabled: Boolean = true

    /**
     * 「OkHttp 对 jwapp 恒 403」这个结论的会话级记忆。
     *
     * 一旦某次请求被 403 拒绝，后续请求就不再浪费 10 秒超时去试 OkHttp，
     * 直接走 [JwDataHarvester]。
     */
    @Volatile
    private var okHttpAlwaysRejected: Boolean = false

    /** 连续确认「OkHttp 恒 403」多少次后写盘 */
    private const val PERSIST_THRESHOLD = 3

    private const val OKHTTP_PREFS = "jnu_jw_okhttp"
    private const val KEY_OKHTTP_REJECTED = "always_rejected"

    /** 由宿主注入 Context，重复调用无副作用 */
    fun install(context: android.content.Context) {
        appContext = context.applicationContext
        // 冷启动恢复「OkHttp 恒 403」的结论，省掉首次白等
        runCatching {
            val hit = appContext
                ?.getSharedPreferences(OKHTTP_PREFS, android.content.Context.MODE_PRIVATE)
                ?.getBoolean(KEY_OKHTTP_REJECTED, false) ?: false
            if (hit) {
                okHttpAlwaysRejected = true
                consecutiveHarvestHits = PERSIST_THRESHOLD
                Log.i("JwApi", "restored okhttp rejected flag from prefs")
            }
        }
    }

    /**
     * 教务接口统一入口。
     *
     * 先用 OkHttp（快、可直接复用 CookieJar）；被 403/会话过期拒绝时，
     * 退回 [JwJsFetcher] 用隐藏 WebView 发同源请求。
     *
     * 为什么要这条兜底：实测 jw 域 cookie 已齐（CASTGC + MOD_AMP_AUTH + asessionid），
     * OkHttp 仍然 403（响应还带 Set-Cookie），说明服务端认可的是浏览器会话而不是 cookie 本身。
     */
    private suspend fun post(path: String, body: String): String {
        var gidRefreshedInThisCall = false
        return try {
            postViaOkHttp(path, body)
        } catch (t: JwException) {
            // 403 有时是因为 gid_ 还没取到/已失效：先刷新一次再重试，别急着报错
            // 注意：不能只在「没有 gid_」时才刷新 —— 旧值（别的账号/过期）同样会导致 403，
            // 所以只要被拒就先刷新一次再重试，刷新本身有超时保护，不会拖太久。
            if (t is JwException.SessionExpired && appContext != null && !gidRefreshedInThisCall &&
                JwSession.refreshGid(appContext!!)
            ) {
                gidRefreshedInThisCall = true
                android.util.Log.i("JwApi", path + " -> gid_ refreshed, retry OkHttp")
                val retried = runCatching { postViaOkHttp(path, body) }.getOrNull()
                if (retried != null) return retried
            }
            val ctx = appContext
            if (!jsFallbackEnabled || ctx == null || t !is JwException.SessionExpired) throw t
            android.util.Log.w("JwApi", path + " -> okhttp rejected (" + t.userMessage + "), fallback to webview fetch")
            val text = try {
                JwJsFetcher.fetch(ctx, path, body)
            } catch (js: Throwable) {
                android.util.Log.w("JwApi", path + " -> webview fetch failed: " + js.message)
                // 两条路都被拒：把「无权限」这个结论带上去，让 UI 给出可操作的降级提示
                if (js is JwException.SessionExpired) throw JwPermissionDeniedException()
                throw t
            }
            val trimmed = text.trimStart()
            if (trimmed.isEmpty()) throw JwException.Parse("服务器返回了空内容")
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                throw JwException.SessionExpired
            }
            android.util.Log.i("JwApi", path + " -> recovered via webview fetch (" + text.length + " chars)")
            text
        }
    }

    /**
     * 先走常规 [post]；被拒且环境允许时，改用 [JwDataHarvester] 在页面里取数。
     *
     * @param marker 用于在页面请求里匹配目标接口（例如 "xskcb"）
     */
    private suspend fun postOrHarvest(
        path: String,
        body: String,
        marker: String,
        appLabel: String = DEFAULT_APP_LABEL,
        /** 子应用名，用于在 WebView 里直接构造入口（比点击门户可靠得多） */
        appPath: String = "",
    ): String {
        val ctx = appContext ?: return post(path, body)

        // 已知教务对 OkHttp 恒 403：一旦确认过就直接走 harvester，
        // 省掉每次 10 秒的超时等待（首次加载从 ~25s 降到 5s 内）。
        if (okHttpAlwaysRejected) {
            // 标志已置位：每成功取一次数，就相当于又一次确认「OkHttp 确实不可用」，
            // 累计到阈值后写盘，让冷启动也不用再白试一次 OkHttp。
            val text = harvestOrThrow(ctx, path, body, marker, appLabel, appPath)
            if (consecutiveHarvestHits < PERSIST_THRESHOLD) {
                consecutiveHarvestHits++
                Log.i("JwApi", "harvest confirm #" + consecutiveHarvestHits + " marker=" + marker)
                if (consecutiveHarvestHits >= PERSIST_THRESHOLD) persistRejectedFlag()
            }
            return text
        }

        return try {
            // 只探一次 OkHttp 是否可用：这里**不能调 post()**，
            // 因为 post() 内部还有「刷新 gid 重试 + JwJsFetcher 兜底」两条重链，
            // 每条都可能等十几秒，加起来才是首次 50 秒的真凶。
            val text = postViaOkHttp(path, body)
            Log.i("JwApi", path + " -> okhttp OK")
            text
        } catch (t: JwException) {
            if (t is JwException.SessionExpired) {
                okHttpAlwaysRejected = true
                consecutiveHarvestHits++
                Log.i(
                    "JwApi",
                    "jw rejects OkHttp (403) -> switch to harvester for this session" +
                        " (hits=" + consecutiveHarvestHits + ")",
                )
                // 连续多次确认后持久化，冷启动也不用再等一次
                if (consecutiveHarvestHits >= PERSIST_THRESHOLD) persistRejectedFlag()
            }
            Log.w("JwApi", path + " -> " + t.userMessage + ", trying JwDataHarvester(" + marker + ")")
            harvestOrThrow(ctx, path, body, marker, appLabel, appPath)
        }
    }

    /** 标记「OkHttp 恒 403」已确认的会话内计数（用于决定是否持久化） */
    @Volatile
    private var consecutiveHarvestHits: Int = 0

    /**
     * UI「重新探测 OkHttp」调用：清掉标志与持久化，下一次请求会重新试 OkHttp。
     *
     * 场景：教务哪天恢复正常了，不必重装 App 才能切回来。
     */
    fun resetOkHttpRejectedFlag() {
        okHttpAlwaysRejected = false
        consecutiveHarvestHits = 0
        runCatching {
            appContext?.getSharedPreferences(OKHTTP_PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_OKHTTP_REJECTED)?.apply()
        }
        Log.i("JwApi", "okhttp rejected flag reset")
    }

    /** 把「OkHttp 恒 403」写盘，冷启动直接走 harvester */
    private fun persistRejectedFlag() {
        runCatching {
            appContext?.getSharedPreferences(OKHTTP_PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_OKHTTP_REJECTED, true)?.apply()
            Log.i("JwApi", "persist okhttp rejected=true")
        }
    }

    /** 走页面取数；失败时抛出【harvest 的异常】而不是 OkHttp 的，便于上层区分 */
    private suspend fun harvestOrThrow(
        ctx: android.content.Context,
        path: String,
        body: String,
        marker: String,
        appLabel: String,
        appPath: String,
    ): String = try {
        JwDataHarvester.harvest(ctx, marker, path, body, appLabel, appPath)
    } catch (h: Throwable) {
        android.util.Log.w("JwApi", "harvest failed for " + marker + ": " + h.message)
        throw h as? JwException ?: JwException.Network(h.message ?: "教务取数失败")
    }

    private suspend fun postViaOkHttp(path: String, body: String): String = withContext(Dispatchers.IO) {
        JwSession.logJarCookies(path.substringAfterLast('/'))
        val request = Request.Builder()
            .url(BASE + path)
            .post(body.toRequestBody(FORM))
            .header("Accept", "application/json, text/javascript, " + ANY_TYPE + "; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", BASE)
            // 教务的 jwapp 业务接口会解析 Referer 里的 gid_ 做二次校验：
            // 裸路径 → 403，带 t_s + gid_ 的完整子应用地址 → 200（实测）。
            // 各子应用还要用**自己的** appName，否则同样会被拒。
            .header("Referer", refererFor(path))
            .build()

        // 排障：看网关取票入口是否能换到带 gid_ 的地址
        if (path.endsWith("dqxnxq.do")) probeQxgl(path)

        // 手动跟随重定向：否则 OkHttp 会把 302→CAS 页面的 JSON 响应体当成数据，
        // 永远拿不到「被踢回登录页」这个信号。
        val executed = try {
            executeFollowing(request)
        } catch (t: Throwable) {
            throw JwException.Network(t.message ?: "网络连接失败")
        }
        val (resp, finalUrl) = executed
        resp.use {
            if (JwSession.isCasLanding(finalUrl)) throw JwException.SessionExpired

            val text = try {
                it.body?.string().orEmpty()
            } catch (t: Throwable) {
                throw JwException.Network(t.message ?: "读取响应失败")
            }
            val trimmed = text.trimStart()

            // 网关/应用层拒绝（403 / 401）时返回的是错误页，不是数据
            if (it.code !in 200..299) {
                JwSession.logJarCookies("http " + it.code + " ")
                android.util.Log.w(
                    "JwApi",
                    path + " -> " + it.code + " " + finalUrl +
                        " | setCookie=" + it.headers("Set-Cookie").joinToString() +
                        " | body=" + text.take(200).replace("\n", " "),
                )
                if (it.code == 403 || it.code == 401) throw JwException.SessionExpired
                throw JwException.Http(it.code)
            }
            if (trimmed.isEmpty()) throw JwException.Parse("服务器返回了空内容")
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                // 返回 HTML 基本只有一种可能：被踢回登录页
                throw JwException.SessionExpired
            }
            text
        }
    }

    /**
     * 一次性诊断（排障用，稳定后可删）：探测 jwapp 网关鉴权接口。
     *
     * HAR 里每条 jwapp 业务请求前都会先请求
     *     /jwapp/sys/funauthapp/api/getAppConfig/{app}-{appId}.do
     * 它是网关的「拿票/鉴权」接口。如果它返回 JSON 说明网关放行，
     * 后续 403 就纯属应用层问题；如果它自身也 403，说明网关就没给票。
     */
    private fun debugProbeGateway() {
        val candidates = listOf(
            "/jwapp/sys/funauthapp/api/getAppConfig/wdkb-4770397878132218.do",
            "/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do",
        )
        candidates.forEach { path ->
            runCatching {
                val req = Request.Builder()
                    .url(BASE + (if (path.contains("/modules/")) path else path + "?v=" + System.currentTimeMillis()))
                    .get()
                    .header("Accept", "application/json, text/javascript, " + ANY_TYPE + "; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", BASE + "/jwapp/sys/wdkb/index.do")
                    .build()
                HttpClient.get().newCall(req).execute().use { resp ->
                    val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    android.util.Log.w(
                        "JwProbe",
                        path + " -> " + resp.code + " | " + body.take(180).replace("\n", " "),
                    )
                }
            }.onFailure { android.util.Log.w("JwProbe", path + " failed: " + it.message) }
        }
    }
    /**
     * 按接口路径推导该用哪个子应用的页面当 Referer。
     *
     *     路径                                    → appName              → Referer
     *     /jwapp/sys/wdkb/modules/jshkcb/….do      wdkb                  课表子应用页
     *     /jwapp/sys/cjcx/modules/cjcx/xscjcx.do   cjcx                  成绩子应用页
     *     /jwapp/sys/studentWdksapApp/…/….do       studentWdksapApp     考试子应用页
     *     /jwapp/sys/xywccx/modules/xywccx/….do    xywccx                学分
     *     /jwapp/sys/xjxxbg/modules/xsxxbgsq/….do  xjxxbg                学籍
     *
     * 推导不出来时退回课表页 —— 它是目前唯一 100% 验证过能过校验的 Referer。
     */
    private fun refererFor(path: String): String {
        val appName = APP_NAME_REGEX.find(path)?.groupValues?.getOrNull(1)
        return if (appName.isNullOrBlank()) JwSession.wdkbIndexUrl()
        else JwSession.appIndexUrl(appName)
    }


    /**
     * 排障：请求网关取票入口 qxgl.do，看它是否 302 到带 gid_ 的子应用地址。
     *
     * 依据：门户「最近使用」列表里每个应用都带 authUrl = /sys/funauthapp/qxgl.do?appName=...，
     * 这是浏览器点应用时真正走的那一跳。
     */
    /**
     * 验证「authUrl 纯 HTTP 链路」是否可行。
     *
     * 依据：门户 recUseServiceList.json 给每个应用下发了 authUrl
     * （/sys/funauthapp/qxgl.do?min=1&appName=xxx&appId=xxx&needFlow=true），
     * 这是官方授权入口。若它能 302 到带 gid_ 的业务页，就说明整条链路
     * 可以脱离 WebView 用 OkHttp 完成。
     *
     * 依次尝试 4 步，每步都打日志，方便判断卡在哪。
     */
    fun verifyAuthUrlChain() {
        runCatching {
            val client = HttpClient.get()
            fun get(url: String, referer: String): Triple<Int, String?, String> {
                val req = Request.Builder().url(url)
                    .header("Referer", referer)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()
                HttpClient.get().newBuilder().followRedirects(false).build()
                    .newCall(req).execute().use { r ->
                        val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                        return Triple(r.code, r.header("Location"), body)
                    }
            }

            // 步骤 1：门户 JSONP，拿 authUrl
            val (c1, _, b1) = get(BASE + "/jsonp/recUseServiceList.json", JwSession.PORTAL_URL)
            android.util.Log.i("JwChain", "step1 recUseServiceList -> " + c1 + " len=" + b1.length)
            if (b1.contains("我的课表")) {
                val m = Regex("\"appName\":\"我的课表\".{0,300}?\"authUrl\":\"([^\"]+)\"").find(b1)
                android.util.Log.i("JwChain", "step1 authUrl = " + (m?.groupValues?.get(1) ?: "NOT_FOUND"))
            }

            // 步骤 2：authUrl
            val authUrl = BASE + "/jwapp/sys/funauthapp/qxgl.do?min=1&appName=wdkb&appId=4770397878132218&needFlow=true"
            val (c2, loc2, b2) = get(authUrl, BASE + "/new/index.html")
            android.util.Log.i("JwChain", "step2 qxgl -> " + c2 + " loc=" + loc2 + " len=" + b2.length + " head=" + b2.take(150).replace("\n", " "))

            // 步骤 3：跟随 location
            if (loc2 != null) {
                val next = if (loc2.startsWith("http")) loc2 else BASE + loc2
                val (c3, loc3, b3) = get(next, BASE + "/new/index.html")
                android.util.Log.i("JwChain", "step3 follow -> " + c3 + " url=" + next.take(120) + " loc=" + loc3 + " len=" + b3.length)
            }

            // 步骤 4：拿 gid 后直接请求业务页
            val gid = JwSession.currentGid
            android.util.Log.i("JwChain", "step4 current gid = " + (gid?.take(20) ?: "null"))
        }.onFailure {
            android.util.Log.e("JwChain", "chain failed: " + it.message, it)
        }
    }

    private fun probeQxgl(forPath: String) {
        runCatching {
            val appName = APP_NAME_REGEX.find(forPath)?.groupValues?.getOrNull(1) ?: "wdkb"
            val appId = APP_IDS[appName] ?: ""
            val url = BASE + "/jwapp/sys/funauthapp/qxgl.do?min=1&appName=" + appName +
                "&appId=" + appId + "&needFlow=true"
            val req = Request.Builder().url(url).get()
                .header("Referer", JwSession.PORTAL_URL)
                .build()
            val client = HttpClient.get().newBuilder().followRedirects(false).build()
            client.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                android.util.Log.w(
                    "JwQxgl",
                    appName + " -> " + resp.code + " location=" + resp.header("Location") +
                        " | len=" + body.length + " | head=" + body.take(400).replace("\n", " "),
                )
            }
        }.onFailure { android.util.Log.w("JwQxgl", "failed: " + it.message) }
    }

    /** 门户里应用的默认显示名（兜底用） */
    private const val DEFAULT_APP_LABEL = "我的课表"

    /** 各子应用的 appId（取自门户 recUseServiceList.json） */
    private val APP_IDS = mapOf(
        "wdkb" to "4770397878132218",
        "cjcx" to "4768574631264620",
        "xywccx" to "4980269146247992",
        "studentWdksapApp" to "4768687067472349",
        "xjxxbg" to "4975943402224761",
    )

    /**
     * 手动跟随最多 [MAX_REDIRECTS] 跳重定向，返回最终响应与最终 URL。
     *
     * 为什么不用 OkHttp 的自动重定向：自动跟随会把「302 到 CAS 登录页」变成一个
     * 200 的 HTML 页面，调用方只能靠内容猜；手动跟随时每一步 url 都可判定，
     * 落到 CAS 立刻就能认出「会话没了」。
     */
    private fun executeFollowing(request: Request): Pair<okhttp3.Response, String> {
        var current = request
        repeat(MAX_REDIRECTS) {
            val client = HttpClient.get().newBuilder().followRedirects(false).build()
            val resp = client.newCall(current).execute()
            val location = resp.header("Location")
            if (resp.code in REDIRECT_CODES && !location.isNullOrBlank()) {
                val next = resp.request.url.resolve(location) ?: return resp to resp.request.url.toString()
                resp.close()
                if (JwSession.isCasLanding(next.toString())) {
                    // 交给调用方判定为会话过期，不再继续跟
                    return client.newCall(current).execute() to next.toString()
                }
                current = Request.Builder()
                    .url(next)
                    .get()
                    .headers(current.headers)
                    .build()
            } else {
                return resp to resp.request.url.toString()
            }
        }
        return HttpClient.get().newCall(current).execute() to current.url.toString()
    }

    private fun parse(text: String): JsonElement = try {
        JSON.parseToJsonElement(text)
    } catch (t: Throwable) {
        throw JwException.Parse("响应不是合法 JSON（${text.take(60)}…）")
    }

    /**
     * 从响应里取 rows。
     * 先找 datas 下第一个含 rows 的对象，找不到就找顶层 rows（学分接口是这种）。
     */
    private fun rowsOf(root: JsonElement): JsonArray {
        val obj = root as? JsonObject ?: return JsonArray(emptyList())
        obj["datas"]?.let { datas ->
            val holder = (datas as? JsonObject)?.values?.firstOrNull { it is JsonObject && it.jsonObject.containsKey("rows") }
            val rows = (holder as? JsonObject)?.get("rows")
            if (rows is JsonArray) return rows
        }
        val topRows = obj["rows"]
        if (topRows is JsonArray) return topRows
        return JsonArray(emptyList())
    }

    /** 仅用于非包裹型响应（学分接口） */
    private fun rowsOfDirect(root: JsonElement): JsonArray {
        val obj = root as? JsonObject ?: return JsonArray(emptyList())
        return obj["rows"] as? JsonArray ?: rowsOf(root)
    }

    // ———————————————————————————— 课表 ————————————————————————————

    /**
     * 当前学年学期。走 [JwSemesterCache]：一个学期内只抓一次，
     * 避免每个页面各跑一遍 harvester。
     */
    suspend fun fetchCurrentSemester(force: Boolean = false): JwSemester =
        JwSemesterCache.get(force) { fetchCurrentSemesterRemote() }

    private suspend fun fetchCurrentSemesterRemote(): JwSemester {
        // 学期查询同样要能走页面取数：它是整个课表链路的第一环
        val root = parse(postOrHarvest("/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do", "", "dqxnxq", "我的课表", "wdkb"))
        val row = rowsOf(root).firstOrNull()?.jsonObject
            ?: throw JwException.Empty("没有拿到当前学年学期")
        val dm = row["DM"]?.let(::jwJsonString).orEmpty()
        val mc = row["MC"]?.let(::jwJsonString).orEmpty().ifBlank { dm }
        if (dm.isBlank()) throw JwException.Empty("学年学期代码为空")
        return JwSemester(dm = dm, mc = mc)
    }

    suspend fun fetchTimetable(semester: String): List<JwCourse> {
        val body = jwFormBody(
            "XNXQDM" to semester,
            "pageSize" to "100",
            "pageNumber" to "1",
        )
        // 注意路径是 modules/xskcb/xskcb.do（比其它模块多一层目录）。
        // 实测 .../modules/xskcb.do 会返回 404，HAR 里取数据的正是下面这条。
        //
        // 拿不到时走 [JwDataHarvester]：在隐藏 WebView 里让页面自己去请求，旁路截获响应。
        // 这是唯一验证过能绕开「非浏览器请求一律 403」的路径。
        val root = parse(postOrHarvest("/jwapp/sys/wdkb/modules/xskcb/xskcb.do", body, "xskcb", "我的课表", "wdkb"))
        return rowsOf(root).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = jwJsonString(o["KCM"])
            if (name.isBlank()) return@mapNotNull null
            JwCourse(
                KCM = name,
                KCH = jwJsonString(o["KCH"]),
                SKJS = jwJsonString(o["SKJS"]),
                SKSJ = jwJsonString(o["SKSJ"]),
                JASMC = jwJsonString(o["JASMC"]),
                SKZC = jwJsonString(o["SKZC"]),
                SKXQ = jwJsonDouble(o["SKXQ"])?.toInt() ?: 0,
                KSJC = jwJsonDouble(o["KSJC"])?.toInt() ?: 0,
                JSJC = jwJsonDouble(o["JSJC"])?.toInt() ?: 0,
                XF = jwJsonDouble(o["XF"]) ?: 0.0,
                XXXQDM_DISPLAY = jwJsonString(o["XXXQDM_DISPLAY"]),
                JXBID = jwJsonString(o["JXBID"]),
            )
        }
    }

    // ———————————————————————————— 成绩 ————————————————————————————

    suspend fun fetchGradeSemesters(): List<String> {
        val root = parse(postOrHarvest("/jwapp/sys/cjcx/modules/cjcx/cxdqxnxqhsygxnxq.do", "", "cxdqxnxqhsygxnxq", "成绩查询", "cjcx"))
        return rowsOf(root).mapNotNull { el ->
            val dm = jwJsonString((el as? JsonObject)?.get("XNXQDM"))
            dm.ifBlank { null }
        }.distinct()
    }

    /**
     * 拉取**全部**成绩（对应官网「全部」页签）。
     *
     * ## 为什么不再按学期过滤
     *
     * 官网有两个页签：`最近两学期` 和 `全部`。
     * 实测（HAR 第 77 / 88 条对比）：
     *   - 「最近两学期」带 XNXQDM=2025-2026-3,2026-2027-1 → **totalSize=0**（学校数据里根本没有这两个学期）
     *   - 「全部」**不带 XNXQDM**，只有 SFYX + SHOWMAXCJ → **totalSize=12**，就是真实成绩
     * 所以按学期过滤必然查空，必须查全部，再在客户端按 XNXQDM 分组。
     *
     * 请求体与 HAR 第 88 条保持一致：
     *   querySetting=[SFYX=1, SHOWMAXCJ=0]&*order=-XNXQDM,-KCH,-KXH&pageSize=..&pageNumber=1
     *
     * @param semesters 传空表示全部；非空则仍按学期过滤（保留兼容）
     */
    suspend fun fetchGrades(semesters: List<String> = emptyList()): List<JwGrade> {
        val conditions = buildString {
            append("{\"name\":\"SFYX\",\"linkOpt\":\"AND\",\"builder\":\"m_value_equal\",")
            append("\"value\":\"1\",\"value_display\":\"是\"}")
            append(",{\"name\":\"SHOWMAXCJ\",\"linkOpt\":\"AND\",\"builder\":\"m_value_equal\",")
            append("\"value\":\"0\",\"value_display\":\"否\"}")
            if (semesters.isNotEmpty()) {
                // 兼容旧调用：放在最前面
                insert(0, "{\"name\":\"XNXQDM\",\"linkOpt\":\"and\",\"builder\":\"m_value_equal\"," +
                    "\"value\":\"" + semesters.joinToString(",") + "\"},")
            }
        }
        val queryJson = "[" + conditions + "]"
        val body = jwFormBody(
            "querySetting" to queryJson,
            "*order" to "-XNXQDM,-KCH,-KXH",
            "pageSize" to "500",
            "pageNumber" to "1",
        )
        val root = parse(postOrHarvest("/jwapp/sys/cjcx/modules/cjcx/xscjcx.do", body, "xscjcx", "成绩查询", "cjcx"))
        return rowsOf(root).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = jwJsonString(o["KCM"]).ifBlank { jwJsonString(o["XSKCM"]) }
            if (name.isBlank()) return@mapNotNull null

            // 总成绩可能叫 ZCJ / CJ / ZSCJ，取第一个非空
            val rawScore = listOf("ZCJ", "CJ", "ZSCJ", "ZCJ_DISPLAY", "CJ_DISPLAY")
                .asSequence()
                .map { jwJsonString(o[it]) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            JwGrade(
                XNXQDM = jwJsonString(o["XNXQDM"]).ifBlank { jwJsonString(o["XNXQDM_DISPLAY"]) },
                KCM = name,
                KCH = jwJsonString(o["KCH"]).ifBlank { jwJsonString(o["XSKCH"]) },
                displayScore = rawScore,
                numericScore = rawScore.trim().removeSuffix("%").toDoubleOrNull(),
                JD = jwJsonString(o["JD"]).ifBlank { jwJsonString(o["XFJD"]) },
                XF = jwJsonString(o["XF"]).ifBlank { jwJsonString(o["XFJ"]) },
                KCKSDM_DISPLAY = jwJsonString(o["KCKSDM_DISPLAY"]),
                KCLBDM_DISPLAY = jwJsonString(o["KCLBDM_DISPLAY"])
                    .ifBlank { jwJsonString(o["KCFLDM_DISPLAY"]) },
                KCCJ_DISPLAY = jwJsonString(o["KCCJ_DISPLAY"]),
            )
        }
    }

    // ———————————————————————————— 考试 ————————————————————————————

    suspend fun fetchExams(semester: String): List<JwExam> {
        val paramJson = """{"XNXQDM":"$semester","*order":"-KSRQ,-KSSJMS"}"""
        val body = jwFormBody("requestParamStr" to paramJson)
        val root = parse(postOrHarvest("/jwapp/sys/studentWdksapApp/WdksapController/cxxsksap.do", body, "cxxsksap", "我的考试安排", "studentWdksapApp"))
        return rowsOf(root).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = jwJsonString(o["KCM"])
            val ksmc = jwJsonString(o["KSMC"])
            if (name.isBlank() && ksmc.isBlank()) return@mapNotNull null
            JwExam(
                KCM = name,
                KCH = jwJsonString(o["KCH"]),
                KSMC = ksmc,
                KSRQ = jwJsonString(o["KSRQ"]),
                KSSJMS = jwJsonString(o["KSSJMS"]),
                ZJJSXM = jwJsonString(o["ZJJSXM"]),
                JASMC = jwJsonString(o["JASMC"]),
                ZWH = jwJsonString(o["ZWH"]),
                XNXQDM_DISPLAY = jwJsonString(o["XNXQDM_DISPLAY"]),
            )
        }
    }

    // ———————————————————————————— 学分 ————————————————————————————

    /** ① 个人培养方案，返回 PYFADM */
    suspend fun fetchPyfadm(xh: String): String {
        val root = parse(postOrHarvest("/jwapp/sys/xywccx/modules/xywccx/grpyfacx.do", jwFormBody("XH" to xh), "grpyfacx", "学业完成查询", "xywccx"))
        val row = rowsOfDirect(root).firstOrNull()?.jsonObject
            ?: throw JwException.Empty("未查询到个人培养方案")
        val pyfadm = jwJsonString(row["PYFADM"])
        if (pyfadm.isBlank()) throw JwException.Empty("培养方案编码为空")
        return pyfadm
    }

    /** ② 学分完成情况 */
    suspend fun fetchCreditSummary(xh: String, pyfadm: String): JwCreditSummary {
        val body = jwFormBody(
            "XH" to xh,
            "SCLBDM" to "04",
            "PYFADM" to pyfadm,
            "BYNJDM" to "-",
        )
        val root = parse(postOrHarvest("/jwapp/sys/xywccx/modules/xywccx/cxscfa.do", body, "cxscfa", "学业完成查询", "xywccx"))
        val row = rowsOfDirect(root).firstOrNull()?.jsonObject
            ?: throw JwException.Empty("未查询到学分完成情况")

        return JwCreditSummary(
            PYFAMC = jwJsonString(row["PYFAMC"]),
            YQXF = jwJsonDouble(row["YQXF"]) ?: 0.0,
            WCXF = jwJsonDouble(row["WCXF"]) ?: 0.0,
            BXYXXF = jwJsonDouble(row["BXYXXF"]) ?: 0.0,
            ZYJYBXYQXF = jwJsonDouble(row["ZYJYBXYQXF"]) ?: 0.0,
            JCJYBXYQXF = jwJsonDouble(row["JCJYBXYQXF"]) ?: 0.0,
            TSJYBXYQXF = jwJsonDouble(row["TSJYBXYQXF"]) ?: 0.0,
            ZYJYBXZXF = jwJsonDouble(row["ZYJYBXZXF"]),
            JCJYBXZXF = jwJsonDouble(row["JCJYBXZXF"]),
            TSJYBXZXF = jwJsonDouble(row["TSJYBXZXF"]),
            XXYXXF = jwJsonDouble(row["XXYXXF"]) ?: 0.0,
            KZYYXXF = jwJsonDouble(row["KZYYXXF"]) ?: 0.0,
        )
    }

    // ———————————————————————————— 个人信息 ————————————————————————————

    suspend fun fetchProfile(xh: String): JwProfile {
        val root = parse(postOrHarvest("/jwapp/sys/xjxxbg/modules/xsxxbgsq/cxxsjbxx.do", jwFormBody("XH" to xh), "cxxsjbxx", "学生个人信息", "xjxxbg"))
        val row = rowsOf(root).firstOrNull()?.jsonObject
            ?: throw JwException.Empty("未查询到学籍基本信息")
        val name = jwJsonString(row["XM"])
        if (name.isBlank()) throw JwException.Empty("学籍信息缺少姓名字段")
        return JwProfile(
            XH = jwJsonString(row["XH"]).ifBlank { jwJsonString(row["XSBH"]) }.ifBlank { xh },
            XM = name,
            CSRQ = jwJsonString(row["CSRQ"]),
            XBDM_DISPLAY = jwJsonString(row["XBDM_DISPLAY"]).ifBlank { jwGenderText(jwJsonString(row["XBDM"])) },
            YXDM_DISPLAY = jwJsonString(row["YXDM_DISPLAY"]),
            ZYMC = jwJsonString(row["ZYMC"]),
            RXNY = jwJsonString(row["RXNY"]),
            MZDM_DISPLAY = jwJsonString(row["MZDM_DISPLAY"]),
            ZZMMDM_DISPLAY = jwJsonString(row["ZZMMDM_DISPLAY"]),
            SFZJH = jwJsonString(row["SFZJH"]),
            KSH = jwJsonString(row["KSH"]),
            NJDM_DISPLAY = jwJsonString(row["NJDM_DISPLAY"]),
            PYCCDM_DISPLAY = jwJsonString(row["PYCCDM_DISPLAY"]),
            XQDM_DISPLAY = jwJsonString(row["XQDM_DISPLAY"]),
        )
    }

    /** 头像直链，交给 Coil 加载（Cookie 由共享 CookieJar 中的 WebView 同步提供） */
    fun avatarUrl(): String = BASE + "/jwapp/sys/jwpubapp/showImageBydsForZPGL.do"

    /**
     * 尝试获取当前登录用户的学号。
     * 教务没有公开的「我是谁」接口，这里依次试几个可能的落点，全部失败返回 null，
     * 由 UI 引导用户手动输入。
     */
    suspend fun tryResolveXh(): String? {
        val candidates = listOf(
            "/jwapp/sys/wdkb/modules/xskcb/cxxsjbxx.do" to "",
            "/jwapp/sys/xjxxbg/modules/xsxxbgsq/cxxsjbxx.do" to "",
        )
        for ((path, body) in candidates) {
            val found = runCatching {
                val root = parse(post(path, body))
                val row = rowsOf(root).firstOrNull()?.jsonObject ?: return@runCatching null
                val xh = jwJsonString(row["XH"]).ifBlank { jwJsonString(row["XSBH"]) }
                xh.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun jwGenderText(code: String): String = when (code.trim()) {
        "1" -> "男"
        "2" -> "女"
        else -> ""
    }
}

/** 教务接口异常，[userMessage] 可直接展示给用户 */
sealed class JwException(userMessage: String) : Exception(userMessage) {
    /** 会话过期 / 未登录 */
    object SessionExpired : JwException("登录已过期，请先在「我的」页重新登录教务系统")

    /**
     * 会话本身是好的，但教务系统拒绝这次访问（403）。
     *
     * 实测：登录链已完整走通（CASTGC + MOD_AMP_AUTH + asessionid + JSESSIONID 全在），
     * 业务页以顶层导航方式打开仍然是 403，页面内 XHR 也是 403。
     * 这种情况下再让用户「重新同步」是没有意义的，必须给出准确解释。
     */


    data class Network(val reason: String) : JwException("网络异常：$reason")

    data class Http(val code: Int) : JwException("服务器返回 HTTP $code，请稍后重试")

    data class Parse(val reason: String) : JwException("数据解析失败：$reason")

    data class Empty(val reason: String) : JwException(reason)

    val userMessage: String get() = message ?: "未知错误"
}

/**
 * 会话本身没问题，但教务系统拒绝访问（403）。
 *
 * 放在顶层而不是塞进 [JwException] 内部：sealed 类里再嵌一个同名 object 会与
 * 继承自 Throwable 的成员产生 JVM 签名冲突（编译报 HIDDEN/Deprecated）。
 */
class JwPermissionDeniedException : JwException(
    "教务系统提示无权限访问。可能原因：账号未开通该功能、或教务系统正在维护。",
)

/** 统一把异常翻译成给用户看的一句话 */
fun Throwable.toJwMessage(): String = when (this) {
    is JwException -> userMessage
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误，请稍后重试"
}