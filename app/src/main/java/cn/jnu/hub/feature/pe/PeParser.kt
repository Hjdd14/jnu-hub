package cn.jnu.hub.feature.pe

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 体测系统 HTML 解析（全部页面共用的 Jsoup 规则）。
 *
 * 站点是典型的 ASP.NET WebForms + layui 模板，结构特征：
 * - 数据不靠 class 定位，而是靠稳定的 id（lblHeight / lblFiftyMeterRace_SingleScore …）
 * - 表格行 = 测试项目；一行里依次是 项目名 / 成绩 / 得分+等级 / 分值
 * - 身高 / 体重 两行用 rowspan=2 合并了「得分/等级」与「分值」，各自只有 2 个单元格
 * - 页面头部固定有 lblStudentName / lblStudentNO / lblClassName
 *
 * 实测要点（见 docs/02_接口台账.md E 节）：
 *   <tr>
 *     <td>肺活量</td>
 *     <td><span id="lblVitalCapacity">3200</span>毫升</td>
 *     <td><span ...>60</span><span ...>及格</span></td>
 *     <td><span ...>9</span></td>
 *   </tr>
 * 所以一个单元格的「值」必须取它的完整文本，不能按列名猜位置。
 *
 * 所有函数都是纯函数（String → 模型），不依赖网络与 Android，便于排查与复用。
 */
object PeParser {

    // ———————————————————————————— 底层 ————————————————————————————

    private fun doc(html: String): Document = Jsoup.parse(html)

    /** 抽出 tr 下所有 td/th 的文本 */
    private fun cells(tr: Element): List<String> = tr.children()
        .filter { it.tagName() == "td" || it.tagName() == "th" }
        .map { clean(it.text()) }

    /**
     * 抽出 tr 下一行单元格的文本，但**保留单元格内部原有的空白分隔**。
     *
     * 成绩表里的「得分/等级」单元格是两个相邻 span：
     *   <td><span>60</span><span>及格</span></td>
     * 直接取 td.text() 会得到没有空格的「60及格」，无法拆出分数与等级。
     * 所以这里逐个子节点拼接，并把元素边界当作分隔符。
     */
    private fun cellTexts(tr: Element): List<String> = tr.children()
        .filter { it.tagName() == "td" || it.tagName() == "th" }
        .map { cell ->
            val sb = StringBuilder()
            cell.childNodes().forEach { node ->
                val part = if (node is Element) node.text() else node.toString()
                if (part.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(part)
                }
            }
            clean(if (sb.isEmpty()) cell.text() else sb.toString())
        }

    /** 去掉 nbsp、全角空格与首尾空白，内部多空格压成一个 */
    fun clean(raw: String?): String {
        if (raw == null) return ""
        return raw
            .replace('\u00a0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 页面是否其实是登录页（会话失效） */
    fun looksLikeLoginPage(text: String): Boolean =
        text.contains("/User/Login.aspx", ignoreCase = true) &&
            (text.contains("vercode", ignoreCase = true) || text.contains("登 入"))

    /** 抽 __VIEWSTATE / __VIEWSTATEGENERATOR */
    fun parseViewState(html: String): Pair<String, String> {
        val d = doc(html)
        val vs = d.selectFirst("input[name=__VIEWSTATE]")?.attr("value").orEmpty()
        val vsg = d.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value").orEmpty()
        return vs to vsg
    }

    /** 抽某个隐藏域的值 */
    fun parseHiddenValue(html: String, name: String): String =
        doc(html).selectFirst("input[name=" + name + "]")?.attr("value").orEmpty()

    /**
     * 抽验证码图片地址：按 id 优先，其次找 src 里含 verify / code / captcha 的 img。
     */
    fun parseCaptchaUrl(html: String): String? {
        val d = doc(html)
        // imgCheckCode / GetCheckCode.aspx 是师生云的实际命名（2026-10 实测），
        // 之前的候选没有覆盖它，导致登录页永远解析不到验证码，
        // 又被上层误报成「体测登录已过期」。
        val byId = d.selectFirst(
            "img#imgCheckCode, img#imgVerify, img#imgCode, img#imgVercode, " +
                "img#verifyImg, img#checkCode, img#captchaImg, img#imgCaptcha",
        )
        byId?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }

        // 二级：src 关键词（站点常见命名 VerifyCode/CheckCode/VerCode/Captcha）
        d.select("img").firstNotNullOfOrNull { img ->
            val src = img.attr("src")
            val lower = src.lowercase()
            val hit = lower.contains("verify") || lower.contains("vercode") ||
                lower.contains("checkcode") || lower.contains("captcha") ||
                lower.contains("getcode") || lower.contains("validcode") ||
                lower.contains("codeimg") || lower.contains("getcheckcode")
            if (src.isNotBlank() && hit) src else null
        }?.let { return it }

        // 三级兜底：取离验证码输入框最近、且不像站点装饰图的 img。
        // 站点若把图片接口命名成 Pic.aspx 这类无关键词的地址，前两级都会落空。
        val input = d.selectFirst("input[name=vercode]") ?: return null
        var scope: Element? = input
        repeat(4) {
            scope = scope?.parent()
            val img = scope?.select("img")?.firstOrNull { img ->
                val src = img.attr("src")
                src.isNotBlank() && !looksLikeDecorativeImage(src)
            }
            if (img != null) return img.attr("src")
        }
        return null
    }

    /** 站点里的装饰性图片（logo / 背景 / 头像），不能当成验证码 */
    private fun looksLikeDecorativeImage(src: String): Boolean {
        val s = src.lowercase()
        return s.contains("logo") || s.contains("banner") || s.contains("headimg") ||
            s.contains("sex") || s.contains("bg") || s.contains("icon") ||
            s.contains("favicon")
    }

    /** 登录失败原因；成功返回 null */
    fun parseLoginFailure(html: String): String? {
        val d = doc(html)
        val candidates = mutableListOf<String>()

        // 实际 id 是 lblMessage（师生云实测），之前的 [id$=lblMsg] 匹配不上，
        // 导致服务端的「请重新输入验证码！」这类提示被丢掉，用户只看到验证码刷新、没有任何文字反馈。
        d.select(
            "span[id$=lblMessage], span[id$=lblError], span[id$=lblMsg], " +
                "span[id$=lblTip], div[id$=divMessage1], .layui-layer-content",
        ).forEach { el ->
            val t = clean(el.text())
            if (t.isNotBlank()) candidates += t
        }

        // 页面里内联 alert('...') 的错误提示
        Regex("alert\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)").findAll(html).forEach { m ->
            candidates += clean(m.groupValues[1])
        }

        val keywords = listOf("验证码", "密码", "账号", "不存在", "错误", "失败", "过期", "锁定")
        candidates.firstOrNull { c -> keywords.any { c.contains(it) } }?.let { return it }

        // 仍停在登录页但没有任何可读提示
        if (d.selectFirst("input[name=vercode]") != null) {
            return "登录失败，请检查账号、密码与验证码"
        }
        return null
    }

    // ———————————————————————————— 学生头部 ————————————————————————————

    fun parseStudent(d: Document): PeStudentInfo = PeStudentInfo(
        name = clean(d.selectFirst("#lblStudentName")?.text()),
        studentNo = clean(d.selectFirst("#lblStudentNO")?.text()),
        className = clean(d.selectFirst("#lblClassName")?.text()),
    )

    /** 页面里「暂未开放…！」的提示框 */
    private fun parseNotice(d: Document): String =
        d.select("div").firstOrNull { el ->
            val t = clean(el.ownText())
            t.startsWith("暂未开放") && t.length < 40
        }?.let { clean(it.ownText()) }.orEmpty()

    // ———————————————————————————— 成绩页 ————————————————————————————

    /** 项目名 → 单位（用于把「3200 毫升」这类值规整成「3200 毫升」） */
    private val UNITS = listOf("厘米", "公斤", "毫升", "分.秒", "秒", "次")

    /**
     * 单元格 → 成绩值。
     * 站点把数值放在 span、单位放在 span 外，[clean] 后天然是「3200 毫升」，
     * 但空值时只剩单位（如「次」「分.秒」），这时要当成空成绩而不是「次」。
     */
    private fun valueOf(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val unit = UNITS.firstOrNull { t == it } ?: return t
        return if (t == unit) "" else t
    }

    /**
     * 「60 及格」→ (60, 及格)。
     * 兼底容错「60及格」这种没有空格的粘连形式（见 [cellTexts]）。
     */
    private fun splitScoreGrade(raw: String): Pair<String, String> {
        var score = ""
        var grade = ""
        raw.split(' ').filter { it.isNotBlank() }.forEach { token ->
            if (token.isChineseGrade()) {
                grade = token
            } else {
                val g = GRADES.firstOrNull { token.startsWith(it) }
                if (g != null) {
                    grade = g
                    val head = token.removePrefix(g).trim()
                    if (head.isNumberLike() && score.isBlank()) score = head
                } else if (token.isNumberLike() && score.isBlank()) {
                    score = token
                }
            }
        }
        return score to grade
    }

    /**
     * 成绩表解析。
     *
     * 实测行结构（PHTest_StudentScore_H.aspx?DataYear=2025）：
     *   4 格：身高 | 181.2 厘米 | 60 肥胖 | 9        ← 身高/体重合并了后两列，取第一行
     *        体重 | 105.4 公斤                        ← rowspan 的附属行
     *   4 格：肺活量 | 3200 毫升 | 60 及格 | 9
     *   3 格：标准分 | 不及格 | 49 分                 ← 汇总行
     *
     * 也就是说：2 格的行是 rowspan 附属行（跳过），3 格是汇总行，4 格是普通项目。
     */
    fun parseScore(html: String, year: String = ""): PeScoreReport {
        val d = doc(html)
        val titleYear = Regex("(\\d{4})年体测成绩")
            .find(clean(d.title()) + " " + d.body().text())
            ?.groupValues?.get(1)
            .orEmpty()

        val items = mutableListOf<PeScoreItem>()
        var standardGrade = ""
        var standardScore = ""
        var totalGrade = ""
        var totalScore = ""

        d.select("table").forEach { table ->
            val isScoreTable = table.selectFirst("#lblTotalScore") != null ||
                table.selectFirst("#lblFiftyMeterRace") != null
            if (!isScoreTable) return@forEach

            table.select("tr").forEach { tr ->
                val c = cellTexts(tr)
                if (c.size < 3) return@forEach
                val name = c[0]

                // 汇总行：标准分 / 总分（3 格：标题 | 等级 | 分数）
                if (name == "标准分" || name == "总分") {
                    val grade = c.drop(1).firstOrNull { it.isChineseGrade() }.orEmpty()
                    val score = c.drop(1).firstOrNull { it.isNumberLike() }.orEmpty()
                    if (name == "总分") {
                        totalGrade = grade
                        totalScore = score
                    } else {
                        standardGrade = grade
                        standardScore = score
                    }
                    return@forEach
                }

                if (name == "测试项目" || name.isBlank()) return@forEach
                if (c[0].none { it in '一'..'龥' }) return@forEach

                val value = valueOf(c.getOrElse(1) { "" })
                // 合并单元格的项目（身高 / 体重）只有 2 格，上面已经跳过；这里一定是 4 格
                val (score, grade) = splitScoreGrade(c.getOrElse(2) { "" })
                val points = c.getOrElse(3) { "" }

                items += PeScoreItem(
                    name = name,
                    value = value,
                    score = score,
                    grade = grade,
                    points = points,
                )
            }
        }

        // 视力表：表头含「左眼」「右眼」
        val vision = mutableListOf<PeVisionRow>()
        var keratoscope = ""
        d.select("table").forEach { table ->
            val head = table.select("tr").firstOrNull()?.let { cells(it) } ?: return@forEach
            if (!head.contains("左眼") && !head.contains("右眼")) return@forEach
            table.select("tr").drop(1).forEach { tr ->
                val c = cells(tr)
                if (c.size < 3) return@forEach
                val label = clean(c.getOrElse(1) { "" })
                val left = clean(c.getOrElse(2) { "" })
                val right = clean(c.getOrElse(3) { "" })
                when {
                    label.contains("裸眼视力") -> vision += PeVisionRow("裸眼视力", left, right)
                    label.contains("球镜") -> vision += PeVisionRow("球镜 S", left, right)
                    label.contains("柱镜") -> vision += PeVisionRow("柱镜 C", left, right)
                    label.contains("轴位") -> vision += PeVisionRow("轴位 A", left, right)
                    label.contains("角膜镜") -> keratoscope = left
                }
            }
        }

        // 往年成绩链接：2025年体测成绩 / 2024年体测成绩
        val historyYears = Regex("(\\d{4})年体测成绩")
            .findAll(d.body().html())
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        return PeScoreReport(
            year = year.ifBlank { titleYear },
            student = parseStudent(d),
            notice = parseNotice(d),
            items = items.filter { it.name.isNotBlank() },
            standardGrade = standardGrade,
            standardScore = standardScore,
            totalGrade = totalGrade,
            totalScore = totalScore,
            vision = vision,
            keratoscope = keratoscope,
            historyYears = historyYears,
        )
    }

    // ———————————————————————————— 评分标准 ————————————————————————————

    /**
     * 评分标准页：一张 .layui-card = 一个测试项目。
     *
     * 实测每行是 5 格：项目 | 成绩档位 | 得分 等级 | 分值 | 分值（第四、五格重复），
     * 取第 4 格作为分值即可。
     */
    fun parseStandard(html: String): PeStandardPage {
        val d = doc(html)
        val tips = clean(d.selectFirst("#lblStandardTips")?.text())
        val tables = mutableListOf<PeStandardTable>()

        d.select("div.layui-card").forEach { card ->
            val title = clean(card.selectFirst(".layui-card-header")?.text())
            val table = card.selectFirst("table") ?: return@forEach
            val rows = mutableListOf<PeStandardRow>()

            table.select("tr").forEach { tr ->
                val c = cellTexts(tr)
                if (c.size < 4 || c.first() == "测试项目" || c.first().isBlank()) return@forEach

                val (score, grade) = splitScoreGrade(c.getOrElse(2) { "" })
                rows += PeStandardRow(
                    project = c[0],
                    value = c.getOrElse(1) { "" },
                    score = score,
                    grade = grade,
                    points = c.getOrElse(3) { "" },
                )
            }

            if (rows.isNotEmpty()) {
                tables += PeStandardTable(
                    title = title.ifBlank { rows.firstOrNull()?.project.orEmpty() },
                    rows = rows,
                )
            }
        }

        return PeStandardPage(
            student = parseStudent(d),
            tips = tips,
            tables = tables,
            notice = parseNotice(d),
        )
    }

    // ———————————————————————————— 主页 ————————————————————————————

    fun parseHome(html: String): PeHome {
        val d = doc(html)
        val entries = d.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (!href.contains(".aspx", ignoreCase = true)) return@mapNotNull null
                val title = clean(a.attr("title").ifBlank { a.text() })
                if (title.isBlank()) return@mapNotNull null
                PeEntry(title, href)
            }
            .distinctBy { it.url }
        return PeHome(student = parseStudent(d), entries = entries)
    }

    // ———————————————————————————— 预约 ————————————————————————————

    private val BOOKING_PROJECTS = listOf(
        "身高", "体重", "肺活量", "50米跑", "坐位体前屈", "立定跳远",
        "跳绳", "引体向上", "仰卧起坐", "1000米跑", "800米跑", "50米×8往返跑",
    )

    fun parseBooking(html: String): PeBookingInfo {
        val d = doc(html)
        val items = mutableListOf<PeBookingItem>()
        var tip = ""

        d.select("tr").forEach { tr ->
            val c = cells(tr)
            if (c.size >= 2 && BOOKING_PROJECTS.contains(c[0])) {
                items += PeBookingItem(c[0], c[1])
            } else if (c.size == 1 && c[0].startsWith("补测时间")) {
                tip = c[0]
            }
        }

        val myBookings = d.select("table")
            .mapNotNull { table ->
                val head = table.selectFirst("tr")?.let { cells(it) } ?: return@mapNotNull null
                if (!head.any { it.contains("预约") }) return@mapNotNull null
                clean(table.text()).takeIf { it.isNotBlank() && it.length > 6 }
            }

        return PeBookingInfo(
            notice = parseNotice(d),
            tip = tip,
            items = items.distinctBy { it.name },
            myBookings = myBookings,
        )
    }

    // ———————————————————————————— 免测申请 ————————————————————————————

    fun parseExempt(html: String): PeExemptInfo {
        val d = doc(html)
        val noticeText = d.select("div.layui-card-body, div.layui-card")
            .map { clean(it.text()) }
            .firstOrNull { it.length > 40 }
            .orEmpty()

        val records = d.select("table").mapNotNull { table ->
            val head = table.selectFirst("tr")?.let { cells(it) } ?: return@mapNotNull null
            if (!head.any { it.contains("申请") }) return@mapNotNull null
            clean(table.text()).takeIf { it.isNotBlank() && it.length > 6 }
        }

        return PeExemptInfo(
            notice = parseNotice(d),
            noticeText = noticeText,
            records = records,
        )
    }

    // ———————————————————————————— 运动处方 ————————————————————————————

    fun parsePrescription(html: String): PePrescription {
        val d = doc(html)
        val scoreItems = mutableListOf<PeScoreItem>()
        var score = ""
        var grade = ""
        var gender = ""
        var gradeName = ""
        var age = ""
        var major = ""

        d.select("tr").forEach { tr ->
            val c = cells(tr)
            if (c.size < 2) return@forEach
            val k = c[0]
            if (k.startsWith("评分")) score = c[1]
            else if (k.startsWith("等级")) grade = c[1]
            else if (k.startsWith("年级")) gradeName = c[1]
            else if (k.startsWith("性别")) gender = c[1]
            else if (k.startsWith("年龄")) age = c[1]
            else if (k.startsWith("班级")) major = c[1]
        }

        // 处方里的成绩表与成绩页同构，复用同一套行规则
        d.select("table").forEach { table ->
            if (table.selectFirst("#lblHeight") == null) return@forEach
            table.select("tr").forEach { tr ->
                val c = cells(tr)
                if (c.size < 4 || c[0].isBlank() || c[0] == "测试项目") return@forEach

                val (s, g) = splitScoreGrade(c.getOrElse(2) { "" })
                scoreItems += PeScoreItem(
                    name = c[0],
                    value = valueOf(c.getOrElse(1) { "" }),
                    score = s,
                    grade = g,
                    points = c.getOrElse(3) { "" },
                )
            }
        }

        val diagnosis = d.select("div.layui-card")
            .mapNotNull { card ->
                val title = clean(card.selectFirst(".layui-card-header")?.text())
                if (title.isBlank()) return@mapNotNull null
                if (title.contains("测试项目")) return@mapNotNull null
                val body = clean(card.selectFirst(".layui-card-body")?.text())
                if (body.isBlank()) null else PeDiagnosis(title, body)
            }

        return PePrescription(
            student = parseStudent(d),
            score = score,
            grade = grade,
            gender = gender,
            gradeName = gradeName,
            age = age,
            major = major,
            items = scoreItems.filter { it.name.isNotBlank() },
            diagnosis = diagnosis,
        )
    }

    // ———————————————————————————— 字符串判定 ————————————————————————————

    private val GRADES = listOf("优秀", "良好", "及格", "不及格", "正常", "肥胖", "超重", "低体重")

    fun String.isChineseGrade(): Boolean = GRADES.contains(this.trim())

    /** "60" / "14.4" / "60分" 都算数值 */
    fun String.isNumberLike(): Boolean {
        val t = trim().removeSuffix("分").trim()
        return t.isNotEmpty() && t.toDoubleOrNull() != null
    }
}