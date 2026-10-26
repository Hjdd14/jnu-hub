package cn.jnu.hub.feature.campusnet

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
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.IOException

/** 校园网接口错误：区分「未建立 mynet 会话」与「其它失败」，方便 UI 给出不同引导 */
sealed class CampusNetException(message: String) : Exception(message) {
    /**
     * mynet 侧没有有效会话。
     *
     * 注意：这**不等于** CAS 过期。mynet 走的是完整 CAS 票据流程
     * （/app/login → ICAS → 回跳 ?ticket=ST-xxx → 换 JSESSIONID），
     * 只做 CAS 登录并不会自动建立 mynet 会话，用户需要完成一次票据交换。
     */
    class SessionExpired : CampusNetException("校园网会话未建立，需要同步一次账号")

    class Network(cause: Throwable) : CampusNetException(
        cause.message?.let { "网络异常：$it" } ?: "网络异常，请检查网络后重试",
    )

    class Business(msg: String) : CampusNetException(msg)

    class Parse(msg: String) : CampusNetException(msg)
}

/**
 * 校园网（mynet.jnu.edu.cn）API。
 *
 * 与 Blackboard / 水电最大的不同：**mynet 不认 auth4 网关直通**，它走标准 CAS 票据交换。
 *
 *   ① GET  /app/login                  未登录 → 302 ICAS → 302 /app/login?ticket=ST-xxx
 *   ② GET  /app/login?ticket=ST-xxx    服务端校验票据 → Set-Cookie: JSESSIONID → 200
 *   ③ GET  /app/customer/accounts/info 此时才返回 JSON
 *
 * 旧的实现跳过 ②，直接请求 ③，于是永远被 302 回 CAS，页面「一直显示已过期」。
 * 这里由 [exchangeTicket] 补上 ②，[ensureSession] 负责「先交换再用」的编排。
 */
object CampusNetApi {

    private const val TAG = "CampusNet"

    /**
     * accounts/info 的字段诊断是否已打印过（本进程只打一次）。
     * 完整响应有 49KB，每次全打会刷屏；但首次调用时打一次对排查字段结构很有用。
     */
    @Volatile
    private var accountDumpedOnce = false

    /** 站点根 */
    val BASE: String = JnuHosts.CAMPUS_NET

    /** 应用根：所有页面 URL / 菜单跳转都挂在这里 */
    val APP_BASE: String = "${JnuHosts.CAMPUS_NET}/app"

    /** CAS 回跳落点，也是内嵌 WebView 要打开的地址 */
    val LOGIN_URL: String = "$APP_BASE/login"

    private const val INFO_PATH = "/customer/accounts/info"
    private const val MENU_PATH = "/sys/menu/nav"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 会话是否在上次探测中确认可用（仅用于 UI 立即渲染，不作为安全判据） */
    @Volatile
    private var sessionKnown: Boolean = false

    fun markSessionUnknown() {
        sessionKnown = false
    }

    // ───────────────────────── 底层请求 ─────────────────────────

    private fun appGet(path: String): String {
        val url = if (path.startsWith("http")) path else APP_BASE + path
        val req = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "$APP_BASE/")
            .get()
            .build()
        Log.d(TAG, "GET $url")
        return try {
            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                Log.d(TAG, "  → ${resp.code} $finalUrl")
                if (isCasRedirect(finalUrl)) {
                    markSessionUnknown()
                    throw CampusNetException.SessionExpired()
                }
                if (!resp.isSuccessful) {
                    throw CampusNetException.Business("校园网服务返回 HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: CampusNetException) {
            throw e
        } catch (e: IOException) {
            throw CampusNetException.Network(e)
        } catch (e: Exception) {
            throw CampusNetException.Network(e)
        }
    }

    private fun isCasRedirect(finalUrl: String): Boolean =
        finalUrl.contains("/cas/login") ||
            finalUrl.contains("icas.jnu.edu.cn") ||
            finalUrl.contains("auth4.jnu.edu.cn") ||
            finalUrl.contains("auth7.jnu.edu.cn")

    /** 从 CookieJar 里找 mynet 的业务会话 Cookie */
    private fun mynetSessionCookieNames(): List<String> = runCatching {
        HttpClient.cookieJar().snapshot()
            .filter { it.domain.contains("mynet.jnu.edu.cn") }
            .map { it.name }
    }.getOrDefault(emptyList())

    /** 只做接收，不做任何跳转 */
    private fun probeLoginPage(): String {
        val req = Request.Builder()
            .url(LOGIN_URL)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .get()
            .build()
        return try {
            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                Log.d(TAG, "probe /app/login → ${resp.code} $finalUrl")
                if (isCasRedirect(finalUrl)) {
                    markSessionUnknown()
                    throw CampusNetException.SessionExpired()
                }
                if (!resp.isSuccessful) {
                    throw CampusNetException.Business("校园网登录状态检查失败（HTTP ${resp.code}）")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: CampusNetException) {
            throw e
        } catch (e: IOException) {
            throw CampusNetException.Network(e)
        } catch (e: Exception) {
            throw CampusNetException.Network(e)
        }
    }

    // ───────────────────────── 会话建立 ─────────────────────────

    /**
     * 用 CAS 票据交换 mynet 会话。
     *
     * 调用 GET /app/login?ticket=ST-xxx，服务端校验通过后会 Set-Cookie: JSESSIONID。
     * OkHttp 自动跟随重定向，票据在 Redirect 时若被清掉会导致落回登录页，所以额外用一次
     * 交互式请求确认结果。返回 true 表示 mynet 会话已可用。
     *
     * 票据是一次性的：无论成功失败，调用方都不该重试同一个 ticket。
     */
    suspend fun exchangeTicket(ticket: String): Boolean = withContext(Dispatchers.IO) {
        if (ticket.isBlank()) return@withContext false
        Log.d(TAG, "exchange ticket=$ticket")
        try {
            val url = "$LOGIN_URL?ticket=${android.net.Uri.encode(ticket)}"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .get()
                .build()
            val (code, finalUrl) = HttpClient.get().newCall(req).execute().use { resp ->
                resp.code to resp.request.url.toString()
            }
            Log.d(TAG, "exchange → $code $finalUrl (cookies=${mynetSessionCookieNames()})")
            if (isCasRedirect(finalUrl)) {
                markSessionUnknown()
                return@withContext false
            }
            // 票据换完后 /app/login 应该 200（或跳到工作台）
            if (code !in 200..399) return@withContext false
            sessionKnown = true
            true
        } catch (e: CampusNetException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "exchange failed", e)
            false
        }
    }

    /**
     * 确保 mynet 会话可用，必要时做一次 CAS 票据交换。
     *
     * 顺序（每一步都不花额外往返的地方不重复请求）：
     * 1. 有本地 CAS 票据 → 直接交换（最快路径，mynet 会话 100% 需要它）
     * 2. 先探 /app/login：200 说明会话已在，直接返回
     * 3. 被 302 到 CAS → 说明缺 mynet 会话，抛出 SessionExpired 让 UI 引导同步
     *
     * 返回 state：会话是否可用。
     */
    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        // 1) 刚由内嵌 WebView 捕获的票据，优先使用
        val ticket = CampusNetTicketBridge.consume()
        if (!ticket.isNullOrBlank()) {
            Log.d(TAG, "ensureSession: using freshly captured ticket")
            if (exchangeTicket(ticket)) return@withContext true
            Log.d(TAG, "ensureSession: ticket exchange failed, fall through to probe")
        }

        // 2) 探测是否已经有会话
        val html = try {
            probeLoginPage()
        } catch (e: CampusNetException.SessionExpired) {
            markSessionUnknown()
            throw e
        } catch (e: CampusNetException) {
            throw e
        }

        if (looksLikeMyNetHome(html)) {
            sessionKnown = true
            return@withContext true
        }

        // 3) 落到 CAS 登录表单 = 连 CAS 都没登录；否则是「CAS 有效但 mynet 未换票」
        markSessionUnknown()
        Log.d(TAG, "ensureSession: not logged in (cookies=${mynetSessionCookieNames()})")
        throw CampusNetException.SessionExpired()
    }

    /** 判断响应是不是 mynet 登录后的框架页（而不是 CAS 登录表单） */
    private fun looksLikeMyNetHome(html: String): Boolean {
        if (html.isBlank()) return false
        val head = html.take(4096).lowercase()
        if (head.contains("icas.jnu.edu.cn") || head.contains("cas/login")) return false
        if (head.contains("id=\"fm1\"") || head.contains("统一身份认证")) return false
        // mynet 是 AdminLTE 框架，登录后页面一定带这些标识
        return head.contains("adminlte") ||
            head.contains("mynet") ||
            head.contains("main-container") ||
            head.contains("<html")
    }

    // ───────────────────────── 业务接口 ─────────────────────────

    private fun asObject(text: String): JsonObject {
        if (text.isBlank()) throw CampusNetException.Parse("服务返回为空")
        val t = text.trimStart()
        if (t.startsWith("<")) {
            // HTML 而不是 JSON：多半又被踢回登录页
            throw CampusNetException.SessionExpired()
        }
        return runCatching { json.parseToJsonElement(text).asObjectOrNull() }
            .getOrNull() ?: throw CampusNetException.Parse("返回格式异常")
    }

    /**
     * 账户信息。
     * GET /app/customer/accounts/info?_<ts>
     *
     * 真实响应外层是 {msg, code, data:{...}}，账户字段在 data 里；
     * 但不同版本可能把字段直接摊在最外层，这里两种都兼容。
     */
    suspend fun accountInfo(): CampusAccount = withContext(Dispatchers.IO) {
        val text = appGet("$INFO_PATH?_${System.currentTimeMillis()}")
        Log.d(TAG, "accounts/info → ${text.take(600)}")
        val obj = asObject(text)
        val code = obj["code"].contentOrNull()
        if (code != null && code != "0") {
            val msg = obj["msg"].contentOrNull().orEmpty()
            if (msg.contains("登录")) throw CampusNetException.SessionExpired()
            throw CampusNetException.Business(msg.ifBlank { "账号信息查询失败" })
        }
        val body = obj["data"].asObjectOrNull() ?: obj
        // 接口字段排查用：只在本次进程内首次调用时打印一次，避免刷屏。
        // 49KB 的完整响应里真正有用的只有 serviceList / user 两个节点，
        // 所以只打这两块，而不是整包。
        if (!accountDumpedOnce) {
            accountDumpedOnce = true
            runCatching {
                Log.d(TAG, "account raw(head)=" + body.toString().take(800))
                Log.d(TAG, "account serviceList=" + body["serviceList"].toString().take(1200))
                Log.d(TAG, "account user=" + body["user"].toString().take(600))
            }.onFailure { Log.w(TAG, "dump failed", it) }
        }
        CampusAccount.parse(body, obj)
    }

    /** 菜单树：GET /app/sys/menu/nav?_<ts> */
    suspend fun menuNav(): List<CampusMenu> = withContext(Dispatchers.IO) {
        val text = appGet("$MENU_PATH?_${System.currentTimeMillis()}")
        Log.d(TAG, "menu/nav → ${text.take(600)}")
        val obj = asObject(text)
        val list = obj["menuList"].asArrayOrNull() ?: return@withContext emptyList()
        list.mapNotNull { el -> parseMenu(el.asObjectOrNull()) }
    }

    private fun parseMenu(o: JsonObject?): CampusMenu? {
        if (o == null) return null
        val name = o["name"].contentOrNull().orEmpty()
        if (name.isBlank()) return null
        val children = o["list"].asArrayOrNull()
            ?.mapNotNull { parseMenu(it.asObjectOrNull()) }
            .orEmpty()
        return CampusMenu(
            name = name,
            url = o["url"].contentOrNull()?.takeIf { it.isNotBlank() },
            icon = o["icon"].contentOrNull()?.takeIf { it.isNotBlank() },
            menuId = o["menuId"].contentOrNull()?.toIntOrNull(),
            children = children,
        )
    }

    /**
     * 拼出菜单对应的网页地址。
     * 菜单里的 url 是相对路径（modules/customer/servicerecord.html），
     * 站点根是 https://mynet.jnu.edu.cn/app/。
     */
    fun pageUrl(path: String?): String? {
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return null
        return when {
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("/") -> BASE + p
            else -> "$APP_BASE/$p"
        }
    }

    /**
     * 一次性拉账号 + 菜单。
     *
     * 先 [ensureSession]，再有 mynet 会话的前提下取数据。任何一步判定会话失效都
     * 抛 [CampusNetException.SessionExpired]，由 UI 展示「同步账号」引导。
     */
    suspend fun loadOverview(withMenu: Boolean = true): CampusNetOverview = withContext(Dispatchers.IO) {
        ensureSession()
        val account = accountInfo()
        val menu = if (withMenu) {
            runCatching { menuNav() }.getOrElse { e ->
                if (e is CampusNetException.SessionExpired) throw e
                Log.w(TAG, "menu nav failed", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        CampusNetOverview(account = account, menu = menu)
    }

    // ───────────────────────── JSON 小工具 ─────────────────────────

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        (this as? JsonObject)

    private fun JsonElement?.asArrayOrNull(): JsonArray? =
        (this as? JsonArray)

    private fun JsonElement?.contentOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}

// ───────────────────────── 数据模型 ─────────────────────────

data class CampusNetOverview(
    val account: CampusAccount,
    val menu: List<CampusMenu> = emptyList(),
)

/**
 * 校园网账户信息。
 *
 * 站点 /customer/accounts/info 的字段随版本变动较大，这里只固化页面明确用到的几项，
 * 其余以 [fields] 保留「键值对」形式展示，避免接口加字段就打不开页面。
 */
data class CampusAccount(
    /** 用户标识（学工号） */
    val userName: String? = null,
    /** 真实姓名 */
    val realName: String? = null,
    /** s = 学生，t = 教师 */
    val userFlag: String? = null,
    /** 手机号 */
    val phone: String? = null,
    /** 邮箱 */
    val email: String? = null,
    /** 余额（元），可能为 null */
    val balance: Double? = null,
    /** 套餐名 */
    val packageName: String? = null,
    /** 到期日期（yyyy-MM-dd）；不同版本字段名不一致，解析时按候选键兜底 */
    val expireDate: String? = null,
    /** 账号状态：正常 / 停机 / 欠费 … */
    val accountStatus: String? = null,
    /** 其它未归类字段（键 → 值），用于兜底展示 */
    val fields: List<Pair<String, String>> = emptyList(),
    /** 原始 JSON，便于排障 */
    val raw: String = "",
) {
    val userFlagLabel: String
        get() = when (userFlag?.lowercase()) {
            "s" -> "学生"
            "t" -> "教师"
            else -> userFlag?.takeIf { it.isNotBlank() } ?: "已识别"
        }

    val displayName: String
        get() = realName?.takeIf { it.isNotBlank() }
            ?: userName?.takeIf { it.isNotBlank() }
            ?: "校园网用户"

    companion object {
        /** 已知字段的候选键名（不同版本命名不一致，按优先级取第一个非空值） */
        private val NAME_KEYS = listOf("realName", "name", "userNameCn", "custName", "customerName")
        private val USER_KEYS = listOf("userName", "loginName", "account", "custNo", "customerNo", "userId")
        private val FLAG_KEYS = listOf("userFlag", "userType", "custType")
        private val PHONE_KEYS = listOf("phone", "mobile", "telephone", "mobilePhone", "custPhone")
        private val EMAIL_KEYS = listOf("email", "mail", "custEmail")
        private val BALANCE_KEYS = listOf("balance", "accountBalance", "money", "surplus")
        private val PACKAGE_KEYS = listOf("packageName", "tariffName", "productName", "package")
        private val STATUS_KEYS = listOf("status", "accountStatus", "userStatus", "state")

        /** 网络接入服务节点里的到期键（实测为 toDate） */
        private val NET_SERVICE_EXPIRE_KEYS = listOf(
            "toDate", "endDate", "expireDate", "expireTime", "endTime", "validTo",
        )

        /** 到期日期的候选键（各版本命名差异很大，按优先级取第一个非空） */
        private val EXPIRE_KEYS = listOf(
            "expireDate", "expireTime", "expiredTime", "expiryDate", "expirationDate",
            "endDate", "endTime", "validTo", "validTime", "deadline",
            "accountExpireTime", "packageExpireTime", "productExpireTime", "serviceExpireTime",
        )

        /** 已经单独建模、不必再出现在「其它信息」里的键 */
        private val HANDLED = (
            NAME_KEYS + USER_KEYS + FLAG_KEYS + PHONE_KEYS + EMAIL_KEYS +
                BALANCE_KEYS + PACKAGE_KEYS + STATUS_KEYS + EXPIRE_KEYS +
                listOf("msg", "code", "data", "countryCodeList", "token", "password")
            ).toSet()

        /**
         * 把各种日期格式统一成 yyyy-MM-dd。
         * 服务端可能给时间戳（毫秒/秒）、"2026-07-09 23:59:59"、"2026/07/09" 等。
         */
        private fun normalizeDate(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            // 纯数字：可能是毫秒或秒级时间戳
            s.toLongOrNull()?.let { n ->
                val ms = if (n > 100_000_000_000L) n else n * 1000
                return runCatching {
                    java.time.Instant.ofEpochMilli(ms)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate().toString()
                }.getOrNull()
            }
            // 提取 年-月-日
            val m = Regex("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})").find(s) ?: return s.take(10)
            val y = m.groupValues[1]
            val mo = m.groupValues[2].padStart(2, '0')
            val d = m.groupValues[3].padStart(2, '0')
            return "$y-$mo-$d"
        }

        fun parse(body: JsonObject, envelope: JsonObject? = null): CampusAccount {
            fun pick(keys: List<String>): String? = keys.firstNotNullOfOrNull { k ->
                body[k].let { el -> (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
            }

            /**
             * 从嵌套节点里取字段。
             *
             * 实测 accounts/info 的真实结构（2026-10-26 抓包）：
             *   {
             *     "serviceList": [   // 每个开通的服务一条
             *       { "serviceType":"internet", "networkTypeName":"网络接入服务(双终端)",
             *         "fromDate":"2026-09-06 19:33:53", "toDate":"2027-01-06 19:33:53", ... },
             *       { "serviceType":"extmail", "toDate":"2074-11-01 00:13:29", ... }
             *     ],
             *     "user": { "jnuid":"...", "username":"张同学", "userFlag":"学生",
             *               "toDate":"2027-01-06 19:33:53", "status":1, ... }
             *   }
             *
             * 到期日期既不在顶层，也不能随便取第一个 service ——
             * 学子邮是 2074 年，取了就显示成「2074 到期」。
             * 必须先过滤 serviceType == "internet" 才准。
             */
            fun nestedObjects(vararg path: String): List<JsonObject> {
                var current: JsonElement = body
                for (seg in path) {
                    current = (current as? JsonObject)?.get(seg) ?: return emptyList()
                }
                return when (current) {
                    is JsonArray -> current.mapNotNull { it as? JsonObject }
                    is JsonObject -> listOf(current)
                    else -> emptyList()
                }
            }

            /** 在若干嵌套对象里按候选键找第一个非空字符串 */
            fun pickNested(objs: List<JsonObject>, keys: List<String>): String? =
                objs.firstNotNullOfOrNull { obj ->
                    keys.firstNotNullOfOrNull { k ->
                        (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                }

            // 用户节点：姓名字段都在这里（顶层没有）
            val userNode = nestedObjects("user")

            // 网络接入服务优先（serviceType == internet）；没有就退回第一条
            val services = nestedObjects("serviceList")
            val internetService = services.filter {
                (it["serviceType"] as? JsonPrimitive)?.contentOrNull
                    ?.equals("internet", ignoreCase = true) == true
            }.ifEmpty { services }

            val balance = BALANCE_KEYS.firstNotNullOfOrNull { k ->
                (body[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            }

            val others = body.entries
                .filter { (k, _) -> k !in HANDLED }
                .mapNotNull { (k, v) ->
                    val text = when (v) {
                        is JsonPrimitive -> v.contentOrNull
                        is JsonObject, is JsonArray -> null // 嵌套结构不摊平，避免卡面过长
                        else -> null
                    }
                    text?.takeIf { it.isNotBlank() }?.let { k to it }
                }
                .sortedBy { it.first }

            // 到期日期的取值顺序：
            // 1. 顶层显式字段（兼容其它版本接口）
            // 2. 网络接入服务节点的 toDate/endTime…
            // 3. user 节点的 toDate（账号有效期，通常与网络服务一致）
            val expireRaw = pick(EXPIRE_KEYS)
                ?: pickNested(internetService, NET_SERVICE_EXPIRE_KEYS)
                ?: pickNested(userNode, listOf("toDate", "endDate", "expireDate"))

            return CampusAccount(
                userName = pick(USER_KEYS) ?: pickNested(userNode, listOf("jnuid", "loginAccount", "accountKey")),
                realName = pick(NAME_KEYS) ?: pickNested(userNode, listOf("username", "realName", "name")),
                userFlag = pick(FLAG_KEYS) ?: pickNested(userNode, listOf("userFlag", "userType")),
                phone = pick(PHONE_KEYS) ?: pickNested(userNode, listOf("tel", "phone", "mobile")),
                email = pick(EMAIL_KEYS) ?: pickNested(userNode, listOf("email", "mail")),
                balance = balance,
                // 套餐名：网络接入服务的 networkTypeName，如「网络接入服务(双终端)」
                packageName = pick(PACKAGE_KEYS)
                    ?: pickNested(internetService, listOf("networkTypeName", "serviceTypeName", "packageName")),
                accountStatus = pick(STATUS_KEYS),
                expireDate = normalizeDate(expireRaw),
                fields = others,
                raw = (envelope ?: body).toString(),
            )
        }
    }
}

/**
 * 菜单节点。
 *
 * @param url 相对路径，如 modules/customer/servicerecord.html；为 null 表示分组节点
 */
data class CampusMenu(
    val name: String,
    val url: String? = null,
    val icon: String? = null,
    val menuId: Int? = null,
    val children: List<CampusMenu> = emptyList(),
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
}
