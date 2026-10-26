package cn.jnu.hub.feature.stuit

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 学工系统 HTML 解析器。
 *
 * 学工是传统 JSP，页面结构没有对外承诺，所以这里的原则是：
 * **宁可少解析一点，也不要因为某个标签改名就整页失败**。
 * 所有函数都是纯函数（只依赖 Jsoup DOM，不依赖 Android），便于单独排查。
 */
object StuitHtmlParser {

    // —————————————————————————— 通用工具 ——————————————————————————

    /** 单元格文本：&nbsp; 会被 Jsoup 解析成 \u00A0，这里统一压成普通空格 */
    fun normalize(raw: String?): String =
        raw.orEmpty()
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    fun text(el: Element?): String = normalize(el?.text())

    /**
     * 「64.54（31 | 44.29%）」/「64.54 (31 | 44.29%)」/「—」→ RankedValue
     * 括号是可选的：页头「班级排名」写作「20 | 58.82%」，排名表单元格才带括号。
     */
    private val RANKED_REGEX =
        Regex("""([\d.]+)\s*[（(]?\s*(\d+)\s*[|｜]\s*([\d.]+)\s*%\s*[)）]?""")
    private val NUMBER_REGEX = Regex("""-?\d+(?:\.\d+)?""")

    /** 无括号的「名次 | 百分比」：20 | 58.82% */
    private val RANK_INLINE_REGEX = Regex("""(\d+)\s*[|｜]\s*([\d.]+)\s*%""")

    fun parseRanked(raw: String): RankedValue? {
        val s = normalize(raw)
        if (s.isBlank() || s == "—" || s == "-" || s == "--") return null
        RANKED_REGEX.find(s)?.let { m ->
            val value = m.groupValues[1].toDoubleOrNull() ?: return@let
            return RankedValue(
                value = value,
                rank = m.groupValues[2].toIntOrNull(),
                percentile = m.groupValues[3].toDoubleOrNull(),
                raw = s,
            )
        }
        val number = NUMBER_REGEX.find(s)?.value?.toDoubleOrNull() ?: return null
        return RankedValue(value = number, raw = s)
    }

    /** 从任意文本抠出分数；"-" / "—" / "" 返回 null */
    fun parseScore(raw: String?): Double? {
        val s = normalize(raw)
        if (s.isBlank()) return null
        val cleaned = s.replace(",", "")
        return NUMBER_REGEX.find(cleaned)?.value?.toDoubleOrNull()
    }

    /** 把所有 <table> 转成二维字符串矩阵（已合并 rowspan/colspan 不重要，这里按原始 td 展开） */
    fun tables(doc: Document): List<TableGrid> =
        doc.select("table").mapNotNull { table ->
            val rows = table.select("tr").map { tr ->
                tr.select("td,th").map { cell -> text(cell) }
            }.filter { it.isNotEmpty() }
            if (rows.isEmpty()) null else TableGrid(rows)
        }

    /** 二维表：便于按「表头关键字」找列 */
    class TableGrid(val rows: List<List<String>>) {
        val header: List<String> get() = rows.firstOrNull().orEmpty()
        val body: List<List<String>> get() = if (rows.size > 1) rows.drop(1) else emptyList()

        fun headerIndex(vararg keys: String): Int {
            val h = header
            for (k in keys) {
                val i = h.indexOfFirst { it.contains(k) }
                if (i >= 0) return i
            }
            return -1
        }

        fun cell(row: List<String>, index: Int): String =
            if (index in row.indices) row[index] else ""
    }

    // —————————————————————————— ① 综合测评 ——————————————————————————

    /** 三大块标题 */
    private val BASIC_KEY = "基础性素养"
    private val ACADEMIC_KEY = "学业成绩"
    private val COMPREHENSIVE_KEY = "综合素养"
    private val SECTION_KEYS = listOf(BASIC_KEY, ACADEMIC_KEY, COMPREHENSIVE_KEY)

    fun parseEvaluation(doc: Document): StuitEvaluation {
        val fullText = text(doc.body())
        val header = parseEvalHeader(doc, fullText)

        val basicItems = parseBasicItems(doc)
        val courses = parseEvalCourses(doc)
        val fiveEdu = parseFiveEdu(doc)

        // 三大块分值：优先从「【学业成绩】80.50」这种标题里抠
        val basicScore = sectionScore(doc, BASIC_KEY) ?: sumOrNull(basicItems.map { it.total })
        val academicScore = sectionScore(doc, ACADEMIC_KEY) ?: weightedCourseScore(courses)
        // 综合素养总分页面直接给出 20.80。不能拿五育相加 ——
        // 那是加权算式（德育+2×智育+...+劳育），简单相加会得到 51.60 这种错数。
        val comprehensiveScore = sectionScore(doc, COMPREHENSIVE_KEY)
            ?: sumOrNull(fiveEdu.map { it.score })

        return StuitEvaluation(
            header = header,
            templateName = parseTemplateName(fullText),
            basicScore = basicScore,
            basicItems = basicItems,
            academicScore = academicScore,
            academicCourses = courses,
            comprehensiveScore = comprehensiveScore,
            fiveEdu = fiveEdu,
        )
    }

    private fun sumOrNull(values: List<Double?>): Double? {
        val numbers = values.filterNotNull()
        return if (numbers.isEmpty()) null else numbers.sum()
    }

    /**
     * 读取某个板块的「当前得分」。
     *
     * 三个板块（基础性素养 / 学业成绩 / 综合素养）结构完全一致（2026-10-23 真机实测）：
     *
     *   <div class="overview-table">
     *     <div class="title" id="626">基础性素养</div>
     *     <div class="table-content">
     *       <div class="illustration">
     *         <div class="grade"><div class="number">100.00</div><div>当前得分</div></div>
     *
     * 所以统一按「找 title -> 在同一个 overview-table 块里取第一个 number」来读，
     * 比原来的「在纯文本里往后找数字」可靠得多（纯文本会串到说明文字里的数字）。
     */
    fun sectionScore(doc: Document, key: String): Double? {
        val title = doc.select("div.title").firstOrNull { text(it).contains(key) }
            ?: return null

        // ① 在 title 的父容器（overview-table）里找第一个 number
        val container = title.parent()
        container?.selectFirst("div.grade div.number")?.let { n ->
            parseScore(text(n))?.let { return it }
        }
        // ② 退一步：title 之后的兄弟节点
        var node = title.nextElementSibling()
        var guard = 0
        while (node != null && guard < 6) {
            node.selectFirst("div.number")?.let { n ->
                parseScore(text(n))?.let { return it }
            }
            node = node.nextElementSibling()
            guard++
        }
        return null
    }

    private fun parseTemplateName(fullText: String): String =
        Regex("""(\d{4}-\d{4}学年[^\s，,。]{0,8}测评)""").find(fullText)?.value.orEmpty()

    /** 头部：学号/姓名/性别/学院/专业/年级/自然班/总分/班级排名 */
    private fun parseEvalHeader(doc: Document, fullText: String): StuitEvalHeader {
        // 学工页面普遍用「标签单元格 + 相邻值单元格」的布局：<td>学号</td><td>2024...</td>
        val pairs = labelValuePairs(doc)

        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            pairs.firstOrNull { it.first.replace("：", "").replace(":", "").endsWith(k) && it.second.isNotBlank() }
                ?.second
        }.orEmpty()

        val totalScore = parseScore(
            pick("综合测评总分", "测评总分", "总分")
                .ifBlank { Regex("""综合测评总分[\s:：]*([\d.]+)""").find(fullText)?.groupValues?.get(1).orEmpty() },
        )

        var classRank: Int? = null
        var classPercent: Double? = null
        val rankRaw = pick("班级排名", "排名")
        if (rankRaw.isNotBlank()) {
            // 页头形如「20 | 58.82%」：第一个数就是名次，第二个带 % 的是百分比
            RANK_INLINE_REGEX.find(rankRaw)?.let { m ->
                classRank = m.groupValues[1].toIntOrNull()
                classPercent = m.groupValues[2].toDoubleOrNull()
            } ?: run {
                classRank = NUMBER_REGEX.find(rankRaw)?.value?.toDoubleOrNull()?.toInt()
            }
        }
        if (classRank == null) {
            Regex("""班级排名[\s:：]*(\d+)\s*[|｜]\s*([\d.]+)\s*%""").find(fullText)?.let {
                classRank = it.groupValues[1].toIntOrNull()
                classPercent = it.groupValues[2].toDoubleOrNull()
            }
        }

        return StuitEvalHeader(
            studentId = pick("学号"),
            name = pick("姓名"),
            gender = pick("性别"),
            college = pick("学院", "测评学院"),
            major = pick("专业", "测评专业"),
            grade = pick("年级", "测评年级"),
            className = pick("自然班", "班级"),
            totalScore = totalScore,
            classRank = classRank,
            classPercentile = classPercent,
        )
    }

    /**
     * 全文档的「标签 → 值」对。
     * 兼容两种写法：
     *   1) 同一行两个 td：[学号, 2024...] 交替排列
     *   2) 同一行两个 span/div：<span>学号：</span><span>2024...</span>
     */
    fun labelValuePairs(doc: Document): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()

        // 写法 1：td 交替
        doc.select("tr").forEach { tr ->
            val cells = tr.select("td,th").map(::text).filter { it.isNotEmpty() }
            var i = 0
            while (i + 1 < cells.size) {
                val label = cells[i]
                val value = cells[i + 1]
                if (looksLikeLabel(label)) {
                    result += label to value
                    i += 2
                } else {
                    i += 1
                }
            }
        }

        // 写法 2：冒号分隔的纯文本行（"学号：2024123456 姓名：张同学"）
        if (result.size < 5) {
            Regex("""([\u4e00-\u9fa5A-Za-z]{2,10})\s*[：:]\s*([^\s：:]{1,40})""")
                .findAll(text(doc.body()))
                .forEach { m ->
                    val label = m.groupValues[1].trim()
                    val value = m.groupValues[2].trim()
                    if (looksLikeLabel(label) && value.isNotBlank()) result += label to value
                }
        }
        return result.distinct()
    }

    private fun looksLikeLabel(s: String): Boolean {
        val t = s.replace("：", "").replace(":", "").trim()
        if (t.isEmpty() || t.length > 12) return false
        if (NUMBER_REGEX.matches(t)) return false
        return t.any { it in '\u4e00'..'\u9fa5' }
    }

    /** 基础性素养明细表：序号 | 测评项目 | 分值 | 说明 | 学生评分 | 班级评分 | 学院评分 | 总分 */
    private fun parseBasicItems(doc: Document): List<StuitBasicItem> {
        val grid = tables(doc).firstOrNull { g ->
            val h = g.header.joinToString("")
            h.contains("学生评分") || (h.contains("测评项目") && h.contains("班级评分"))
        } ?: tables(doc).firstOrNull { g ->
            g.header.joinToString("").let { it.contains("测评项目") && it.contains("分值") }
        } ?: return emptyList()

        val iIndex = grid.headerIndex("序号")
        val iTitle = grid.headerIndex("测评项目")
        val iRange = grid.headerIndex("分值")
        val iStudent = grid.headerIndex("学生评分", "学生")
        val iClass = grid.headerIndex("班级评分", "班级")
        val iCollege = grid.headerIndex("学院评分", "学院")
        val iTotal = grid.headerIndex("总分")

        return grid.body.mapIndexedNotNull { idx, row ->
            val title = grid.cell(row, iTitle)
            if (title.isBlank()) return@mapIndexedNotNull null
            StuitBasicItem(
                index = grid.cell(row, iIndex).ifBlank { (idx + 1).toString() },
                title = title.removeSurrounding("【", "】"),
                range = grid.cell(row, iRange),
                studentScore = parseScore(grid.cell(row, iStudent)),
                classScore = parseScore(grid.cell(row, iClass)),
                collegeScore = parseScore(grid.cell(row, iCollege)),
                total = parseScore(grid.cell(row, iTotal)),
            )
        }.filter { it.title.isNotBlank() }
    }

    /** 学业成绩课程表：课程名称 | 课程类型 | 学期 | 成绩 | 学分 | 是否及格 */
    private fun parseEvalCourses(doc: Document): List<StuitEvalCourse> {
        val grid = tables(doc).firstOrNull { g ->
            val h = g.header.joinToString("")
            h.contains("课程名称") && (h.contains("学分") || h.contains("成绩"))
        } ?: return emptyList()

        val iName = grid.headerIndex("课程名称", "课程")
        val iType = grid.headerIndex("课程类型")
        val iTerm = grid.headerIndex("学期")
        val iScore = grid.headerIndex("成绩")
        val iCredit = grid.headerIndex("学分")
        val iPass = grid.headerIndex("是否及格", "及格")

        return grid.body.mapNotNull { row ->
            val name = grid.cell(row, iName)
            if (name.isBlank()) return@mapNotNull null
            StuitEvalCourse(
                name = name,
                type = grid.cell(row, iType),
                term = grid.cell(row, iTerm),
                score = grid.cell(row, iScore),
                credit = grid.cell(row, iCredit),
                passed = grid.cell(row, iPass),
            )
        }
    }

    private fun weightedCourseScore(courses: List<StuitEvalCourse>): Double? {
        val pairs = courses.mapNotNull { c ->
            val s = parseScore(c.score) ?: return@mapNotNull null
            val credit = parseScore(c.credit) ?: return@mapNotNull null
            s to credit
        }
        val total = pairs.sumOf { it.second }
        if (total <= 0.0) return null
        return pairs.sumOf { it.first * it.second } / total
    }

    /*
     * 五育解析（2026-10-23 依据真机抓到的 HTML 重写）。
     *
     * 真实结构长这样：
     *
     *   <div class="overview-table">
     *     <div class="title" id="629">综合素养</div>
     *     <div class="table-content">
     *       <div class="illustration">
     *         <div class="grade">
     *           <div class="number">20.80</div>     <- 综合素养总分
     *           <div>当前得分</div>
     *         </div>
     *       </div>
     *       <div class="panel-heading">01 德育表现积分 [当前得分：2.80] ...</div>
     *       <div class="panel-heading">02 智育表现积分 [当前得分：8.00] ...</div>
     *       ...
     *
     * 关键：五育分数写在 panel-heading 的「[当前得分：X]」里，
     * 而「德育表现积分」里面还嵌着「德育表现加分」等子项，
     * 所以必须精确匹配「<名称>表现积分」而不是随便找名称后面的数字，
     * 否则会抓到子项（例如「德育表现加分 2.80」抓到别的数）或说明里的数字。
     */

    private val FIVE_EDU_NAMES = listOf("德育", "智育", "体育", "美育", "劳育")

    /** 「[当前得分：2.80]」里的分数 */
    private val CURRENT_SCORE_REGEX = Regex("""当前得分[：:]\s*(-?\d+(?:\.\d+)?)""")

    private fun parseFiveEdu(doc: Document): List<StuitFiveEdu> {
        val out = mutableListOf<StuitFiveEdu>()

        // 主路径：panel-heading 里精确匹配「<名称>表现积分」+「[当前得分：X]」
        val headings = doc.select("div.panel-heading").map { text(it) }

        for (name in FIVE_EDU_NAMES) {
            val target = name + "表现积分"
            val heading = headings.firstOrNull {
                it.contains(target) && it.contains("当前得分")
            }
            var score = heading?.let { h ->
                CURRENT_SCORE_REGEX.find(h)?.groupValues?.get(1)?.toDoubleOrNull()
            }

            // 退一步：整页文本里「<名称>表现积分 [当前得分：X]」
            if (score == null) {
                val full = text(doc.body())
                val re = Regex(Regex.escape(target) + """.{0,30}?当前得分[：:]\s*(-?\d+(?:\.\d+)?)""")
                score = re.find(full)?.groupValues?.get(1)?.toDoubleOrNull()
            }

            if (score != null) out += StuitFiveEdu(name = name, score = score)
        }

        // 参考满量程：取各项最大值向上取整到一个合理刻度，供横条图定比例
        val max = out.mapNotNull { it.score }.maxOrNull() ?: 0.0
        val scale = when {
            max <= 0.0 -> 0.0
            max <= 5.0 -> 5.0
            max <= 10.0 -> 10.0
            max <= 15.0 -> 15.0
            max <= 20.0 -> 20.0
            else -> max
        }
        return out.map { it.copy(maxScore = scale) }
    }
    // —————————————————————————— ② 专业排名 ——————————————————————————

    private val RANK_COLUMNS = listOf(
        "测评模板", "测评学院", "测评专业", "测评年级",
        "综测总分", "基础性素养", "学业成绩", "综合素养",
        "德育", "智育", "体育", "美育", "劳育",
    )

    fun parseRanking(doc: Document): List<StuitRankRow> {
        val grids = tables(doc)

        // 优先找「表头包含综测总分」的表；退一步找列数 >= 9 的最大表
        val grid = grids.firstOrNull { g -> g.header.any { it.contains("综测总分") || it.contains("综测") } }
            ?: grids.maxByOrNull { it.header.size }

        if (grid == null) return emptyList()

        val idx = RANK_COLUMNS.associateWith { name ->
            grid.headerIndex(name)
        }

        fun col(row: List<String>, name: String): String {
            var i = idx[name] ?: -1
            if (i < 0 || i >= row.size) {
                // 表头没命中时按列序兜底
                i = RANK_COLUMNS.indexOf(name)
            }
            return grid.cell(row, i)
        }

        return grid.body.mapNotNull { row ->
            // 跳过「暂无数据」之类的提示行
            val joined = row.joinToString("")
            if (joined.isBlank() || joined.contains("暂无") || joined.contains("没有查询到")) return@mapNotNull null

            val total = parseRanked(col(row, "综测总分"))
            if (row.size < 5 && total == null) return@mapNotNull null

            StuitRankRow(
                template = col(row, "测评模板"),
                college = col(row, "测评学院"),
                major = col(row, "测评专业"),
                grade = col(row, "测评年级"),
                total = total,
                basic = parseRanked(col(row, "基础性素养")),
                academic = parseRanked(col(row, "学业成绩")),
                comprehensive = parseRanked(col(row, "综合素养")),
                moral = parseRanked(col(row, "德育")),
                intellectual = parseRanked(col(row, "智育")),
                physical = parseRanked(col(row, "体育")),
                aesthetic = parseRanked(col(row, "美育")),
                labor = parseRanked(col(row, "劳育")),
            )
        }
    }

    // —————————————————————————— ③ 成绩管理 ——————————————————————————

    /** 表头：学号|姓名|课程名称|课程类型|学期|成绩|学分|绩点成绩|是否及格|是否重修|是否补考|是否缓考|审核状态 */
    fun parseGrades(doc: Document): List<StuitGrade> {
        val grid = tables(doc).firstOrNull { g ->
            val h = g.header.joinToString("")
            h.contains("课程名称") && (h.contains("成绩") || h.contains("学分"))
        } ?: tables(doc).maxByOrNull { g -> g.header.count { it.contains("课程") } }
            ?: return emptyList()

        if (grid.body.isEmpty()) return emptyList()

        val iXh = grid.headerIndex("学号")
        val iName = grid.headerIndex("姓名")
        val iCourse = grid.headerIndex("课程名称", "课程")
        val iType = grid.headerIndex("课程类型", "类型")
        val iTerm = grid.headerIndex("学期")
        val iScore = grid.headerIndex("成绩")
        val iCredit = grid.headerIndex("学分")
        val iGpa = grid.headerIndex("绩点")
        val iPass = grid.headerIndex("是否及格")
        val iRetake = grid.headerIndex("重修")
        val iMakeup = grid.headerIndex("补考")
        val iPostpone = grid.headerIndex("缓考")
        val iAudit = grid.headerIndex("审核")

        // 关键列缺失说明这根本不是成绩表
        if (iScore < 0 && iCredit < 0) return emptyList()

        return grid.body.mapNotNull { row ->
            val course = grid.cell(row, iCourse)
            if (course.isBlank()) return@mapNotNull null
            if (course.contains("合计") || course.contains("平均")) return@mapNotNull null
            StuitGrade(
                studentId = grid.cell(row, iXh),
                name = grid.cell(row, iName),
                course = course,
                courseType = grid.cell(row, iType),
                term = grid.cell(row, iTerm),
                score = grid.cell(row, iScore),
                credit = grid.cell(row, iCredit),
                gpa = grid.cell(row, iGpa),
                passed = grid.cell(row, iPass),
                retake = grid.cell(row, iRetake),
                makeup = grid.cell(row, iMakeup),
                postpone = grid.cell(row, iPostpone),
                auditStatus = grid.cell(row, iAudit),
            )
        }
    }

    // —————————————————————————— ④ 基础信息 ——————————————————————————

    /**
     * 基本信息解析（2026-10-23 依据真机 HTML 重写）。
     *
     * 真实结构是 Bootstrap 表单布局，**不是表格**：
     *
     *   <div id="studentId" class="form-group col-xs-6">
     *     <div class="col-xs-5 control-label list_cell text-right">
     *       <span style="color: red;">*</span>学号：
     *     </div>
     *     <div class="col-xs-7 list_cell">
     *       <div class="plaintext"> 2024123456 </div>
     *     </div>
     *   </div>
     *
     * 之前用「表格 tr/td 配对」解析，读到的是页面里模板区的表头（原院校/审核状态…），
     * 所以显示出一堆假字段。现在改成按 form-group 精确配对 label / plaintext。
     */
    fun parseBasicInfo(doc: Document): StuitBasicInfo {
        val fields = mutableListOf<StuitInfoField>()

        // 主路径：div.form-group -> .control-label（标签） + .plaintext（值）
        doc.select("div.form-group").forEach { group ->
            val labelEl = group.selectFirst(".control-label")
                ?: group.selectFirst("label")
                ?: return@forEach
            val valueEl = group.selectFirst(".plaintext")
                ?: group.selectFirst(".list_cell:nth-child(2)")
                ?: return@forEach

            val label = cleanLabel(text(labelEl))
            val value = text(valueEl)
            if (label.isBlank() || value.isBlank()) return@forEach
            // 跳过占位/空值标记
            if (value == "—" || value == "-") return@forEach
            fields += StuitInfoField(label = label, value = value)
        }

        // 兜底：如果没有 form-group，再退回原来的表格配对
        if (fields.isEmpty()) {
            labelValuePairs(doc)
                .map { (l, v) -> StuitInfoField(label = cleanLabel(l), value = v) }
                .filter { it.label.isNotBlank() && it.value.isNotBlank() && it.label != it.value }
                .forEach { fields += it }
        }

        return StuitBasicInfo(fields = fields.distinctBy { it.label })
    }

    private fun cleanLabel(label: String): String =
        label.replace("：", "")
            .replace(":", "")
            .replace("*", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 头像：GET /student/basicinfo/attachDownload.jsp
     * 页面里通常有 <img src=".../attachDownload.jsp?id=xx"> 或 onclick 里的 url，
     * 这里尝试从任意属性里找出指向 attachDownload 的地址。
     */
    fun findAvatarUrl(doc: Document): String? {
        val candidates: List<String> =
            doc.select("img[src]").map { it.attr("src") } +
                doc.select("[onclick]").map { it.attr("onclick") } +
                doc.select("a[href]").map { it.attr("href") }
        val hit = candidates.firstOrNull { it.contains("attachDownload", ignoreCase = true) }
            ?: return null
        val token = hit.split("'", "\"", "(", ")", " ", "\t", "\n")
            .firstOrNull { it.contains("attachDownload", ignoreCase = true) }
            ?: return null
        return when {
            token.startsWith("http://") || token.startsWith("https://") -> token
            token.startsWith("/") -> token
            else -> "/".plus(token)
        }
    }
}