package cn.jnu.hub.feature.faculty

import android.util.Log
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.IOException

/** 师资模块的错误：区分网络 / 服务端 / 解析，UI 据此给出不同文案 */
sealed class FacultyException(message: String) : Exception(message) {
    class Network(cause: Throwable) : FacultyException(
        cause.message?.takeIf { it.isNotBlank() }?.let { "网络异常：$it" }
            ?: "网络异常，请检查网络后重试",
    )

    class Business(msg: String) : FacultyException(msg)

    class Parse(msg: String) : FacultyException(msg)
}

/**
 * 师资查询 API（免登录）。
 *
 * 接口是 webplus 的通用查询，所有参数都通过表单提交：
 *
 * | 参数 | 说明 |
 * |---|---|
 * | `conditions` | JSON 数组，筛选条件，judge 支持 `=` / `like` |
 * | `returnInfos` | JSON 数组，声明要返回哪些字段 |
 * | `rows` | 每页条数（实测服务端会按它切页） |
 * | `pageIndex` | 页码，1 开始 |
 * | `orders` | 排序，如 `[{"field":"letter","type":"asc"}]` |
 * | `count` | `1` 时只返回 total，data 为空 |
 * | `departmentSearch` | `1` 时返回学院列表 |
 *
 * 注意几个实测结论（踩过的坑，改动前先看一眼）：
 * 1. **教师姓名是 `title` 字段**，不是 name / teacherName，乱传会被忽略；
 * 2. 人名检索用 `{"field":"title","value":"陈","judge":"like"}`，
 *    英文名 like 走不通（站点数据是中文姓名排序）；
 * 3. 学院筛选用 `ownDepartment` = collegeId，不是 collegeName；
 * 4. 职称筛选用 `career` + `like`（例如「教授」会同时命中「教授」「副教授」，
 *    想要精确值就传完整字符串 + `=`）。
 *
 * 详情页：搜索结果里的 `cnUrl` 就是教师主页，直接抓 HTML 用 Jsoup 解析。
 */
object FacultyApi {

    private const val TAG = "FacultyApi"

    /** 通用查询入口 */
    private const val QUERY_URL = JnuHosts.FACULTY + "/_wp3services/generalQuery?queryObj=teacherHome"

    /** 站点根，用来补全相对地址 */
    private const val SITE_ROOT = JnuHosts.FACULTY

    /** 默认每页条数 */
    const val PAGE_SIZE = 20

    /** 学院列表一次拉全 */
    private const val COLLEGE_PAGE_SIZE = 200

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ───────────────────────── 常驻筛选条件 ─────────────────────────

    /** 只看中文版、已发布的主页 */
    private val BASE_CONDITIONS = listOf(
        Condition("language", "1", "="),
        Condition("published", "1", "="),
    )

    /**
     * 请求哪些字段：姓名(title)/职称/学院/邮箱/电话/主页地址/访问量/头像。
     *
     * 注意「学院」的字段名是 **department**，不是 collegeName
     * （collegeName 在教师查询里恒为 null，只在学院列表 departmentSearch 里才有效）。
     */
    private val RETURN_INFOS = listOf(
        "title" to "title",
        "career" to "career",
        "department" to "department",
        "email" to "email",
        "phone" to "phone",
        "cnUrl" to "cnUrl",
        "visitCount" to "visitCount",
        "headerPic" to "headerPic",
    )

    /** 一条查询条件 */
    private data class Condition(val field: String, val value: String, val judge: String)

    // ───────────────────────── 底层请求 ─────────────────────────

    private fun postForm(fields: Map<String, String>): String {
        val form = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()

        val req = Request.Builder()
            .url(QUERY_URL)
            .header("Referer", "$SITE_ROOT/1212/list.htm")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .post(form)
            .build()

        return try {
            HttpClient.get().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw FacultyException.Business(
                        when (resp.code) {
                            403, 503 -> "师资服务暂时不可用（HTTP ${resp.code}），请稍后重试"
                            else -> "师资服务返回 HTTP ${resp.code}"
                        },
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: FacultyException) {
            throw e
        } catch (e: IOException) {
            throw FacultyException.Network(e)
        } catch (e: Exception) {
            throw FacultyException.Network(e)
        }
    }

    private fun parseEnvelope(text: String): JsonObject {
        if (text.isBlank()) throw FacultyException.Parse("师资服务返回为空")
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<")) {
            throw FacultyException.Parse("师资服务返回了网页而不是数据，请稍后重试")
        }
        return runCatching { json.parseToJsonElement(text) as? JsonObject }
            .getOrNull() ?: throw FacultyException.Parse("师资服务返回格式异常")
    }

    // ───────────────────────── 学院列表 ─────────────────────────

    /**
     * 拉取学院列表（含每个学院的教师人数）。
     *
     * 请求 `departmentSearch=1`，返回 `{"total":N,"data":[{"collegeId":..,"fullDepartName":"..","count":..}]}`。
     * 实测一次给 200 条可以拿全（站点共 44 条，部分 count 为 0 的会被前端隐藏）。
     */
    suspend fun colleges(): List<FacultyCollege> = withContext(Dispatchers.IO) {
        val returnInfos = buildJsonArray {
            add(buildJsonObject { put("field", "collegeId"); put("name", "collegeId") })
            add(buildJsonObject { put("field", "collegeName"); put("name", "collegeName") })
            add(buildJsonObject { put("field", "fullDepartName"); put("name", "fullDepartName") })
            add(buildJsonObject { put("field", "count"); put("name", "count") })
        }

        val text = postForm(
            mapOf(
                "siteId" to "2",
                "pageIndex" to "1",
                "rows" to COLLEGE_PAGE_SIZE.toString(),
                "returnInfos" to returnInfos.toString(),
                "articleType" to "0",
                "level" to "0",
                "departmentSearch" to "1",
            ),
        )
        Log.d(TAG, "colleges → ${text.take(300)}")
        val obj = parseEnvelope(text)
        val arr = obj["data"] as? JsonArray ?: return@withContext emptyList()

        arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["collegeId"].asIntOrNull() ?: return@mapNotNull null
            val name = o["fullDepartName"].asStringOrNull()
                ?: o["collegeName"].asStringOrNull()
                ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            FacultyCollege(
                collegeId = id,
                name = name.trim(),
                count = o["count"].asIntOrNull() ?: 0,
            )
        }
            .distinctBy { it.collegeId }
            .sortedBy { it.collegeId }
    }

    // ───────────────────────── 教师搜索 ─────────────────────────

    /**
     * 搜索教师。
     *
     * @param keyword 姓名关键词，按 `title like` 模糊匹配；空串表示不筛姓名
     * @param collegeId 学院 id（来自 [colleges]），null 表示全部
     * @param career 职称筛选值，需传站点里的完整职称字符串（见 [FacultyCareers]），精确匹配
     * @param pageIndex 页码，1 开始
     */
    suspend fun search(
        keyword: String = "",
        collegeId: Int? = null,
        career: String? = null,
        pageIndex: Int = 1,
        pageSize: Int = PAGE_SIZE,
    ): TeacherPage = withContext(Dispatchers.IO) {
        val conditions = buildList {
            addAll(BASE_CONDITIONS)
            val kw = keyword.trim()
            if (kw.isNotEmpty()) add(Condition("title", kw, "like"))
            if (collegeId != null) add(Condition("ownDepartment", collegeId.toString(), "="))
            if (!career.isNullOrBlank()) {
                // 职称：精确值用 =，前缀筛选（如「教授」）用 like 会带上「副教授」，
                // 所以这里统一用精确匹配；调用方传的必须是站点里的完整职称字符串。
                add(Condition("career", career.trim(), "="))
            }
        }

        val fields = mutableMapOf(
            "siteId" to "2",
            "pageIndex" to pageIndex.coerceAtLeast(1).toString(),
            "rows" to pageSize.toString(),
            "conditions" to conditionsToJson(conditions),
            "returnInfos" to returnInfosJson(),
            "articleType" to "1",
            "level" to "0",
            "pageEvent" to "dataSearchByPageIndex",
        )

        val text = postForm(fields)
        Log.d(TAG, "search kw=$keyword college=$collegeId career=$career p=$pageIndex → ${text.take(300)}")
        val obj = parseEnvelope(text)
        val total = obj["total"].asIntOrNull() ?: 0
        val arr = obj["data"] as? JsonArray ?: JsonArray(emptyList())

        val items = arr.mapNotNull { parseTeacher(it as? JsonObject) }
        TeacherPage(
            total = total,
            items = items,
            pageIndex = pageIndex.coerceAtLeast(1),
            pageSize = pageSize,
        )
    }

    private fun parseTeacher(o: JsonObject?): TeacherSummary? {
        if (o == null) return null
        val name = o["title"].asStringOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null

        val rawUrl = o["cnUrl"].asStringOrNull()?.trim().orEmpty()
        val headerPic = o["headerPic"].asStringOrNull()?.trim().orEmpty()

        return TeacherSummary(
            name = name,
            career = o["career"].asStringOrNull()?.trim().orEmpty(),
            college = o["department"].asStringOrNull()?.trim().orEmpty(),
            email = o["email"].asStringOrNull()?.trim().orEmpty(),
            phone = o["phone"].asStringOrNull()?.trim().orEmpty(),
            homeUrl = absoluteUrl(rawUrl),
            visitCount = o["visitCount"].asIntOrNull() ?: 0,
            avatarPath = if (headerPic.isEmpty()) "" else absoluteUrl(headerPic),
        )
    }

    private fun conditionsToJson(conditions: List<Condition>): String = buildJsonArray {
        conditions.forEach { c ->
            add(
                buildJsonObject {
                    put("field", c.field)
                    put("value", c.value)
                    put("judge", c.judge)
                },
            )
        }
    }.toString()

    private fun returnInfosJson(): String = buildJsonArray {
        RETURN_INFOS.forEach { (field, name) ->
            add(
                buildJsonObject {
                    put("field", field)
                    put("name", name)
                },
            )
        }
    }.toString()

    // ───────────────────────── 教师详情 ─────────────────────────

    /**
     * 抓取教师主页并解析。
     *
     * @param homeUrl 搜索结果里的 [TeacherSummary.homeUrl]
     * @param fallbackName 页面里取不到姓名时的兜底（用搜索结果的姓名）
     */
    suspend fun detail(homeUrl: String, fallbackName: String = ""): TeacherDetail =
        withContext(Dispatchers.IO) {
            if (homeUrl.isBlank()) {
                throw FacultyException.Business("这位老师没有公开主页，无法查看详情")
            }
            val html = fetchHtml(homeUrl)
            parseDetail(html, homeUrl, fallbackName)
        }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Referer", "$SITE_ROOT/1212/list.htm")
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .get()
            .build()
        return try {
            HttpClient.get().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw FacultyException.Business("教师主页返回 HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) throw FacultyException.Parse("教师主页内容为空")
                body
            }
        } catch (e: FacultyException) {
            throw e
        } catch (e: IOException) {
            throw FacultyException.Network(e)
        } catch (e: Exception) {
            throw FacultyException.Network(e)
        }
    }

    /**
     * 解析教师主页。
     *
     * 两套模板统一处理：
     *  主模板  `div.post[id] > h3.tit > span.title` + `div.con`
     *  老模板  `li.news ` 里 `span.title`（标签）+ `span.txt`（值）
     *
     * 字段优先取结构化资料，取不到再退回搜索结果里的兜底值。
     */
    internal fun parseDetail(html: String, sourceUrl: String, fallbackName: String): TeacherDetail {
        val doc: Document = Jsoup.parse(html, sourceUrl)
        doc.select("script, style, noscript").remove()

        // 1) 结构化资料：老模板的 li.news 键值对
        val kvRows = mutableListOf<InfoRow>()
        doc.select("li.news").forEach { li ->
            val label = li.selectFirst("span.title")?.text()?.trim()
                ?.trimEnd('：', ':')?.trim()
                ?: return@forEach
            val value = li.selectFirst("span.txt")?.text()?.trim().orEmpty()
            if (label.isNotEmpty() && value.isNotEmpty()) kvRows += InfoRow(label, value)
        }

        // 2) 主模板的 p.news_text 键值对（左侧「个人信息」块）
        val newsTextRows = mutableListOf<InfoRow>()
        doc.select("p.news_text").forEach { p ->
            // 跳过后端用 display:none 隐藏的字段
            val style = p.attr("style").replace(" ", "").lowercase()
            if (style.contains("display:none")) return@forEach
            // 文本形如「姓名： 宾德善」，值在 span 里
            val value = p.selectFirst("span")?.text()?.trim().orEmpty()
            if (value.isEmpty()) return@forEach
            val label = p.text()
                .substringBefore("：")
                .substringBefore(":")
                .trim()
            if (label.isNotEmpty()) newsTextRows += InfoRow(label, value)
        }

        val info = (kvRows + newsTextRows).distinctBy { it.label }

        // 3) 长文本分区：div.post 里同时有 h3.tit 和 div.con 的算一个分区
        val sections = mutableListOf<DetailSection>()
        doc.select("div.post").forEach { post ->
            val title = post.selectFirst("h3.tit > span.title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@forEach
            val con = post.selectFirst("div.con") ?: return@forEach
            val text = normalizeText(con)
            if (text.isBlank()) return@forEach
            sections += DetailSection(title, text)
        }

        // 4) 头像
        val avatar = doc.select("img[alt=头像]").firstNotNullOfOrNull { img ->
            img.attr("src").takeIf { it.isNotBlank() }
        } ?: doc.select("div.con1 img, div.img_pic img").firstNotNullOfOrNull { img ->
            img.attr("src").takeIf { it.isNotBlank() }
        }

        fun pick(vararg labels: String): String {
            labels.forEach { l ->
                info.firstOrNull { it.label == l }?.value?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }

        val name = pick("姓名").ifBlank { fallbackName }
        val email = pick("电子邮箱", "邮箱", "Email", "E-mail")
        val phone = pick("联系电话", "电话", "办公电话")
        // 老模板里职称写在「职务」；新模板有独立的「职称」
        val career = pick("职称", "职务")
        val department = pick("部门", "学院", "所在单位", "直属机构")

        return TeacherDetail(
            name = name,
            career = career,
            department = department,
            email = email,
            phone = phone,
            avatarUrl = if (avatar.isNullOrBlank()) "" else absoluteUrl(avatar),
            info = info,
            sections = sections,
            sourceUrl = sourceUrl,
        )
    }

    /**
     * 把富文本 div 压成带换行的纯文本。
     *
     * 注意不能用 `Element.text()`：它按浏览器的空白折叠规则把换行压成单个空格，
     * 整篇简介会挤成一行。这里先在块级元素后插入换行标记，再用 `wholeText()`
     * 原样取文本，最后统一折叠空白。
     */
    private fun normalizeText(con: Element): String {
        val clone = con.clone()
        clone.select("script, style, noscript").remove()
        clone.select("img").remove()
        val newline = TextNode("\n")
        clone.select("br").forEach { it.replaceWith(newline.clone()) }
        clone.select("p, li, h1, h2, h3, h4, h5, h6, tr, div, table").forEach { el ->
            el.appendChild(newline.clone())
        }
        return clone.wholeText()
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 相对路径补全成绝对地址；已经是 http(s) 的原样返回 */
    fun absoluteUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return ""
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> SITE_ROOT + u
            else -> "$SITE_ROOT/$u"
        }
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement?.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
}
