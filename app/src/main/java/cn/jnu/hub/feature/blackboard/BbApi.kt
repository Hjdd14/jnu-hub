package cn.jnu.hub.feature.blackboard

import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * Blackboard（Learn 9.1 传统版）接口客户端。
 *
 * **没有 REST API**，全部是 JSP / Form 页面，因此每个方法 = 一次请求 + 一次 Jsoup 解析。
 *
 * 会话说明：BB 走 auth4 网关，用户在 WebView 里 CAS 登录后，Cookie 通过共享
 * PersistentCookieJar 直通，**不需要换票**。
 * 若请求被 302 到 CAS 登录页，说明会话过期 —— 抛出 BbException.SessionExpired，
 * 由 UI 引导用户重新登录（不自动重试，避免死循环）。
 */
object BbApi {

    const val BASE = JnuHosts.BLACKBOARD

    private val FORM: okhttp3.MediaType =
        "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    /** 课程列表模块 ID：My Courses */
    private const val MOD_COURSES = "_3_1"

    /** 课程列表模块 ID：My Organizations */
    private const val MOD_ORGS = "_4_1"

    private const val TAB_GROUP = "_1_1"

    // ———————————————————————————— 底层请求 ————————————————————————————

    /**
     * 带诊断信息的请求结果。
     *
     * 三种「非成功」必须分开，因为 UI 的引导完全不同：
     * - [needsLogin]  被踢回 CAS   → 「去登录」
     * - [needsGrant]  BB 拒绝授权   → 「仅 JBJI 学生可用」
     * - [error]       网络/解析/HTTP → 「重试」
     */
    private class Fetched(
        val body: String?,
        val needsLogin: Boolean = false,
        val needsGrant: Boolean = false,
        val error: BbException? = null,
    )

    private suspend fun requestResult(
        pathOrUrl: String,
        form: List<Pair<String, String>>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        referer: String = BASE + "/",
    ): Fetched = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else BASE + pathOrUrl
        val builder = Request.Builder()
            .url(url)
            // 注意：Accept 通配符必须拆开拼接（"星号" 与 "/" 的组合会被 Kotlin 当成块注释起始，
            // 把后面的代码整体吞掉，报 "Expecting a top level declaration"）。这里用变量规避。
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9," + STAR + "/" + STAR + ";q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", referer)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        if (form == null) {
            builder.get()
        } else {
            val body = form.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            builder.post(body.toRequestBody(FORM))
        }

        val response = try {
            HttpClient.get().newCall(builder.build()).execute()
        } catch (t: Throwable) {
            return@withContext Fetched(null, error = BbException.Network(t.message ?: "网络连接失败"))
        }

        response.use { resp ->
            // 真实落到哪一步（OkHttp 跟完重定向后的最终地址），用来区分「过期」与「无权限」
            val finalUrl = resp.request.url.toString()
            val body = try {
                resp.body?.string().orEmpty()
            } catch (t: Throwable) {
                ""
            }

            // ① 无权限：BB 对没有该模块访问权的账号会 403 / 跳 bb-auth-provider / 渲染 Access Denied 页
            if (looksLikeAccessDenied(body) || isGrantDeniedUrl(finalUrl) || resp.code == 403) {
                return@withContext Fetched(null, needsGrant = true)
            }

            // ② 未登录：被 302 到统一认证域，或 CAS 以 200 把登录表单渲染回来
            if (isCasUrl(finalUrl) || looksLikeCasLogin(body)) {
                return@withContext Fetched(null, needsLogin = true)
            }

            if (resp.code !in 200..299) {
                return@withContext Fetched(null, error = BbException.Http(resp.code))
            }
            if (body.isBlank()) {
                return@withContext Fetched(null, error = BbException.Parse("服务器返回了空页面"))
            }
            Fetched(body)
        }
    }

    private suspend fun fetch(
        pathOrUrl: String,
        form: List<Pair<String, String>>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        referer: String = BASE + "/",
    ): String {
        val result = requestResult(pathOrUrl, form, extraHeaders, referer)
        if (result.needsGrant) throw BbException.NoAccess
        if (result.needsLogin) throw BbException.SessionExpired
        result.body?.let { return it }
        throw result.error ?: BbException.SessionExpired
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** 是否落在统一认证域 */
    fun isCasUrl(url: String): Boolean =
        url.contains("icas.jnu.edu.cn") ||
            url.contains("/cas/login") ||
            url.contains("auth4.jnu.edu.cn") ||
            url.contains("auth7.jnu.edu.cn")

    /** 内容是否像 CAS 登录页 */
    fun looksLikeCasLogin(html: String): Boolean {
        if (html.isBlank()) return false
        val head = html.take(8000)
        if (head.contains("统一身份认证")) return true
        if (head.contains("casLoginForm")) return true
        if (head.contains("JNUID") && head.contains("立即登录")) return true
        return false
    }

    // ———————————————————————————— 无权限识别 ————————————————————————————

    /**
     * 是否「登录了，但没有这个模块的访问权」。
     *
     * 暨大 BB 只给伯明翰联合学院（JBJI）的学生开权限，其他学院账号会走到这里。
     * 识别依据（按可靠性排序）：
     * 1. 302 被跟到 /webapps/bb-auth-provider-cas/... —— BB 的 CAS 授权链，缺授权就会停在这
     * 2. HTTP 403
     * 3. 页面标题/主体出现 Access Denied
     *.
     * 注意：**不能**直接在整页 HTML 里搜 "Access Denied" —— 正常页面的 i18n
     * 脚本里也有 page.bundle.addKey('accessDeniedMsg','Access Denied')，会 100% 误判。
     * 所以只查 <title> 与主体可见文本。
     */
    fun looksLikeAccessDenied(html: String): Boolean {
        if (html.isBlank()) return false
        // 登录页优先：那是「未登录」，不是「无权限」
        if (looksLikeCasLogin(html)) return false
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return false
        val title = doc.title()
        if (title.contains("Access Denied", ignoreCase = true)) return true
        if (title.contains("Forbidden", ignoreCase = true)) return true
        // 主体可见文本（已剔除 script/style）
        val body = doc.body()?.text().orEmpty()
        if (body.isEmpty()) return false
        if (body.contains("Access Denied", ignoreCase = true)) return true
        if (body.contains("You do not have access to this content", ignoreCase = true)) return true
        if (body.contains("not enrolled", ignoreCase = true)) return true
        if (body.contains("无权访问") || body.contains("没有访问权限")) return true
        return false
    }

    /** 最终 URL 是否停在了授权链上（= 缺授权，而不是缺登录） */
    fun isGrantDeniedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/bb-auth-provider") ||
            lower.contains("access_denied") ||
            lower.contains("accessdenied")
    }

    // ———————————————————————————— ① 课程列表 ————————————————————————————

    /**
     * 拉取「我的课程 + 我的机构」。
     *
     * GET tabAction 的首页 HTML 里本来就带 My Courses 模块，
     * 而 POST refreshAjaxModule 是 BB 自己刷新模块的路径，两个都试、合并结果。
     */
    suspend fun fetchCourses(): List<BbCourse> = coroutineScope {
        // 首页 GET 是「会话是否有效」的权威判据：它一旦被判成无权限/未登录，
        // 就必须原样抛出去，不能再被下面的 getOrDefault 吞掉 —— 否则 UI 只会看到空列表，
        // 于是把「非 JBJI 学生」显示成「没有课程」。
        val homeRes = async {
            runCatching { fetch("/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=" + TAB_GROUP) }
        }

        val courses = async { postModule(MOD_COURSES) }
        val orgs = async { postModule(MOD_ORGS) }

        val homeOutcome = homeRes.await()
        val courseRaw = courses.await()
        val orgRaw = orgs.await()

        // 只有「无权限 / 未登录」算是决定性结论；网络抖动之类的先忍住，看模块有没有拿到数据
        homeOutcome.exceptionOrNull()?.let { t ->
            if (t is BbException.NoAccess || t is BbException.SessionExpired) throw t
        }
        val getRaw = homeOutcome.getOrDefault("")

        val merged = BbParsers.mergeCourseLists(courseRaw, orgRaw, getRaw)
        if (merged.isEmpty()) {
            // 三条路都没拿到任何课程：把真正的失败原因抛出去，别让调用方以为是「没选课」
            homeOutcome.exceptionOrNull()?.let { t -> if (t is BbException) throw t }
            throw BbException.Empty("没有解析到课程列表，可能你的账号暂时没有 Blackboard 访问权限")
        }
        merged
    }

    /** POST tabAction?action=refreshAjaxModule 刷新单个模块，返回 XML/CDATA 原文 */
    private suspend fun postModule(modId: String): String = runCatching {
        fetch(
            pathOrUrl = "/webapps/portal/execute/tabs/tabAction",
            form = listOf(
                "action" to "refreshAjaxModule",
                "modId" to modId,
                "tabId" to TAB_GROUP,
                "tab_tab_group_id" to TAB_GROUP,
            ),
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
    }.getOrDefault("")

    // ———————————————————————————— ② 课程主页 ————————————————————————————

    /**
     * 课程主页：同时拿到课程名和课程菜单。
     *
     * courseMain 本身会 302 到具体页面，OkHttp 自动跟随即可；
     * 拿不到菜单时退回 listContent 页面（两者都带同一套 courseMenuPalette）。
     */
    suspend fun fetchCourseHome(courseId: String): BbCourseHome {
        val mainHtml = runCatching {
            fetch("/webapps/blackboard/execute/courseMain?course_id=" + courseId + "&task=true&src=")
        }.getOrElse { t ->
            // 未登录 / 无权限 都要原样上抛，不能退化成「空页面 → 继续兜底」再报解析错误
            if (t is BbException.SessionExpired || t is BbException.NoAccess) throw t
            ""
        }

        if (mainHtml.isNotBlank()) {
            val home = BbParsers.parseCourseMenu(BbParsers.parse(mainHtml), courseId)
            if (home.menu.isNotEmpty()) return home
        }

        // 兜底：直接进内容页
        val contentHtml = fetch("/webapps/blackboard/content/listContent.jsp?course_id=" + courseId + "&mode=reset")
        val home = BbParsers.parseCourseMenu(BbParsers.parse(contentHtml), courseId)
        if (home.menu.isEmpty() && home.courseName.isBlank()) {
            throw BbException.Empty("没有解析到课程主页内容，可能没有选课权限")
        }
        return home
    }

    // ———————————————————————————— ③ 课程内容 ————————————————————————————

    /** 课程内容区 */
    suspend fun fetchContent(courseId: String, contentId: String = ""): List<BbContentItem> {
        val query = buildString {
            append("/webapps/blackboard/content/listContent.jsp?course_id=")
            append(enc(courseId))
            if (contentId.isNotBlank()) append("&content_id=").append(enc(contentId))
            append("&mode=reset")
        }
        return BbParsers.parseContentList(fetch(query))
    }

    // ———————————————————————————— ④ 通知 ————————————————————————————

    /**
     * 通知列表。
     *
     * @param context "course"（常规课程）或 "course_entry"（机构/入口页）
     * @param handle  BB 的模块 handle，默认 "announcements"
     */
    suspend fun fetchAnnouncements(
        courseId: String,
        context: String = "course",
        handle: String = "announcements",
    ): List<BbAnnouncement> {
        val query = buildString {
            append("/webapps/blackboard/execute/announcement?method=search")
            append("&context=").append(enc(context))
            append("&course_id=").append(enc(courseId))
            append("&handle=").append(enc(handle))
        }
        return BbParsers.parseAnnouncements(fetch(query), courseId)
    }

    /**
     * 自动挑 context：先按常规课程试，取不到再试 course_entry（机构页）。
     */
    suspend fun fetchAnnouncementsSmart(courseId: String, home: BbCourseHome? = null): List<BbAnnouncement> {
        val primary = runCatching { fetchAnnouncements(courseId, "course", "announcements") }
        val first = primary.getOrNull()
        if (!first.isNullOrEmpty()) return first

        val secondary = runCatching { fetchAnnouncements(courseId, "course_entry", "announcements_entry") }
        val second = secondary.getOrNull()
        if (second != null) return second

        primary.exceptionOrNull()?.let { if (it is BbException.SessionExpired) throw it }
        secondary.exceptionOrNull()?.let { if (it is BbException.SessionExpired) throw it }

        second?.let { return it }
        return emptyList()
    }

    /**
     * 按 ID 取一条通知的完整内容（通知详情页用）。
     *
     * 列表页的 `div.details` 里其实**已经带了正文**（BB 9.1 的 search 视图是展开渲染的），
     * 所以绝大多数情况这里不需要再发请求。但有两种情况列表里没有正文：
     *
     * 1. 老师发布时就只写了标题（正文天生为空）；
     * 2. 正文挂在单独的详情视图上。
     *
     * 对第 2 种，这里再试一次「单条通知」的视图：BB 的通知表单是
     * `POST /webapps/blackboard/execute/announcement`，字段 `method=view&announcementId=<id>`。
     * 拿不到不算错误（第 1 种情况本来就该空），只是不改善而已。
     */
    suspend fun fetchAnnouncementDetail(
        courseId: String,
        announcementId: String,
        home: BbCourseHome? = null,
    ): BbAnnouncement {
        val list = fetchAnnouncementsSmart(courseId, home)
        val listed = list.firstOrNull { it.id == announcementId }
            ?: throw BbException.Empty("该通知已不存在或无权查看")

        // 列表里已有正文：直接用，省一次请求
        if (listed.bodyHtml.isNotBlank()) return listed

        // 正文为空：尝试单条视图（失败就保持原样，说明老师确实没写正文）
        val single = runCatching {
            val html = fetch(
                pathOrUrl = "/webapps/blackboard/execute/announcement",
                form = listOf(
                    "method" to "view",
                    "announcementId" to announcementId,
                    "course_id" to courseId,
                    "context" to "course",
                    "internalHandle" to "announcements",
                    "handle" to "announcements",
                ),
            )
            BbParsers.parseAnnouncementBody(html)
        }.getOrNull()

        if (single.isNullOrBlank()) return listed
        // 单条视图里再抽一次附件与链接
        val root = BbParsers.parseBodyRoot(single)
        return listed.copy(
            bodyHtml = single,
            preview = BbParsers.text(root).take(200),
            attachments = BbParsers.parseAnnouncementAttachments(root),
            links = BbParsers.parseAnnouncementLinks(root),
        )
    }
    /**
     * 首页聚合用：跨课程取最新通知。
     *
     * 每门课各自一个 announcement 页面，挨个抓太慢，所以这里并发 + 限流，
     * 只取前 [maxCourses] 门课，每门课取前 [perCourse] 条。
     * 单门课失败直接跳过（首页不应该因为某一门课 403 就整体空白）。
     */
    suspend fun fetchLatestAnnouncements(
        maxCourses: Int = 6,
        perCourse: Int = 3,
    ): List<BbAnnouncement> = coroutineScope {
        val courses = runCatching { fetchCourses() }.getOrDefault(emptyList())
            .filter { !it.isOrganization }
            .take(maxCourses)

        val jobs = courses.map { course ->
            async {
                runCatching { fetchAnnouncementsSmart(course.id) }
                    .getOrDefault(emptyList())
                    .take(perCourse)
                    .map { it.copy(courseId = course.id) }
            }
        }
        jobs.awaitAll().flatten()
    }


    // ———————————————————————————— ⑤ 作业 ————————————————————————————

    /**
     * 作业详情。
     *
     * 走 uploadAssignment?mode=view —— 这个页面本身就是「提交历史」页，
     * 同时包含作业说明、截止时间、附件和已提交记录，一次性全拿到。
     *
     * 测评类条目（launchAssessment）没有这个页面，直接说明「只能打开原页面」：
     * 与其返回一份假数据，不如让 UI 明确告诉用户点进去看。
     */
    suspend fun fetchAssignment(
        courseId: String,
        contentId: String,
        groupId: String = "",
    ): BbAssignment {
        val query = buildString {
            append("/webapps/assignment/uploadAssignment?content_id=").append(enc(contentId))
            append("&course_id=").append(enc(courseId))
            if (groupId.isNotBlank()) append("&group_id=").append(enc(groupId))
            append("&mode=view")
        }
        val html = fetch(query)
        return BbParsers.parseAssignment(html, courseId, contentId)
    }

    /**
     * 「作业」Tab 的完整数据：合并两个来源后去重。
     *
     * | 来源 | 内容 | 能否拿到说明/附件 |
     * |---|---|---|
     * | 课程内容区 uploadAssignment | 作业名、截止、附件、提交记录 | ✅ |
     * | 课程内容区 launchAssessment | 测评名、摘要 | ❌（只有入口） |
     * | 成绩中心 myGrades 未出分项 | 名称、状态、截止 | ❌（只有条目） |
     *
     * 成绩中心那一份很重要：学生看到「CA1 还没分」，如果作业 Tab 里没有，
     * 就会以为 App 坏了。所以这里必须有，但也要在 UI 上说清楚它只是「条目」。
     */
    suspend fun fetchAssignments(courseId: String, home: BbCourseHome? = null): BbAssignmentSheet {
        val sourceItems = mutableListOf<BbAssignment>()
        var partial = 0
        var contentUnavailable = false

        // —— 来源 1：课程内容区 ——
        val contents = runCatching {
            fetchContent(courseId, home?.contentId.orEmpty())
        }.getOrElse { t ->
            if (t is BbException.SessionExpired || t is BbException.NoAccess) throw t
            contentUnavailable = true
            emptyList()
        }

        for (item in contents.filter { it.isAssignmentLike }) {
            val isAssessment = item.assessmentContentId.isNotBlank() && item.assignmentContentId.isBlank()
            if (isAssessment) {
                // 测评类没有详情页：保留入口 + 摘要，让用户点进去看
                sourceItems += BbAssignment(
                    contentId = item.assessmentContentId,
                    title = item.title,
                    source = BbAssignmentSource.ASSESSMENT,
                    openUrl = item.linkUrl,
                    instructionsText = item.summary,
                    files = item.attachments,
                    courseId = courseId,
                )
                continue
            }

            val detail = runCatching { fetchAssignment(courseId, item.evalContentId) }.getOrNull()
            if (detail == null) {
                partial++
                sourceItems += BbAssignment(
                    contentId = item.evalContentId,
                    title = item.title,
                    source = BbAssignmentSource.CONTENT,
                    openUrl = item.linkUrl,
                    instructionsText = item.summary,
                    files = item.attachments,
                    courseId = courseId,
                )
            } else {
                sourceItems += detail.copy(
                    title = detail.title.ifBlank { item.title },
                    source = BbAssignmentSource.CONTENT,
                    openUrl = item.linkUrl.ifBlank { detail.openUrl },
                )
            }
        }

        // —— 来源 2：成绩中心里「还没出分」的条目 ——
        val grades = runCatching { fetchCourseGrades(courseId).items }.getOrNull().orEmpty()
        val gradebookItems = grades.filter { it.isPendingAssignment }.map { grade ->
            BbAssignment(
                contentId = grade.id,
                title = grade.item,
                dueDate = grade.dueDate,
                source = BbAssignmentSource.GRADEBOOK,
                openUrl = grade.detailUrl,
                grade = grade.grade,
                courseId = courseId,
            )
        }

        // —— 合并去重 ——
        val merged = mergeAssignments(sourceItems + gradebookItems)
        val contentIds = sourceItems.map { it.contentId }.toSet()
        val titles = sourceItems.map { normalizeTitle(it.title) }.toSet()
        val gradebookOnly = gradebookItems.count {
            it.contentId !in contentIds && normalizeTitle(it.title) !in titles
        }

        val note = when {
            contentUnavailable ->
                "课程内容区暂时取不到，下面只列出成绩中心里能找到的作业。稍后可以下拉刷新重试。"
            else -> ""
        }

        return BbAssignmentSheet(
            courseId = courseId,
            items = merged,
            partialCount = partial,
            gradebookOnlyCount = gradebookOnly,
            detailNote = note,
        )
    }

    /**
     * 合并两个来源的作业并去重。
     *
     * 去重优先级：content_id 相同 > 标题（规范化后）相同。
     * 同一件事两份数据时保留「信息更全」的那一份（有说明/附件/截止的优先）。
     */
    private fun mergeAssignments(items: List<BbAssignment>): List<BbAssignment> {
        val out = LinkedHashMap<String, BbAssignment>()
        for (item in items) {
            val keys = listOf("id:" + item.contentId, "t:" + normalizeTitle(item.title))
            val existingKey = keys.firstOrNull { out.containsKey(it) }
            if (existingKey == null) {
                keys.forEach { out[it] = item }
            } else {
                val old = out.getValue(existingKey)
                val better = if (richness(item) > richness(old)) item else old
                val picked = better.copy(
                    dueDate = better.dueDate.ifBlank { old.dueDate },
                    openUrl = better.openUrl.ifBlank { old.openUrl },
                    grade = better.grade.ifBlank { old.grade },
                )
                keys.forEach { out[it] = picked }
            }
        }
        return out.values.distinctBy { it.contentId + "|" + normalizeTitle(it.title) }
            .sortedWith(compareBy({ it.dueDate.isBlank() }, { it.title.lowercase() }))
    }

    /** 信息完整度：有说明 > 有附件 > 有截止 > 只有标题 */
    private fun richness(a: BbAssignment): Int =
        (if (a.instructionsText.isNotBlank()) 8 else 0) +
            (if (a.files.isNotEmpty()) 4 else 0) +
            (if (a.attempts.isNotEmpty()) 2 else 0) +
            (if (a.dueDate.isNotBlank()) 1 else 0)

    /** 标题规范化：去掉空白/标点/大小写差异，用来跨来源匹配同一件事 */
    private fun normalizeTitle(raw: String): String =
        raw.lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")

    // ———————————————————————————— ⑥ 成绩 ————————————————————————————

    /** 单门课程成绩 */
    suspend fun fetchCourseGrades(courseId: String): BbGradeSheet {
        val query = "/webapps/bb-mygrades-BBLEARN/myGrades?course_id=" + enc(courseId) + "&stream_name=mygrades"
        return BbParsers.parseGrades(fetch(query), courseId)
    }

    /**
     * 成绩总览（streamViewer）。这一页（streamName=mygrades）主要是壳，
     * 真实数据由 myGrades?course_id= 提供，所以只当作可用性探测，失败不阻断。
     */
    suspend fun fetchGradesOverview(): String = runCatching {
        fetch("/webapps/streamViewer/streamViewer?cmd=view&streamName=mygrades&globalNavigation=false")
    }.getOrDefault("")

    /** 课程成绩；为空时给出明确的「暂无成绩」而不是空列表 */
    suspend fun fetchGradesSmart(courseId: String): BbGradeSheet {
        val sheet = fetchCourseGrades(courseId)
        if (sheet.items.isEmpty()) throw BbException.Empty("该课程暂无成绩")
        return sheet
    }

    // ———————————————————————————— ⑦ 课堂回放 ————————————————————————————

    /**
     * 课堂回放。
     *
     * 本 BB 的课程菜单通常没有独立 Panopto 项，回放多以外部链接形式
     * 贴在课程内容或通知正文里，所以这里把内容页 + 通知页都扫一遍。
     */
    suspend fun fetchRecordings(courseId: String, home: BbCourseHome? = null): BbRecordingSheet =
        coroutineScope {
            val contentId = home?.contentId.orEmpty()
            val contentHtml = async {
                runCatching { fetchContentHtml(courseId, contentId) }.getOrDefault("")
            }
            val announcementHtml = async {
                runCatching {
                    fetch(
                        "/webapps/blackboard/execute/announcement?method=search&context=course" +
                            "&course_id=" + enc(courseId) + "&handle=announcements",
                    )
                }.getOrDefault("")
            }
            val pages = listOf(contentHtml.await(), announcementHtml.await())
            BbParsers.parseRecordings(
                courseId = courseId,
                home = home ?: BbCourseHome(courseId = courseId, courseName = ""),
                pageHtmls = pages,
            )
        }

    private suspend fun fetchContentHtml(courseId: String, contentId: String): String {
        val query = buildString {
            append("/webapps/blackboard/content/listContent.jsp?course_id=").append(enc(courseId))
            if (contentId.isNotBlank()) append("&content_id=").append(enc(contentId))
            append("&mode=reset")
        }
        return fetch(query)
    }

    // ———————————————————————————— ⑧ 课程详情聚合 ————————————————————————————

    /**
     * 课程详情页需要「课程主页 + 内容列表」两份数据，一次性并发拉回来，
     * 供 4 个 Tab 共用（通知 / 作业 / 成绩 / 回放各自按需再请求）。
     */
    suspend fun fetchCourseOverview(courseId: String): BbCourseOverview = coroutineScope {
        val home = fetchCourseHome(courseId)
        val contents = runCatching { fetchContent(courseId, home.contentId) }.getOrElse { emptyList() }
        BbCourseOverview(course = home, contents = contents)
    }

    // ———————————————————————————— ⑨ 会话探测 ————————————————————————————

    /** 探测 Blackboard 是否仍登录（供页面进入时快速失败用） */
    suspend fun probeSession(): Boolean = withContext(Dispatchers.IO) {
        val result = requestResult("/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=" + TAB_GROUP)
        !result.needsLogin && result.body != null
    }

    /** 解析 HTML（暴露给 UI 层做正文渲染） */
    fun parseQuietly(html: String): org.jsoup.nodes.Document = Jsoup.parse(html, BASE)

    /** Accept 头的通配符，单独定义以避开 Kotlin 的块注释语法 */
    private const val STAR = "*"
}

/** 课程详情页的聚合数据 */
data class BbCourseOverview(
    val course: BbCourseHome,
    val contents: List<BbContentItem> = emptyList(),
) {
    /**
     * 内容区里所有「要交的东西」：uploadAssignment 作业 + launchAssessment 测评。
     *
     * 早先只认 uploadAssignment / assignment 图标，于是课程里以 test_on.gif 出现的
     * 测评类作业（例如 GT CA1）被整条丢掉，「作业」Tab 就成了「暂无作业」。
     */
    val assignments: List<BbContentItem>
        get() = contents.filter { it.isAssignmentLike }
}

/** Blackboard 接口异常，[userMessage] 可直接展示给用户 */
sealed class BbException(userMessage: String) : Exception(userMessage) {

    /**
     * 会话过期：请求被 302 到 CAS 登录页。
     *
     * BB 走 auth4 网关，用户 CAS 登录后 Cookie 直通 —— 所以这里**不需要换票**，
     * 只要引导用户重新走一次登录页即可。
     */
    object SessionExpired : BbException("Blackboard 登录状态已过期，请重新登录")

    /**
     * 已登录，但账号没有 Blackboard 访问权限。
     *
     * 暨大 BB 实际只对伯明翰联合学院（JBJI）的学生开放，
     * 其他学院账号点进来就是这个状态 —— 不是错误，是权限边界，
     * UI 要给友好说明 + 浏览器兜底，而不是「加载失败」。
     */
    object NoAccess : BbException("Blackboard 仅对伯明翰联合学院（JBJI）学生开放")

    data class Network(val reason: String) : BbException("网络异常：" + reason)

    data class Http(val code: Int) : BbException("服务器返回 HTTP " + code + "，请稍后重试")

    data class Parse(val reason: String) : BbException("数据解析失败：" + reason)

    data class Empty(val reason: String) : BbException(reason)

    val userMessage: String get() = message ?: "未知错误"
}

/** 统一把异常翻译成给用户看的一句话 */
fun Throwable.toBbMessage(): String = when (this) {
    is BbException -> userMessage
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误，请稍后重试"
}
