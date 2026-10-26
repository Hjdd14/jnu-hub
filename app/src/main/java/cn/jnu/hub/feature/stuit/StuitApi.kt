package cn.jnu.hub.feature.stuit

import cn.jnu.hub.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * 学工系统（stuit.jnu.edu.cn / D-Campus）接口客户端。
 *
 * 约定（见 docs/06_教务与学工接口实测.md 第二节）：
 * - 纯 JSP 服务端渲染，响应是 HTML，数据在 <table> 里，用 Jsoup 解析
 * - 所有请求走全局 [HttpClient.get()]，Cookie 由共享 CookieJar 携带（CAS 会话）
 * - 被重定向到 CAS / icas 域，或拉到 CAS 登录页 HTML = 缺少学工会话，
 *   交由 [StuitSession] 建立后自动重试一次，仍失败才抛 [StuitException.SessionExpired]
 */
object StuitApi {

    const val BASE = cn.jnu.hub.core.network.JnuHosts.STUIT

    private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    // ———————————————————————————— 底层 ————————————————————————————

    /**
     * 带诊断信息的请求结果。
     *
     * [needsSession] 为 true 表示「页面本身拿到了，但它是 CAS 登录页」——
     * 这**不是**错误，而是需要引导用户同步一次学工会话。
     * 把它和真正的网络/解析错误分开，UI 才能给出不同的引导，
     * 而不是笼统报「加载失败」。
     */
    private class Fetched(
        val html: String?,
        val needsSession: Boolean,
        val error: StuitException?,
    )

    private suspend fun fetchHtmlResult(
        path: String,
        form: List<Pair<String, String>>? = null,
        referer: String = BASE + "/",
    ): Fetched = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else BASE + path
        val builder = Request.Builder()
            .url(url)
            // 注意：Accept 头的通配符原样写会让 Kotlin 把其中的注释起始符当成块注释开头，
            // 后面的代码会被整体吞掉（报 "Expecting a top level declaration"）。这里拆开拼接规避。
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9," + "*" + "/" + "*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", referer)

        if (form == null) {
            builder.get()
        } else {
            val body = form.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            builder.post(body.toRequestBody(FORM))
        }

        val response = try {
            HttpClient.get().newCall(builder.build()).execute()
        } catch (t: Throwable) {
            return@withContext Fetched(null, false, StuitException.Network(t.message ?: "网络连接失败"))
        }

        response.use { resp ->
            val finalUrl = resp.request.url.toString()
            // 被 302 到统一认证域 = 没有 stuit 业务会话
            if (StuitSession.isCasUrl(finalUrl)) {
                return@withContext Fetched(null, true, null)
            }
            if (resp.code !in 200..299) {
                return@withContext Fetched(null, false, StuitException.Http(resp.code))
            }

            val html = try {
                resp.body?.string().orEmpty()
            } catch (t: Throwable) {
                return@withContext Fetched(null, false, StuitException.Network(t.message ?: "读取响应失败"))
            }
            if (html.isBlank()) {
                return@withContext Fetched(null, false, StuitException.Parse("服务器返回了空页面"))
            }
            // CAS 也可能以 200 把登录表单渲染回来，此时 URL 不变，只能看内容
            if (StuitSession.looksLikeCasLogin(html)) {
                return@withContext Fetched(null, true, null)
            }
            Fetched(html, false, null)
        }
    }

    /**
     * 取页面，必要时自动建立一次学工会话后重试。
     *
     * 学工需要一次 CAS 回跳才有 JSESSIONID，而 App 可能从没访问过 stuit 域，
     * 所以第一次大概率「没有会话」。[StuitSession.ensureSession] 会让 OkHttp
     * 自己走一遍首页跳转（CAS 有效时零点击即可拿到会话），然后再重取一次。
     */
    private suspend fun fetchHtml(
        path: String,
        form: List<Pair<String, String>>? = null,
        referer: String = BASE + "/",
    ): String {
        var result = fetchHtmlResult(path, form, referer)
        if (result.needsSession) {
            // 先尝试静默建立会话（CAS 有效时无需用户介入）
            StuitSession.invalidate()
            if (StuitSession.tryBootstrapWithHttp()) {
                result = fetchHtmlResult(path, form, referer)
            }
            if (result.needsSession) throw StuitException.SessionExpired
        }
        result.html?.let { return it }
        throw result.error ?: StuitException.SessionExpired
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun doc(html: String): org.jsoup.nodes.Document =
        try {
            Jsoup.parse(html, BASE)
        } catch (t: Throwable) {
            throw StuitException.Parse(t.message ?: "HTML 解析失败")
        }

    // ———————————————————————————— ① 综合测评 ————————————————————————————

    /** 默认测评模板 ID */
    const val DEFAULT_TEMPLATE_ID = "13"

    suspend fun fetchEvaluation(templateId: String = DEFAULT_TEMPLATE_ID): StuitEvaluation {
        val html = fetchHtml(
            "/student/evaluationNew34Jnu/stuEvaApp_detail.jsp?evaluationTemplateId=$templateId",
        )
        val d = doc(html)
        val evaluation = StuitHtmlParser.parseEvaluation(d)
        if (!evaluation.hasAnyScore && evaluation.header.name.isBlank() &&
            evaluation.basicItems.isEmpty() && evaluation.academicCourses.isEmpty()
        ) {
            throw StuitException.Empty("没有解析到综合测评数据，可能本学年尚未开启测评")
        }
        return evaluation
    }

    // ———————————————————————————— ② 专业排名 ————————————————————————————

    suspend fun fetchRanking(): List<StuitRankRow> {
        val html = fetchHtml("/student/evaluationNew34Jnu/stuEvaAppHistoryList_specRank.jsp")
        return StuitHtmlParser.parseRanking(doc(html))
    }

    // ———————————————————————————— ③ 成绩管理 ————————————————————————————

    /**
     * 成绩查询。year 形如「2024-2025学年」。
     * 页面 POST 需要 session_token，格式 token_key_{时间戳}，用毫秒时间戳即可。
     */
    suspend fun fetchGrades(year: String): List<StuitGrade> {
        val token = "token_key_" + System.currentTimeMillis()
        val form = listOf(
            "action_name" to "search",
            "parentDepartmentId" to "",
            "session_token" to token,
            "classYearId" to year,
            "subjectsName" to "",
            "subjectsTypeId" to "0",
            "scroll.pageSize" to "50",
            "scroll.pageIndex" to "0",
            "sort.sortColumn" to "s.XH",
            "sort.ascending" to "true",
            "example1_length" to "50",
        )
        val html = fetchHtml(
            "/student/studentScore/studentSubjectsList.jsp",
            form = form,
            referer = BASE + "/student/studentScore/studentSubjectsList.jsp",
        )
        return StuitHtmlParser.parseGrades(doc(html))
    }

    // ———————————————————————————— ④ 基础信息 ————————————————————————————

    suspend fun fetchBasicInfo(): StuitBasicInfo {
        val html = fetchHtml("/student/module/basicinfo/basicInfo.jsp")
        val d = doc(html)
        val info = StuitHtmlParser.parseBasicInfo(d)
        if (info.fields.isEmpty()) throw StuitException.Empty("没有解析到基本信息，页面结构可能已变更")

        val avatarPath = StuitHtmlParser.findAvatarUrl(d)
            ?: "/student/basicinfo/attachDownload.jsp"
        return info.copy(
            avatarUrl = if (avatarPath.startsWith("http")) avatarPath else BASE + avatarPath,
        )
    }
}

/** 学工接口异常，[userMessage] 可直接展示给用户 */
sealed class StuitException(userMessage: String) : Exception(userMessage) {
    /**
     * 缺少学工系统（stuit）自己的业务会话。
     *
     * 注意语义：这**不是**「统一认证过期」。学工走完整 CAS 回跳流程，
     * 即使 CAS 已登录，没访问过 stuit 域也一样没有 JSESSIONID，
     * 需要同步一次（通常是零点击，由 OkHttp 自动完成）。
     */
    object SessionExpired :
        StuitException("学工系统需要先同步一次登录状态")

    data class Network(val reason: String) : StuitException("网络异常：$reason")

    data class Http(val code: Int) : StuitException("服务器返回 HTTP $code，请稍后重试")

    data class Parse(val reason: String) : StuitException("数据解析失败：$reason")

    data class Empty(val reason: String) : StuitException(reason)

    val userMessage: String get() = message ?: "未知错误"
}

/** 统一把异常翻译成给用户看的一句话 */
fun Throwable.toStuitMessage(): String = when (this) {
    is StuitException -> userMessage
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误，请稍后重试"
}