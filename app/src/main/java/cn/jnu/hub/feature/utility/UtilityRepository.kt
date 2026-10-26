package cn.jnu.hub.feature.utility

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 水电查询仓库：串联 9 个接口，产出页面需要的一份快照。
 *
 * 数据流（见 docs/05_水电模块拆分设计.md 2.2）：
 *   CheckSessionAndLogin → [GetAccountBalance | GetUserInfo] → GetBillCost
 *   → GetSubsidy → GetCustomerMetricalData → GetBillCostByCutomerId → GetPaymentRecord
 *
 * 设计要点：
 * - 登录必须先成功，否则后续接口全部 302 到 CAS。
 * - 余额/账号信息与账单/补贴等可以并行，任一失败不拖垮整页（只有核心余额失败才整页报错）。
 */
object UtilityRepository {

    private const val TAG = "UtilityRepo"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /**
     * 建立水电业务会话。
     *
     * 户号来源优先级：
     * 0. CAS ticket（WebPageActivity 刚捕获到 ST- 票据时，最可靠）
     * 1. 手动填写的户号（UtilityCredentialStore，用户显式指定）
     * 2. Cookie jnu-lp / jnu-lpid 解密（网页端登录过就有了）
     * 3. **GetUserInfo 反查**：服务端在 CAS 会话有效时会回
     *    `message: "use session login."`，说明它认得当前会话，
     *    此时 GetUserInfo 往往能直接返回账号名称，拿它当户号再登录。
     * 4. 空值兜底：服务端返回 isSuccess:false，由上层引导同步/手填。
     *
     * 注意第 3 步是「用 session 换户号」，不是「重新登录」——
     * 它解决的是「CAS 明明有效、只是我们不知道户号」这个死结。
     */
    suspend fun login(context: Context?): UtilityLoginResult {
        val store = context?.let { UtilityCredentialStore(it) }

        // 0) 优先用刚捕获的 CAS 票据（一次性，消费后清空）
        val ticket = UtilityTicketBridge.consume()
        if (!ticket.isNullOrBlank()) {
            Log.d(TAG, "login via CAS ticket")
            return try {
                val result = UtilityApi.checkTicketAndLogin(ticket)
                // 换取成功后立即落盘，下次就不用再走网页了
                store?.saveAccount(
                    result.userName.orEmpty(),
                    result.customerId?.toString().orEmpty(),
                )
                result
            } catch (e: Exception) {
                Log.w(TAG, "checkTicketAndLogin failed, fallback", e)
                loginWithStoredOrCookie(context)
            }
        }

        return loginWithStoredOrCookie(context)
    }

    /** 手动户号 → Cookie 解密 → 空值兜底 */
    private suspend fun loginWithStoredOrCookie(context: Context?): UtilityLoginResult {
        val store = context?.let { UtilityCredentialStore(it) }

        // 1) 手动户号优先
        val manualName = store?.accountName().orEmpty()
        val manualId = store?.customerId().orEmpty()
        if (manualName.isNotBlank()) {
            Log.d(TAG, "login manual name=" + manualName + " id=" + manualId)
            return UtilityApi.checkSessionAndLogin(manualName, manualId)
        }

        // 2) Cookie 自动识别
        val identity = UtilityIdentityResolver.resolve()
        if (identity != null) {
            Log.d(TAG, "login cookie name=" + identity.userName + " id=" + identity.customerId)
            return UtilityApi.checkSessionAndLogin(identity.userName, identity.customerId)
        }

        // 3) GetUserInfo 反查用户：服务端说 "use session login." 时它往往能回账号名
        val discovered = discoverAccountViaUserInfo()
        if (discovered != null) {
            Log.d(TAG, "login via GetUserInfo discovery: " + discovered)
            try {
                val ok = UtilityApi.checkSessionAndLogin(
                    discovered.userName,
                    discovered.customerId.orEmpty(),
                )
                // 反查成功就落盘，下次不必再探测
                store?.saveAccount(
                    ok.userName ?: discovered.userName,
                    ok.customerId?.toString() ?: discovered.customerId.orEmpty(),
                )
                return ok
            } catch (e: Exception) {
                Log.w(TAG, "discovered login failed, fallback to empty", e)
            }
        }

        // 4) 兜底：空值尝试，让上层拿到明确的失败信号
        //
        // 实测（2026-10-26）：服务端返回
        //   {"code":0,"message":"use session login.",
        //    "loginResult":{"isSuccess":false,"customerId":0}}
        // 而紧接着 GetUserInfo 回 {"Success":false,"ErrorMessage":"5"}，
        // 说明 **ErrorMessage:"5" = 水电系统侧无会话**。
        // 注意 "use session login." 是固定模板文案，不代表会话有效 ——
        // 它是「请用 session 登录」的指令，不是「已用 session 登录」的确认。
        Log.d(TAG, "login EMPTY (no cookie, no manual account) -> expect ErrorMessage=5")
        return UtilityApi.checkSessionAndLogin()
    }

    /**
     * 用 GetUserInfo 反查当前会话对应的水电户号。
     *
     * 背景：CheckSessionAndLogin 不传 userName 时，服务端回
     *   {"code":0,"message":"use session login.","loginResult":{"isSuccess":false}}
     * **"use session login." 说明服务端承认 CAS 会话有效**，只是不知道是哪个户。
     * 而 GetUserInfo 走同一个 session，多数情况下会直接返回「账号名称」。
     *
     * @return 解析到的户号；拿不到返回 null
     */
    private suspend fun discoverAccountViaUserInfo(): DiscoveredAccount? {
        val info = runCatching { UtilityApi.getUserInfo() }.getOrElse { e ->
            Log.d(TAG, "GetUserInfo discovery failed: " + e.javaClass.simpleName + " " + e.message)
            return null
        }
        Log.d(
            TAG,
            "GetUserInfo discovery: accountName=" + info.accountName +
                " balance=" + info.balance +
                " allowanceRows=" + info.allowance.size,
        )

        val name = info.accountName?.trim().orEmpty()
        if (name.isBlank()) {
            Log.d(TAG, "GetUserInfo returned no accountName -> cannot discover")
            return null
        }

        // 户号形如 T00001；customerId 拿不到时留空，让服务端按 session 推断
        val customerId = info.allowance
            .firstOrNull { it.keyName.contains("客户号") || it.keyName.contains("customerId", true) }
            ?.keyValue
            ?.filter { it.isDigit() }
            .orEmpty()

        return DiscoveredAccount(userName = name, customerId = customerId)
    }

    private data class DiscoveredAccount(val userName: String, val customerId: String)

    /** 加载整页数据 */
    suspend fun loadSnapshot(context: Context? = null): UtilitySnapshot = coroutineScope {
        // 1) 建立业务会话（必须先做）
        login(context)

        val now = Date()
        val monthStart = startOfMonth(now, offsetMonths = 0)
        val nextMonthStart = startOfMonth(now, offsetMonths = 1)
        // 上一自然月，用于「本期账单」（本月通常还没结算）
        val prevMonthStart = startOfMonth(now, offsetMonths = -1)
        val yearStart = startOfMonth(now, offsetMonths = -11)

        val warnings = mutableListOf<String>()

        // 2) 并发拉取各块数据
        val balanceDeferred = async { runCatching { UtilityApi.getAccountBalance() } }
        val userInfoDeferred = async { runCatching { UtilityApi.getUserInfo() } }
        val billDeferred = async {
            runCatching { UtilityApi.getBillCost(format(prevMonthStart), format(nextMonthStart)) }
                .recoverCatching { UtilityApi.getBillCost(format(monthStart), format(nextMonthStart)) }
        }
        val subsidyDeferred = async {
            runCatching { UtilityApi.getSubsidy(format(prevMonthStart), format(nextMonthStart)) }
                .recoverCatching { UtilityApi.getSubsidy(format(monthStart), format(nextMonthStart)) }
        }
        val dailyDeferred = async {
            runCatching { UtilityApi.getCustomerMetricalData(format(monthStart), format(nextMonthStart)) }
        }
        val monthlyDeferred = async {
            runCatching { UtilityApi.getBillCostByCustomerId(format(yearStart), format(nextMonthStart)) }
        }

        val userInfoResult = userInfoDeferred.await()
        val balanceResult = balanceDeferred.await()

        // 核心：会话过期必须整页失败，其它按块降级
        balanceResult.exceptionOrNull()?.let { throw it }
        userInfoResult.exceptionOrNull()?.let { throw it }

        val userInfo = userInfoResult.getOrThrow()
        val balance = balanceResult.getOrNull() ?: userInfo.balance
        val account = userInfo.copy(balance = balance ?: userInfo.balance)

        // 会话过期不能降级成「暂时取不到」，否则用户看不到登录引导，
        // 后面的接口也只会继续失败。任何一块报会话过期就整页抛出。
        fun <T> Result<List<T>>.orEmptyWith(message: String): List<T> = getOrElse { e ->
            if (e is UtilityException.SessionExpired) throw e
            warnings += message
            emptyList()
        }

        val bills = billDeferred.await().orEmptyWith("本期账单暂时取不到")
        val subsidies = subsidyDeferred.await().orEmptyWith("补贴额度暂时取不到")
        val daily = dailyDeferred.await().orEmptyWith("每日用量暂时取不到")
        val monthly = monthlyDeferred.await().orEmptyWith("月度消费暂时取不到")

        UtilitySnapshot(
            account = account,
            subsidies = subsidies,
            bills = bills.sortedBy { it.energyType },
            dailyUsage = daily.sortedBy { EnergyTypes.ordered.indexOf(it.energyType).takeIf { i -> i >= 0 } ?: 99 },
            monthly = monthly.sortedBy { EnergyTypes.ordered.indexOf(it.energyType).takeIf { i -> i >= 0 } ?: 99 },
            warnings = warnings,
        )
    }

    /** 分页加载充值记录 */
    suspend fun loadPaymentRecords(
        context: Context?,
        startIdx: Int,
        count: Int = 20,
    ): Pair<List<PaymentRecord>, Int> {
        login(context)
        return UtilityApi.getPaymentRecord(startIdx, count)
    }

    // ─────────────────────── 日期工具 ───────────────────────

    /** 今天所在的自然月（或偏移若干月）的 1 号 */
    private fun startOfMonth(base: Date, offsetMonths: Int): Date {
        val cal = Calendar.getInstance().apply {
            time = base
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, offsetMonths)
        }
        return cal.time
    }

    private fun format(date: Date): String = dateFmt.format(date)
}
