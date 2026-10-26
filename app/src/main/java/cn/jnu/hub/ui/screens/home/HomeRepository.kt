package cn.jnu.hub.ui.screens.home

import android.util.Log
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.core.session.Service
import cn.jnu.hub.core.session.SessionManager
import cn.jnu.hub.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 首页聚合数据 */
data class HomeData(
    val utilityBalance: Double? = null,
    val utilityAccount: String? = null,
    val utilityMonthly: Double? = null,
    val campusNetInfo: String? = null,
    /** 校园网到期日期 yyyy-MM-dd */
    val campusNetExpire: String? = null,
    val campusNetBalance: String? = null,
    val bbCourses: Int = 0,
    val bbAnnouncements: List<HomeAnnouncement> = emptyList(),
    val sessions: Map<Service, SessionState> = emptyMap(),
    val peScore: Int? = null,
    val peYear: String? = null,
    val loading: Boolean = true,
    val anyLoggedIn: Boolean = false,
)

data class HomeAnnouncement(val course: String, val title: String, val date: String)

/**
 * 首页数据聚合器。
 * 只做「读取」，任何一块失败都不影响其它块（降级为 null）。
 */
object HomeRepository {

    private const val TAG = "HomeRepo"
    private val mediaJson = "application/json; charset=UTF-8".toMediaType()

    suspend fun load(context: android.content.Context? = null): HomeData = coroutineScope {
        val utilityDeferred = async { loadUtility(context) }
        val campusDeferred = async { loadCampusNet() }
        val bbDeferred = async { loadBlackboard() }
        val sessionDeferred = async { SessionManager.probeAll() }

        val u = utilityDeferred.await()
        val c = campusDeferred.await()
        val b = bbDeferred.await()
        val s = sessionDeferred.await()

        HomeData(
            utilityBalance = u?.balance,
            utilityAccount = u?.account,
            utilityMonthly = u?.monthly,
            campusNetInfo = c?.info,
            campusNetBalance = c?.balance,
            campusNetExpire = c?.expire,
            bbCourses = b?.courses ?: 0,
            bbAnnouncements = b?.announcements ?: emptyList(),
            sessions = s,
            loading = false,
            anyLoggedIn = s.values.any { it == SessionState.LOGGED_IN },
        )
    }

    // ───────────── 水电 ─────────────
    private data class UtilityBrief(val balance: Double?, val account: String?, val monthly: Double?)

    /**
     * 水电余额。
     *
     * 复用 [UtilityRepository.login]，它已经封装了完整的户号解析链：
     * CAS ticket → 手动填写 → Cookie(jnu-lp/jnu-lpid) 解密。
     * 之前首页传空 userName/userID 调 CheckSessionAndLogin，服务端必然 isSuccess=false，
     * 于是永远显示「--」。
     */
    private suspend fun loadUtility(context: android.content.Context?): UtilityBrief? = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = context
            if (ctx == null) {
                Log.d(TAG, "utility: no app context, skip")
                return@runCatching null
            }
            val login = cn.jnu.hub.feature.utility.UtilityRepository.login(ctx)
            Log.d(TAG, "utility login: success=" + login.isSuccess + " user=" + login.userName)
            if (!login.isSuccess) return@runCatching null

            val balance = runCatching {
                cn.jnu.hub.feature.utility.UtilityApi.getAccountBalance()
            }.getOrNull()
            Log.d(TAG, "utility balance=" + balance)

            UtilityBrief(
                balance = balance,
                account = login.userName?.takeIf { it.isNotBlank() },
                monthly = null,
            )
        }.getOrElse {
            Log.d(TAG, "utility failed: " + it.message)
            null
        }
    }

// ───────────── 校园网 ─────────────
    private data class CampusBrief(
        val info: String?,
        val balance: String?,
        /** 到期日期 yyyy-MM-dd */
        val expire: String? = null,
    )

    /**
     * 校园网：走 CampusNetApi，拿余额 + 到期日期。
     * 首页只展示到期日期，会话未建立时返回 null，卡片显示「点击进入」。
     */
    private suspend fun loadCampusNet(): CampusBrief? = withContext(Dispatchers.IO) {
        runCatching {
            val account = cn.jnu.hub.feature.campusnet.CampusNetApi.accountInfo()
            Log.d(TAG, "campusnet: balance=" + account.balance + " expire=" + account.expireDate)
            CampusBrief(
                info = account.packageName?.takeIf { it.isNotBlank() }
                    ?: account.accountStatus?.takeIf { it.isNotBlank() }
                    ?: account.userFlagLabel,
                balance = account.balance?.let { "¥" + it },
                expire = account.expireDate,
            )
        }.getOrElse {
            Log.d(TAG, "campusnet failed: " + it.message)
            null
        }
    }

    // ───────────── Blackboard ─────────────
    private data class BbBrief(val courses: Int, val announcements: List<HomeAnnouncement>)

    private suspend fun loadBlackboard(): BbBrief? = withContext(Dispatchers.IO) {
        runCatching {
            // 课程列表
            val req = Request.Builder()
                .url("${JnuHosts.BLACKBOARD}/webapps/portal/execute/tabs/tabAction")
                .post(
                    ("action=refreshAjaxModule&modId=_4_1&tabId=_1_1&tab_tab_group_id=_1_1")
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                )
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val body = HttpClient.get().newCall(req).execute().use { r ->
                if (r.request.url.toString().contains("icas")) return@use ""
                r.body?.string().orEmpty()
            }
            if (body.isBlank()) return@runCatching null
            val courses = Regex("launcher\\?type=(?:Course|Organization)&id=(_\\d+_1)")
                .findAll(body).count()
            BbBrief(courses = courses, announcements = emptyList())
        }.getOrElse {
            Log.d(TAG, "blackboard failed: ${it.message}")
            null
        }
    }

    // ───────────── 工具 ─────────────
    private fun post(url: String, body: String): String {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(mediaJson))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "${JnuHosts.UTILITY}/ibsjnuweb/")
            .build()
        return HttpClient.get().newCall(req).execute().use { r ->
            if (r.request.url.toString().contains("icas")) "" else r.body?.string().orEmpty()
        }
    }

    private fun parse(text: String): JsonObject? = runCatching {
        Json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    private fun rootD(text: String): String {
        val obj = parse(text) ?: return text
        val d = obj["d"] ?: return text
        return d.toString()
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
}
