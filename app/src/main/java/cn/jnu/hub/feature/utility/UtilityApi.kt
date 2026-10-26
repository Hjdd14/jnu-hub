package cn.jnu.hub.feature.utility

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 水电接口错误：区分「会话过期」与「其它失败」，方便 UI 给出不同引导 */
sealed class UtilityException(message: String) : Exception(message) {
    /** CAS 会话缺失/过期，被 302 到登录页 */
    class SessionExpired : UtilityException("请先登录暨大统一身份认证")

    /** 网络不可达 / 超时 */
    class Network(cause: Throwable) : UtilityException(friendlyNetworkMessage(cause))

    /** 服务端返回了内容但业务失败 */
    class Business(message: String) : UtilityException(message)

    /** 响应无法解析 */
    class Parse(detail: String?) : UtilityException(
        detail?.let { "数据解析失败：$it" } ?: "数据解析失败，接口可能已变更",
    )
}

/**
 * 把网络异常翻译成用户能看懂的提示。
 *
 * DNS 解析失败在校园网场景下几乎总是「不在校内网络」：
 * pynhcx / jw / ehall 等域名只在校园网（或学校 VPN）内可解析，
 * 用移动数据 / 校外 Wi-Fi 时会报 Unable to resolve host。
 */
private fun friendlyNetworkMessage(cause: Throwable): String {
    val raw = cause.message.orEmpty()
    return if (raw.contains("Unable to resolve host", true) ||
        raw.contains("No address associated", true) ||
        raw.contains("UnknownHost", true)
    ) {
        "无法连接水电系统：需要连接校园网（或学校 VPN）才能访问 jnu.edu.cn 域名"
    } else {
        "网络异常，请检查网络后重试"
    }
}

/**
 * 水电查询 API 封装。
 *
 * Base: https://pynhcx.jnu.edu.cn/ibsjnuweb/WebService/JNUService.asmx/
 * 全部为 POST + JSON，Cookie 由全局 HttpClient 的共享 CookieJar 自动携带（CAS 会话）。
 *
 * 使用顺序：必须先调用 [checkSessionAndLogin] 建立业务侧会话，再调用其它方法。
 */
object UtilityApi {

    private const val BASE = "${JnuHosts.UTILITY}/ibsjnuweb/WebService/JNUService.asmx"

    private const val TAG = "UtilityApi"

    private val mediaType = "application/json; charset=UTF-8".toMediaType()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ───────────────────────── 底层请求 ─────────────────────────

    /** 当前已登录的 customerId，用于生成 Token 头；未登录为 null */
    @Volatile
    private var currentCustomerId: String? = null

    /** 返回解析后的 `d` 节点 */
    private suspend fun call(method: String, body: String, query: String = ""): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "$BASE/$method$query"
            val request = try {
                // 复刻站点全局拦截器：每个请求都带 Token + DateTime
                val token = UtilityToken.build(currentCustomerId)
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(mediaType))
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Referer", "${JnuHosts.UTILITY}/ibsjnuweb/")
                    .apply {
                        if (token.token.isNotEmpty()) {
                            header("Token", token.token)
                            header("DateTime", token.dateTime)
                        }
                    }
                    .build()
            } catch (e: Exception) {
                throw UtilityException.Network(e)
            }

            val text = try {
                HttpClient.get().newCall(request).execute().use { resp ->
                    // OkHttp 已跟随重定向：最终落到 CAS 域 = 会话过期
                    val finalUrl = resp.request.url.toString()
                    if (finalUrl.contains("/cas/login") ||
                        finalUrl.contains("icas.jnu.edu.cn") ||
                        finalUrl.contains("auth4.jnu.edu.cn")
                    ) {
                        Log.d(TAG, "call $method -> redirected to CAS: $finalUrl")
                        throw UtilityException.SessionExpired()
                    }
                    if (!resp.isSuccessful) {
                        throw UtilityException.Business("服务返回 HTTP ${resp.code}")
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: UtilityException) {
                throw e
            } catch (e: Exception) {
                throw UtilityException.Network(e)
            }

            // 调试日志：输出完整响应原文（截断 800 字），用于排查服务端返回结构
            Log.d(TAG, "call " + method + " -> " + text.take(800))

            if (text.isBlank()) throw UtilityException.SessionExpired()

            // 没有 CAS 会话时，服务端可能直接返回登录页 HTML（HTTP 200）。
            // 先按 HTML 特征判定会话过期，避免误报成「解析失败」。
            if (looksLikeLoginPage(text)) {
                Log.d(TAG, "call $method -> looks like CAS login page, treat as expired")
                throw UtilityException.SessionExpired()
            }

            val root = try {
                json.parseToJsonElement(text).asObjectOrNull()
            } catch (e: Exception) {
                if (text.trimStart().startsWith("<")) {
                    // 返回了 HTML 但不是登录页，多半是服务异常
                    throw UtilityException.Business("水电服务暂时不可用，请稍后重试")
                }
                throw UtilityException.Parse("返回的不是 JSON")
            } ?: throw UtilityException.Parse("返回的不是 JSON 对象")

            root["d"]?.asObjectOrNull() ?: root
        }

    /** 判断响应是不是 CAS / 统一认证登录页 */
    private fun looksLikeLoginPage(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("<")) return false
        val head = t.take(2048).lowercase()
        return head.contains("icas.jnu.edu.cn") ||
            head.contains("cas/login") ||
            head.contains("namespaces/cas") ||
            head.contains("统一身份认证") ||
            (head.contains("<form") && head.contains("id=\"fm1\""))
    }

    // ───────────────────────── 9 个接口 ─────────────────────────

    /**
     * 1. CheckSessionAndLogin —— 必须最先调用，建立业务会话。
     *
     * 服务端要求 userName（水电户号，如 T00001）+ userID（customerId，如 00000000），
     * **传空值会返回 isSuccess:false / customerId:0**。
     *
     * 户号来源见 [UtilityIdentityResolver]：
     * 1. Cookie jnu-lp / jnu-lpid 解密（网页端登录过一次后长期存在，有效期到 2100 年）
     * 2. 用户手动填写并本地记忆（[UtilityCredentialStore]）
     */
    suspend fun checkSessionAndLogin(
        userName: String = "",
        userID: String = "",
    ): UtilityLoginResult {
        val d = call(
            "CheckSessionAndLogin",
            buildJsonObject {
                put("userName", userName)
                put("userID", userID)
            }.toString(),
        )

        // 两种可能的外形：
        //   {"code":0,"message":"use session login.","data":{...loginResult...}}
        //   {"Success":true,...}
        val loginResult = d["data"].asObjectOrNull()?.get("loginResult").asObjectOrNull()
            ?: d["loginResult"].asObjectOrNull()
            ?: d

        // 逐个取，只要有任何一处明确表示成功即视为成功；
        // 都没有明确标记时，看 d.code==0 或结果里带 customerId 也算成功。
        val explicitFlags = listOfNotNull(
            loginResult.boolOrNull("isSuccess"),
            d.boolOrNull("isSuccess"),
            d.boolOrNull("Success"),
        )
        val codeOk = d.doubleOrNull("code")?.let { it == 0.0 }
        val hasCustomer = loginResult.doubleOrNull("customerId") != null

        val success = when {
            explicitFlags.any { it } -> true
            explicitFlags.isNotEmpty() -> false          // 明确全部为 false
            codeOk != null -> codeOk
            hasCustomer -> true
            else -> true                                  // 无任何标记：交由后续接口判定
        }

        Log.d(
            TAG,
            "checkSessionAndLogin flags=$explicitFlags codeOk=$codeOk hasCustomer=$hasCustomer success=$success",
        )

        // 只有「明确失败」才报会话过期；无标记的情况交给后续接口兜底，
        // 避免服务端改了包裹格式就把用户直接挡在门外。
        if (!success) {
            throw UtilityException.SessionExpired()
        }

        // 记下客户号，后续每个请求的 Token 头都基于它生成
        val customerId = loginResult.doubleOrNull("customerId")?.toLong()
        if (customerId != null && customerId > 0) {
            currentCustomerId = customerId.toString()
        } else if (userID.isNotBlank() && userID != "0") {
            currentCustomerId = userID.trim()
        }

        return UtilityLoginResult(
            isSuccess = true,
            customerId = customerId,
            userName = loginResult.stringOrEmpty("userName").ifBlank {
                d.stringOrEmpty("userName")
            }.ifBlank { null },
            message = d.stringOrEmpty("message").ifBlank { null },
        )
    }

    /**
     * 1b. CheckTicketAndLogin —— 用 CAS ticket 换取水电会话。
     *
     * 站点里这是「从 CAS 跳转过来」的分支：
     *   POST WebService/JNUService.asmx/CheckTicketAndLogin  {"ticket":"ST-xxx"}
     * 成功后返回结构与 CheckSessionAndLogin 相同（data.loginResult + data.userName），
     * 之后前端会 saveUserInfoToCookies 写入 jnu-lp / jnu-lpid。
     */
    suspend fun checkTicketAndLogin(ticket: String): UtilityLoginResult {
        val d = call(
            "CheckTicketAndLogin",
            buildJsonObject { put("ticket", ticket) }.toString(),
        )

        val loginResult = d["data"].asObjectOrNull()?.get("loginResult").asObjectOrNull()
            ?: d["loginResult"].asObjectOrNull()
            ?: d

        val ok = loginResult.boolOrNull("isSuccess")
            ?: d.boolOrNull("isSuccess")
            ?: d.boolOrNull("Success")
            ?: (d.doubleOrNull("code")?.let { it == 0.0 } ?: false)

        val customerId = loginResult.doubleOrNull("customerId")?.toLong()
        val userName = d.stringOrEmpty("userName").ifBlank { loginResult.stringOrEmpty("userName") }

        Log.d(TAG, "checkTicketAndLogin ok=" + ok + " user=" + userName + " id=" + customerId)

        if (!ok || customerId == null || customerId <= 0 || userName.isBlank()) {
            throw UtilityException.SessionExpired()
        }

        currentCustomerId = customerId.toString()

        return UtilityLoginResult(
            isSuccess = true,
            customerId = customerId,
            userName = userName,
            message = d.stringOrEmpty("message").ifBlank { null },
        )
    }

    /** 2. GetUserInfo —— 账号名称 / 余额 / 补贴余量 / 单价 */
    suspend fun getUserInfo(): UtilityAccount {
        val d = call("GetUserInfo", "{}")
        val row = d["ResultList"].asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            ?: return UtilityAccount()

        val roomInfo = KeyValueRow.parse(row["roomInfo"].asArrayOrNull()?.mapNotNull { it.asObjectOrNull() })
        val allowanceInfo = KeyValueRow.parse(row["allowanceInfo"].asArrayOrNull()?.mapNotNull { it.asObjectOrNull() })
        val priceInfo = KeyValueRow.parse(row["energyUnitPrice"].asArrayOrNull()?.mapNotNull { it.asObjectOrNull() })

        val balanceRow = KeyValueRow.find(roomInfo, "账户余额", "余额", "balance")

        return UtilityAccount(
            accountName = KeyValueRow.find(roomInfo, "账号名称", "账户名", "账号")?.keyValue,
            balance = KeyValueRow.number(balanceRow?.keyValue),
            subsidyPeople = KeyValueRow.find(allowanceInfo, "补贴人数", "人数")?.keyValue,
            allowance = allowanceInfo,
            unitPrice = priceInfo,
        )
    }

    /** 3. GetAccountBalance —— 只取余额（最轻量） */
    suspend fun getAccountBalance(): Double? {
        // 注意：接口文档为无请求体，但服务端要求 JSON Content-Type，这里发空对象
        val d = call("GetAccountBalance", "{}")
        return d.doubleOrNull("balance")
    }

    /** 4. GetBillCost —— 指定日期区间的账单 */
    suspend fun getBillCost(startDate: String, endDate: String, energyType: Int = EnergyTypes.ALL): List<BillItem> {
        val d = call(
            "GetBillCost",
            buildJsonObject {
                put("energyType", energyType)
                put("startDate", startDate)
                put("endDate", endDate)
            }.toString(),
        )
        return parseBillItems(d)
    }

    /** 5. GetBillCostByCutomerId —— 按区间汇总（月度柱状图数据源）。注意方法名拼写 Cutomer。 */
    suspend fun getBillCostByCustomerId(
        startDate: String,
        endDate: String,
        energyType: Int = EnergyTypes.ALL,
    ): List<EnergySeries> {
        val d = call(
            "GetBillCostByCutomerId",
            buildJsonObject {
                put("startDate", startDate)
                put("endDate", endDate)
                put("energyType", energyType)
            }.toString(),
        )
        val list = d["ResultList"].asArrayOrNull().orEmpty()
        return list.mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.doubleOrNull("energyType")?.toInt() ?: return@mapNotNull null
            val values = obj["values"].asArrayOrNull().orEmpty()
            val points = values.mapNotNull { v ->
                val vo = v.asObjectOrNull() ?: return@mapNotNull null
                val t = vo.doubleOrNull("startDate")?.toLong() ?: return@mapNotNull null
                val value = vo.doubleOrNull("dataValue") ?: 0.0
                UsagePoint(t, value)
            }.sortedBy { it.timeMillis }
            EnergySeries(type, EnergyTypes.label(type), points)
        }.filter { it.points.isNotEmpty() }
    }

    /** 6. GetCustomerMetricalData —— 每日用量折线图数据源 */
    suspend fun getCustomerMetricalData(
        startDate: String,
        endDate: String,
        energyType: Int = EnergyTypes.ALL,
        interval: Int = 1,
    ): List<EnergySeries> {
        val d = call(
            "GetCustomerMetricalData",
            buildJsonObject {
                put("energyType", energyType)
                put("startDate", startDate)
                put("endDate", endDate)
                put("interval", interval)
            }.toString(),
        )
        val list = d["ResultList"].asArrayOrNull().orEmpty()
        return list.mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.doubleOrNull("energyType")?.toInt() ?: return@mapNotNull null
            val name = obj.stringOrEmpty("energyName").ifBlank { EnergyTypes.label(type) }
            val points = obj["datas"].asArrayOrNull().orEmpty().mapNotNull { p ->
                val po = p.asObjectOrNull() ?: return@mapNotNull null
                val t = po.doubleOrNull("recordTime")?.toLong() ?: return@mapNotNull null
                UsagePoint(t, po.doubleOrNull("dataValue") ?: 0.0)
            }.sortedBy { it.timeMillis }
            EnergySeries(type, name, points)
        }.filter { it.points.isNotEmpty() }
    }

    /** 7. GetSubsidy —— 补贴额度（字段拼写 avalibleValue） */
    suspend fun getSubsidy(startDate: String, endDate: String): List<SubsidyItem> {
        val d = call(
            "GetSubsidy",
            buildJsonObject {
                put("startDate", startDate)
                put("endDate", endDate)
            }.toString(),
        )
        return d["ResultList"].asArrayOrNull().orEmpty().mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.doubleOrNull("itemType")?.toInt() ?: return@mapNotNull null
            SubsidyItem(
                itemType = type,
                itemName = obj.stringOrEmpty("itemName"),
                itemUnit = obj.stringOrEmpty("itemUnit").ifBlank { EnergyTypes.defaultUnit(type) },
                totalValue = obj.doubleOrNull("totalValue") ?: 0.0,
                // 注意拼写：avalibleValue
                availableValue = obj.doubleOrNull("avalibleValue")
                    ?: obj.doubleOrNull("availableValue")
                    ?: 0.0,
            )
        }
    }

    /** 8. GetPaymentRecord —— 分页充值记录；返回 (记录, 总数) */
    suspend fun getPaymentRecord(startIdx: Int = 0, recordCount: Int = 10): Pair<List<PaymentRecord>, Int> {
        val dc = System.currentTimeMillis()
        val d = call(
            "GetPaymentRecord",
            buildJsonObject {
                put("startIdx", startIdx)
                put("recordCount", recordCount)
            }.toString(),
            query = "?_dc=$dc",
        )
        val total = d.doubleOrNull("TotalCounts")?.toInt() ?: 0
        val records = d["ResultList"].asArrayOrNull().orEmpty().mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            PaymentRecord(
                logTime = obj.doubleOrNull("logTime")?.toLong() ?: 0L,
                paymentType = obj.stringOrEmpty("paymentType"),
                itemType = obj.stringOrEmpty("itemType"),
                value = obj.doubleOrNull("dataValue") ?: 0.0,
                unit = obj.stringOrEmpty("unit").ifBlank { "元" },
            )
        }
        return records to total
    }

    /** 9. GetEnergyTypeInfo —— 能源类型清单 */
    suspend fun getEnergyTypeInfo(nodeType: Int = 0, nodeID: Int = 0): List<EnergySeries> {
        val d = call(
            "GetEnergyTypeInfo",
            buildJsonObject {
                put("nodeType", nodeType)
                put("nodeID", nodeID)
            }.toString(),
        )
        return d["ResultList"].asArrayOrNull().orEmpty().mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.doubleOrNull("EnergyType")?.toInt() ?: return@mapNotNull null
            EnergySeries(
                energyType = type,
                energyName = obj.stringOrEmpty("EnergyName").ifBlank { EnergyTypes.label(type) },
                points = emptyList(),
            )
        }
    }

    // ───────────────────────── 解析工具 ─────────────────────────

    private fun parseBillItems(d: JsonObject): List<BillItem> =
        d["ResultList"].asArrayOrNull().orEmpty().mapNotNull { el ->
            val obj = el.asObjectOrNull() ?: return@mapNotNull null
            val type = obj.doubleOrNull("energyType")?.toInt() ?: return@mapNotNull null
            val detail = obj["energyCostDetails"].asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            val value = detail?.get("billItemValues").asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            BillItem(
                energyType = type,
                energyName = detail?.stringOrEmpty("billItemName")
                    ?: EnergyTypes.label(type),
                allowance = obj.doubleOrNull("allowance"),
                unitPrice = obj.doubleOrNull("unitPrice"),
                preValue = value?.doubleOrNull("preValue"),
                curValue = value?.doubleOrNull("curValue"),
                energyValue = value?.doubleOrNull("energyValue"),
                chargeValue = value?.doubleOrNull("chargeValue"),
                meterName = value?.stringOrEmpty("metricalNodeName")?.ifBlank { null },
            )
        }
}

// ───────────────────────── JsonElement 小工具 ─────────────────────────

internal fun JsonElement?.asObjectOrNull(): JsonObject? =
    this?.let { runCatching { it as JsonObject }.getOrNull() }

internal fun JsonElement?.asArrayOrNull(): JsonArray? =
    this?.let { runCatching { it as JsonArray }.getOrNull() }

internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? =
    this?.let { runCatching { it as JsonPrimitive }.getOrNull() }
