package cn.jnu.hub.feature.blackboard

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Blackboard Learn 9.1 HTML 解析器。
 *
 * 所有选择器都用 `_research/blackboard.jnu.edu.cn.har` 里的**真实页面**验证过：
 *
 * | 页面 | 关键结构 |
 * |---|---|
 * | tabAction（课程列表） | `<contents><![CDATA[ ... ]]></contents>` → `ul.courseListing` → `a[href*=launcher]` |
 * | listContent | `ul#content_listContainer > li` → `div.item h3` 标题 + `ul.attachments li a` 附件 |
 * | announcement | `ul#announcementList > li` → `h3.item` 标题 + `div.details` 正文 + `div.announcementInfo` 作者 |
 * | uploadAssignment | `#instructionId` 说明 + `#assignmentInfo h3` 名称/截止/附件 + `#aggregateGrade` 得分 |
 * | myGrades | `div.gradeTableNew div.sortable_item_row` → `div.cell.gradable a` / `span.grade` / `span.activityType` |
 *
 * 原则：**宁可少解析一点，也不要因为某个标签改名就整页失败**。
 * 所有函数都是纯函数（只依赖 Jsoup DOM，不依赖 Android），便于单独排查。
 */
object BbParsers {

    // —————————————————————————— 通用工具 ——————————————————————————

    /** &nbsp; 会被 Jsoup 解析成 \u00A0，这里统一压成普通空格 */
    fun normalize(raw: String?): String =
        raw.orEmpty()
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    fun text(el: Element?): String = normalize(el?.text())

    fun parse(html: String): Document = Jsoup.parse(html, BbCourse.BASE)

    /**
     * tabAction 返回的是 XML 包一层 CDATA，里面才是真正的 HTML 片段。
     * 这里取出 CDATA 内容后直接交给 Jsoup（片段文件可以正常解析）。
     */
    fun unwrapAjaxHtml(raw: String): String {
        val body = raw.trim()
        if (body.isEmpty()) return ""
        val open = body.indexOf("<![CDATA[")
        if (open >= 0) {
            val close = body.indexOf("]]>", open)
            if (close > open) {
                return body.substring(open + "<![CDATA[".length, close).trim()
            }
        }
        // 某些响应没有 CDATA，直接是 HTML
        return body
    }

    /** 从 `/webapps/blackboard/execute/launcher?type=Course&id=_659_1&url=` 抠出 `_659_1` */
    private val COURSE_ID_REGEX = Regex("""[?&]id=(_[0-9]+_[0-9]+)""")
    private val TYPE_REGEX = Regex("""[?&]type=(Course|Organization)""")
    private val CONTENT_ID_REGEX = Regex("""[?&]content_id=(_[0-9]+_[0-9]+)""")

    private fun firstGroup(regex: Regex, raw: String): String =
        regex.find(raw)?.groupValues?.getOrNull(1).orEmpty()

    /** 建一个空 Element（Jsoup 没有公开的无参构造器） */
    private fun jsoupElement(tag: String): Element = Jsoup.parse("<" + tag + "></" + tag + ">").body().child(0)

    /** 连续空白（含全角空格） */
    private val WHITESPACE_REGEX = Regex("""[\s\u00A0\u3000]+""")

    // —————————————————————————— ① 课程列表 ——————————————————————————

    /**
     * 解析 POST /webapps/portal/execute/tabs/tabAction 的响应。
     *
     * 同时兼容两个入口：
     * - `modId=_3_1`（My Courses，`coursefakeclass`）
     * - `modId=_4_1`（My Organizations，`orgfakeclass`）
     *
     * 判定依据是 launcher 链接的 `type=` 参数：Organization 才是机构页。
     * 不依赖 `<h3>` 的英文文案（英文文案会随语言包变），只做兜底。
     */
    fun parseCourseList(raw: String): List<BbCourse> {
        val html = unwrapAjaxHtml(raw)
        if (html.isBlank()) return emptyList()
        val doc = parse(html)

        val result = LinkedHashMap<String, BbCourse>()

        val links = doc.select("a[href*=launcher]").ifEmpty {
            // 万一页面没有 class 也能抓到全部 launcher 链接
            doc.select("a[href]").filter { it.attr("href").contains("launcher") }
        }

        for (a in links) {
            val href = a.attr("href")
            val courseId = firstGroup(COURSE_ID_REGEX, href)
            if (courseId.isBlank()) continue
            val name = text(a)
            if (name.isBlank()) continue
            val type = firstGroup(TYPE_REGEX, href)
            val isOrg = type.equals("Organization", ignoreCase = true)
            result[courseId] = BbCourse(id = courseId, name = name, isOrganization = isOrg)
        }
        return result.values.toList()
    }

    /**
     * 从 tabAction HTML 里同时抽取课程与机构（合并两个 modId 的响应）。
     * 同一个 ID 只保留第一次出现的结果。
     */
    fun mergeCourseLists(vararg rawLists: String): List<BbCourse> {
        val map = LinkedHashMap<String, BbCourse>()
        rawLists.forEach { raw ->
            parseCourseList(raw).forEach { c -> map.putIfAbsent(c.id, c) }
        }
        return map.values.toList()
    }

    // —————————————————————————— ② 课程主页 / 菜单 ——————————————————————————

    /**
     * 解析课程页（courseMain / listContent 都带同一套课程菜单）。
     *
     * 真实结构：
     * ```
     * <ul id="courseMenuPalette_contents" class="courseMenu">
     *   <li id="paletteItem:_5873_1"><a href="/webapps/..."><span title="Module Content">...</span></a></li>
     * ```
     * 面包屑里的 `ol.clearfix li a` 第一个就是课程名。
     */
    fun parseCourseMenu(doc: Document, courseId: String): BbCourseHome {
        val name = text(doc.selectFirst("#pageTitleText"))
            .ifBlank { text(doc.selectFirst("ol.clearfix li.root a")) }
            .ifBlank { text(doc.selectFirst("h1#pageTitleHeader")) }

        val menu = doc.select("ul#courseMenuPalette_contents li a")
            .ifEmpty { doc.select("li[id^=paletteItem:] a") }
            .mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = text(a.selectFirst("span")).ifBlank { text(a) }
                if (title.isBlank()) return@mapNotNull null
                BbMenuItem(
                    title = title,
                    url = href,
                    external = href.startsWith("http") && !href.contains("blackboard.jnu.edu.cn"),
                )
            }
            .distinctBy { it.title + "|" + it.url }

        // 内容区入口：菜单里「Module Content / Content」通常是 listContent.jsp
        val contentId = menu.firstOrNull { it.url.contains("listContent.jsp") }
            ?.let { firstGroup(CONTENT_ID_REGEX, it.url) }
            ?: doc.select("a[href*=listContent.jsp]")
                .mapNotNull { firstGroup(CONTENT_ID_REGEX, it.attr("href")).takeIf { id -> id.isNotBlank() } }
                .firstOrNull()
                .orEmpty()

        return BbCourseHome(
            courseId = courseId,
            courseName = name,
            contentId = contentId,
            menu = menu,
        )
    }

    /** 课程菜单里是否有「通知」工具入口 */
    fun findAnnouncementMenuUrl(home: BbCourseHome): String? =
        home.menu.firstOrNull {
            it.title.contains("Announcement", ignoreCase = true) ||
                it.title.contains("通知")
        }?.url

    /** 课程菜单里 Panopto 回放入口 */
    fun findPanoptoMenuUrl(home: BbCourseHome): String? =
        home.menu.firstOrNull {
            it.url.contains("ppto-PanoptoCourseTool", ignoreCase = true) ||
                it.title.contains("Panopto", ignoreCase = true) ||
                it.title.contains("Echo", ignoreCase = true) ||
                it.title.contains("回放")
        }?.url

    // —————————————————————————— ③ 课程内容 ——————————————————————————

    /**
     * 解析 listContent.jsp。
     *
     * 真实结构（每一个条目）：
     * ```
     * <li id="contentListItem:_18954_1">
     *   <img class="item_icon" src="/images/ci/sets/set01/document_on.gif">
     *   <div class="item" id="_18954_1"><h3>…<a href="...">标题</a> / <span>标题</span></h3></div>
     *   <div class="details">…<ul class="attachments"><li><a href="/bbcswebdav/...">文件名</a> (1.3 MB)</li></ul></div>
     * </li>
     * ```
     * 标题既可能是 `<a>`（可点），也可能只是一个 `<span style="color:...">`。
     */
    fun parseContentList(html: String): List<BbContentItem> {
        val doc = parse(html)
        val container = doc.selectFirst("ul#content_listContainer")
            ?: doc.selectFirst("ul.contentList")
            ?: return emptyList()

        return container.select("> li").mapNotNull { li -> parseContentItem(li) }
            .filter { it.title.isNotBlank() }
    }

    private fun parseContentItem(li: Element): BbContentItem? {
        val itemDiv = li.selectFirst("div.item") ?: return null
        val contentId = itemDiv.id().ifBlank {
            li.id().substringAfter("contentListItem:", "")
        }
        if (contentId.isBlank()) return null

        val h3 = itemDiv.selectFirst("h3")
        val anchor = h3?.selectFirst("a[href]")
        val title = text(h3).ifBlank { text(itemDiv) }
        if (title.isBlank()) return null

        val iconSrc = li.selectFirst("img.item_icon")?.attr("src").orEmpty()
        val kind = when {
            iconSrc.contains("folder") -> BbContentKind.FOLDER
            iconSrc.contains("assignment") -> BbContentKind.ASSIGNMENT
            iconSrc.contains("test") || iconSrc.contains("assessment") -> BbContentKind.TEST
            iconSrc.contains("link") -> BbContentKind.LINK
            iconSrc.contains("file") -> BbContentKind.FILE
            iconSrc.contains("document") || iconSrc.contains("blankpage") -> BbContentKind.DOCUMENT
            else -> BbContentKind.OTHER
        }

        val href = anchor?.attr("href").orEmpty()
        // 有些条目把真实地址塞在 onClick 里：this.href='/webapps/...'
        val onClickHref = ONCLICK_HREF_REGEX.find(anchor?.attr("onClick").orEmpty())
            ?.groupValues?.get(1).orEmpty()
        val linkUrl = href.ifBlank { onClickHref }

        val attachments = parseAttachments(li.select("ul.attachments li a"))

        // 两套「要交的东西」入口：uploadAssignment（作业）与 launchAssessment（作业/测验）。
        // 后者在内容区里图标就是 test_on.gif，此前被整条丢掉，导致「作业」Tab 少项。
        val assignmentContentId = when {
            linkUrl.contains("uploadAssignment") -> firstGroup(CONTENT_ID_REGEX, linkUrl)
            kind == BbContentKind.ASSIGNMENT -> contentId
            else -> ""
        }
        val assessmentContentId = when {
            linkUrl.contains("launchAssessment") || linkUrl.contains("/assessment/") ->
                firstGroup(CONTENT_ID_REGEX, linkUrl).ifBlank { contentId }
            kind == BbContentKind.TEST && assignmentContentId.isBlank() -> contentId
            else -> ""
        }

        val details = li.selectFirst("div.details")
        val summary = text(details?.selectFirst("div.vtbegenerated"))
            .ifBlank {
                text(details)
                    .replace(ATTACHED_LABEL, "")
                    .trim()
            }

        return BbContentItem(
            contentId = contentId,
            title = title,
            kind = kind,
            linkUrl = linkUrl,
            assignmentContentId = assignmentContentId,
            assessmentContentId = assessmentContentId,
            attachments = attachments,
            summary = summary,
        )
    }

    /** onClick 里塞的真实地址：this.href = '...' */
    private val ONCLICK_HREF_REGEX: Regex by lazy {
        Regex(LITERAL_THIS_HREF + WHITESPACE_CLASS + LITERAL_EQ + WHITESPACE_CLASS + QUOTE_CLASS + "([^" + QUOTE_CLASS + "]+)" + QUOTE_CLASS)
    }

    private const val LITERAL_THIS_HREF = "this[.]href"
    private const val LITERAL_EQ = "="
    private const val WHITESPACE_CLASS = "[\\s]"
    private const val QUOTE_CLASS = "[\u0027\u0022]"
    private const val ATTACHED_LABEL = "Attached Files:"

    /** `<ul class="attachments"><li><a href="...">名字</a> (<span>1.3 MB</span>)</li></ul>` */
    private fun parseAttachments(anchors: List<Element>): List<BbAttachment> =
        anchors.mapNotNull { a ->
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            if (href.startsWith("#") || href.startsWith("mailto:")) return@mapNotNull null
            // 文件名：优先取 <a> 内文字，去掉图标带来的空白与 &nbsp;
            val name = text(a).ifBlank { href.substringAfterLast('/') }
            val sizeEl = a.parent()?.selectFirst("span[id$=_fileSize]")
            val size = text(sizeEl).ifBlank { SIZE_REGEX.find(a.parent()?.text().orEmpty())?.value.orEmpty() }
            BbAttachment(name = name, url = href, size = size)
        }.distinctBy { it.url }

    private val SIZE_REGEX = Regex("""[\d.]+\s?(?:B|KB|MB|GB)""", RegexOption.IGNORE_CASE)

    // —————————————————————————— ④ 通知 ——————————————————————————

    /**
     * 解析 announcement?method=search 页面。
     *
     * 真实结构：
     * ```
     * <ul id="announcementList">
     *   <li class="clearfix" id="_2419_1">
     *     <h3 class="item">标题</h3>
     *     <div class="details">
     *        <p><span>Posted on: Wednesday, September 30, 2026 1:20:57 AM CST</span></p>
     *        <p><div class="vtbegenerated">…正文…</div></p>
     *     </div>
     *     <div class="announcementInfo">
     *        <p><span>Posted by:</span> Daniel Jones</p>
     *        <p><span>Posted to:</span> 课程名</p>
     *     </div>
     *   </li>
     * ```
     */
    fun parseAnnouncements(html: String, courseId: String): List<BbAnnouncement> {
        val doc = parse(html)
        val list = doc.selectFirst("ul#announcementList")
            ?: doc.selectFirst("ul.announcementList")
            ?: return emptyList()

        return list.select("> li").mapNotNull { li -> parseAnnouncement(li, courseId) }
            .filter { it.title.isNotBlank() }
    }

    private fun parseAnnouncement(li: Element, courseId: String): BbAnnouncement? {
        val id = li.id().ifBlank { return null }
        val title = text(li.selectFirst("h3.item")).ifBlank { text(li.selectFirst("h3")) }
        if (title.isBlank()) return null

        val details = li.selectFirst("div.details")

        // 发布时间：「Posted on: …」所在的第一个 <p>；其余 <p> 拼成正文
        var postedOn = ""
        val bodyParts = StringBuilder()
        if (details != null) {
            for (p in details.select("> p")) {
                val t = text(p)
                if (t.startsWith(POSTED_ON)) {
                    postedOn = t.removePrefix(POSTED_ON).trim()
                } else {
                    bodyParts.append(p.outerHtml())
                }
            }
            if (bodyParts.isBlank()) {
                details.selectFirst("div.vtbegenerated")?.let { bodyParts.append(it.outerHtml()) }
            }
        }

        val info = li.selectFirst("div.announcementInfo")
        var postedBy = ""
        var postedTo = ""
        info?.select("p")?.forEach { p ->
            val t = text(p)
            when {
                t.startsWith(POSTED_BY) -> postedBy = t.removePrefix(POSTED_BY).trim()
                t.startsWith(POSTED_TO) -> postedTo = t.removePrefix(POSTED_TO).trim()
            }
        }

        val bodyHtml = bodyParts.toString()
        // 正文再解析一次成 DOM，用来抽附件（bbcswebdav 链接/图片）与外部链接
        val bodyRoot = parseBodyRoot(bodyHtml)
        val attachments = parseAnnouncementAttachments(bodyRoot)
        val links = parseAnnouncementLinks(bodyRoot)

        return BbAnnouncement(
            id = id,
            title = htmlUnescape(title),
            postedOn = postedOn,
            postedBy = postedBy,
            postedTo = postedTo,
            preview = text(bodyRoot).take(200),
            bodyHtml = bodyHtml,
            attachments = attachments,
            links = links,
            courseId = courseId,
        )
    }

    private const val POSTED_ON = "Posted on:"
    private const val POSTED_BY = "Posted by:"
    private const val POSTED_TO = "Posted to:"

    /**
     * 通知正文里的附件。
     *
     * BB 没有单独的「附件区」，附件就是正文里指向 `/bbcswebdav/` 的链接：
     * ```
     * <a href="/bbcswebdav/pid-376-dt-announcement-rid-42763_1/xid-42763_1"><span>xxx.pdf</span></a>
     * ```
     * 内嵌图片（`<img src="/bbcswebdav/...">`）也一并收集，便于详情页整页展示。
     */
    fun parseAnnouncementAttachments(body: Element): List<BbAttachment> {
        val out = mutableListOf<BbAttachment>()
        body.select("a[href*=bbcswebdav]").forEach { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@forEach
            val name = text(a).ifBlank { a.attr("title") }
                .ifBlank { java.net.URLDecoder.decode(href.substringAfterLast('/'), "UTF-8") }
            out += BbAttachment(name = name, url = href)
        }
        body.select("img[src*=bbcswebdav]").forEach { img ->
            val src = img.attr("src")
            if (src.isBlank()) return@forEach
            val name = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { "图片" }
            out += BbAttachment(name = name, url = src)
        }
        return out.distinctBy { it.url }
    }

    fun parseAnnouncementLinks(body: Element): List<BbLink> =
        body.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.contains("bbcswebdav")) return@mapNotNull null
            val label = text(a).ifBlank { href }
            BbLink(text = label, url = href)
        }.distinctBy { it.url }

    /** 把一段正文 HTML 包成可查询的 DOM 根节点 */
    fun parseBodyRoot(bodyHtml: String): Element =
        parse("<div id=\"bbbody\">" + bodyHtml + "</div>").selectFirst("#bbbody")
            ?: jsoupElement("div")

    /**
     * 从「单条通知视图」的整页 HTML 里抽出正文 HTML。
     *
     * 兼容三种容器：
     * - `div.vtbegenerated`（BB 富文本正文的标准容器）
     * - `ul#announcementList li div.details`（视图复位成列表形态时）
     * - `#contentdiv`（最后兜底）
     *
     * 都取不到时返回空串 —— 这通常意味着老师确实没写正文，而不是解析失败。
     */
    fun parseAnnouncementBody(html: String): String {
        val doc = parse(html)
        doc.selectFirst("#announcementList li div.vtbegenerated")?.let { return it.html() }
        doc.selectFirst("div.vtbegenerated")?.let { return it.html() }
        doc.selectFirst("#announcementList li div.details")?.let { details ->
            val clone = details.clone()
            // 去掉「Posted on:」那一行
            clone.select("p").forEach { p ->
                if (text(p).startsWith(POSTED_ON)) p.remove()
            }
            return clone.html()
        }
        return ""
    }

    /** 标题里的 `&#x3a;` 之类，Jsoup 通常已解码，这里再兜一次 */
    private fun htmlUnescape(s: String): String =
        s.replace("&#x3a;", ":").replace("&amp;", "&").replace("&ndash;", "–").trim()

    // —————————————————————————— ⑤ 作业 ——————————————————————————

    /**
     * 解析 uploadAssignment?mode=view 页面。
     *
     * 真实结构：
     * ```
     * <h1 id="pageTitleHeader"><span id="pageTitleText">Review Submission History: 作业名</span></h1>
     * <div id="instructionId"><div class="vtbegenerated">作业说明…</div></div>
     * <div id="assignmentInfo">
     *   <h3>Name</h3><p>Continuous Assessment</p>
     *   <h3>Due Date</h3><p>October 14, 2026 1:00 PM</p>
     *   <h3>Assignment Files</h3><ul><li><a href="/bbcswebdav/...">CO_Assessment.pdf</a></li></ul>
     * </div>
     * <input id="aggregateGrade" value="-"><span class="pointsPossible">/100</span>
     * ```
     */
    fun parseAssignment(html: String, courseId: String, contentId: String): BbAssignment {
        val doc = parse(html)

        val pageTitle = text(doc.selectFirst("#pageTitleText"))
            .ifBlank { text(doc.selectFirst("h1#pageTitleHeader")) }

        val instructionEl = doc.selectFirst("#instructionId")
            ?: doc.selectFirst("#contentAreaBlock0")
        val instructionsHtml = instructionEl?.html().orEmpty()
        val instructionsText = text(instructionEl)

        // 右侧 Assignment Details：Name / Due Date / Assignment Files
        var name = ""
        var dueDate = ""
        val files = mutableListOf<BbAttachment>()
        doc.selectFirst("#assignmentInfo")?.let { info ->
            for (h3 in info.select("h3")) {
                val key = text(h3)
                val value = text(h3.nextElementSibling())
                when {
                    key.equals("Name", ignoreCase = true) -> name = value
                    key.contains("Due Date", ignoreCase = true) -> dueDate = value
                    key.contains("Assignment Files", ignoreCase = true) || key.contains("附件") ->
                        files += parseAttachments(info.select("ul li a[href]"))
                }
            }
        }

        val title = name.ifBlank {
            pageTitle.substringAfter("Review Submission History:", "").trim()
                .ifBlank { pageTitle }
        }

        val gradeInput = doc.selectFirst("#aggregateGrade")
        val grade = gradeInput?.attr("value").orEmpty().let { if (it == "-") "" else it }
        val pointsPossible = text(doc.selectFirst("#aggregateGrade_pointsPossible"))
            .removePrefix("/")

        val attempts = parseSubmissions(doc)

        return BbAssignment(
            contentId = contentId,
            title = title.ifBlank { "作业 $contentId" },
            dueDate = dueDate,
            instructionsHtml = instructionsHtml,
            instructionsText = instructionsText,
            files = files.distinctBy { it.url },
            attempts = attempts,
            pointsPossible = pointsPossible,
            grade = grade,
            courseId = courseId,
        )
    }

    /** 提交记录：`#currentAttempt_content` 下的 `ul.filesList li a` */
    private fun parseSubmissions(doc: Document): List<BbSubmission> {
        val out = mutableListOf<BbSubmission>()
        val label = text(doc.selectFirst("#currentAttempt_label"))
        val date = text(doc.selectFirst("#currentAttempt_label .dateStamp"))
        val grade = doc.selectFirst("#currentAttempt_grade")?.attr("value").orEmpty()
        val files = doc.select("ul#currentAttempt_submissionList li")
            .mapNotNull { li ->
                val a = li.selectFirst("a.downloadFile a[href]") ?: li.selectFirst("a[href]")
                val visible = li.selectFirst("a.attachment") ?: a
                val href = a?.attr("href").orEmpty()
                if (href.isBlank()) return@mapNotNull null
                BbAttachment(name = text(visible), url = href)
            }
        if (files.isNotEmpty() || label.isNotBlank()) {
            out += BbSubmission(label = label, date = date, grade = grade, files = files)
        }
        return out
    }

    // —————————————————————————— ⑥ 成绩 ——————————————————————————

    /**
     * 解析 /webapps/bb-mygrades-BBLEARN/myGrades 页面。
     *
     * 真实结构：
     * ```
     * <div class="gradeTableNew mybb">
     *   <div class="grades_header">…</div>
     *   <div id="grades_wrapper">
     *     <div class="sortable_item_row graded_item_row row expanded" id="3231">
     *       <div class="cell gradable"><a href="#">课程名</a><div class="itemCat">Survey</div></div>
     *       <div class="cell activity timestamp">
     *         <span class="lastActivityDate">Oct 3, 2026 10:54 PM</span>
     *         <span class="activityType">Graded</span></div>
     *       <div class="cell grade"><span class="grade">85</span></div>
     *       <div class="cell gradeStatus">…<span class="tooltip">Completed</span></div>
     *     </div>
     * ```
     * 另外要注意 `.calculatedRow`（Total / Weighted Total）这些是合计行，单独标记。
     */
    fun parseGrades(html: String, courseId: String): BbGradeSheet {
        val doc = parse(html)
        val courseName = text(doc.selectFirst("#streamDetailHeaderRightClickable"))
            .ifBlank { text(doc.selectFirst("h2.streamHeader")) }

        val rows = doc.select("div.gradeTableNew div.sortable_item_row")
            .ifEmpty { doc.select("#grades_wrapper div.sortable_item_row") }
            .ifEmpty { doc.select("div.sortable_item_row") }

        val items = rows.mapNotNull { row ->
            val cellGradable = row.selectFirst("div.cell.gradable")
                ?: row.selectFirst("div.cell.gradable_item")
                ?: return@mapNotNull null

            // 合计行没有 <a>，直接是文本
            val anchor = cellGradable.selectFirst("a[href]")
            val itemName = text(anchor).ifBlank {
                // 去掉 category / eval-links 后再取剩余文本
                val clone = cellGradable.clone()
                clone.select("div.itemCat, div.eval-links, input, select").remove()
                text(clone)
            }
            if (itemName.isBlank()) return@mapNotNull null

            val category = text(cellGradable.selectFirst("div.itemCat"))
            val lastActivity = text(row.selectFirst(".lastActivityDate"))
            val activityType = text(row.selectFirst(".activityType"))
            val grade = text(row.selectFirst("div.cell.grade span.grade"))
                .ifBlank { text(row.selectFirst("div.cell.grade")) }
            // 状态：tooltip 里的 Completed / Needs Grading；没有 tooltip 时退回 img 的 alt
            val status = text(row.selectFirst("div.cell.gradeStatus span.tooltip"))
                .ifBlank { row.selectFirst("div.cell.gradeStatus img.tooltip-icon")?.attr("alt").orEmpty() }

            // 部分版本的 BB 在行属性里直接给 duedate（毫秒时间戳），
            // 9223372036854775807 / 0 是「无截止」的哨兵值，必须排除。
            val dueFromAttr = parseDueTimestamp(row.attr("duedate"))
            // 另一部分版本把截止写成文本：「Due: Oct 28, 2026」
            val dueFromText = DUE_TEXT_REGEX.find(text(row))?.groupValues?.get(1)?.trim().orEmpty()
            val detailUrl = DETAIL_URL_REGEX.find(row.html())?.groupValues?.get(1).orEmpty()

            BbGrade(
                id = row.id().ifBlank { itemName },
                item = itemName,
                category = category,
                lastActivity = lastActivity,
                activityType = activityType,
                grade = if (grade == "-") "" else grade,
                status = status,
                isCalculated = row.hasClass("calculatedRow"),
                dueDate = dueFromAttr.ifBlank { dueFromText },
                detailUrl = detailUrl,
            )
        }

        return BbGradeSheet(courseId = courseId, courseName = courseName, items = items)
    }

    /**
     * 把 BB 行上的 duedate 毫秒时间戳转成可读日期。
     *
     * 0 和 Long.MAX_VALUE（9223372036854775807）是「无截止」的哨兵值，返回空串。
     */
    private fun parseDueTimestamp(raw: String): String {
        val ts = raw.trim().toLongOrNull() ?: return ""
        if (ts <= 0L || ts >= MAX_SENTINEL_THRESHOLD) return ""
        return runCatching {
            val fmt = java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            fmt.format(java.util.Date(ts))
        }.getOrDefault("")
    }

    /** duedate 的哨兵上界：超过它必然是「无截止」而不是真实时间 */
    private const val MAX_SENTINEL_THRESHOLD = 9_000_000_000_000_000_000L

    /** 行文本里的 "Due: Oct 28, 2026" 形式 */
    private val DUE_TEXT_REGEX =
        Regex("Due[\\s:：]*([A-Za-z]{3,9}\\s+[0-9]{1,2},\\s*[0-9]{4})")

    /** 成绩行里 onClick 携带的站内地址（如 mygrades.loadContentFrame('/webapps/...')） */
    private val DETAIL_URL_REGEX =
        Regex("/webapps/[A-Za-z0-9_\\-/]+[.](?:jsp|do|form)[?][^\\s\\)\\]']*")

    // —————————————————————————— ⑦ 课堂回放 ——————————————————————————

    private val PANOPTO_URL_REGEX =
        Regex("https?://[^" + QUOTE_AND_WS + "]*panopto[^" + QUOTE_AND_WS + "]*", RegexOption.IGNORE_CASE)

    /** 正则字符类里「引号 + 空白」的排除集合（用常量拼，避免源码里出现三引号包裹的裸引号） */
    private const val QUOTE_AND_WS = "\u0027\u0022\\s>"

    /**
     * 从任意课程页面里找 Panopto 入口与回放链接。
     *
     * 本 BB 班级课程菜单里通常**没有**独立的 Panopto 工具项，
     * 回放都是以外部链接形式贴在课程内容 / 通知正文里
     * （形如 `https://bham.cloud.panopto.eu/Panopto/Pages/Viewer.aspx?id=...`）。
     * 所以这里同时看：课程菜单 -> 内容页所有链接 -> 通知正文链接。
     */
    fun parseRecordings(
        courseId: String,
        home: BbCourseHome,
        pageHtmls: List<String>,
    ): BbRecordingSheet {
        val entry = findPanoptoMenuUrl(home).orEmpty()

        val found = LinkedHashMap<String, BbRecording>()

        fun collect(doc: Document, source: String) {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@forEach
                val isPanoptoLink = href.contains("panopto", ignoreCase = true) &&
                    (href.startsWith("http") || href.contains("ppto-"))
                if (!isPanoptoLink) return@forEach
                val abs = when {
                    href.startsWith("http") -> href
                    href.startsWith("/") -> BbCourse.BASE + href
                    else -> BbCourse.BASE + "/" + href
                }
                // 图片（logo）不算回放
                if (abs.endsWith(".png") || abs.endsWith(".gif") || abs.endsWith(".jpg")) return@forEach
                val title = text(a).ifBlank { text(a.selectFirst("span")) }.ifBlank { "回放" }
                found.putIfAbsent(abs, BbRecording(title = title, url = abs, source = source))
            }
        }

        pageHtmls.forEachIndexed { index, html ->
            if (html.isBlank()) return@forEachIndexed
            val doc = parse(html)
            collect(doc, if (index == 0) "课程内容" else "通知")
        }

        return BbRecordingSheet(
            courseId = courseId,
            panoptoEntry = entry,
            recordings = found.values.toList(),
        )
    }

    /** 从纯文本里再兜一次 Panopto 链接（有些地址写在 onclick / 脚本变量里） */
    fun findPanoptoUrlsInText(text: String): List<String> =
        PANOPTO_URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')').trim('\"') }
            .filter { u -> u.endsWith(".png").not() }
            .distinct()
            .toList()
}
