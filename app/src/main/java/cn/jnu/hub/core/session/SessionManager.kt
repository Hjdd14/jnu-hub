package cn.jnu.hub.core.session

import android.content.Context
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 会话管理器。
 *
 * 核心职责：
 * 1. 探测各业务系统的会话是否仍然有效（被 302 到 CAS 登录页 = 已过期）
 * 2. 记录每个系统的「上次成功时间 / 预估剩余时长」
 * 3. 心跳保活（前台每 15 分钟）刷新目标服务的闲置超时
 */
object SessionManager {

    private val _sessions = MutableStateFlow<Map<Service, ServiceSession>>(
        Service.entries.associateWith { ServiceSession(it) },
    )
    val sessions: StateFlow<Map<Service, ServiceSession>> = _sessions.asStateFlow()

    private val JSON = "application/json; charset=UTF-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    /** 更新单个服务状态 */
    private fun update(service: Service, block: (ServiceSession) -> ServiceSession) {
        val current = _sessions.value.toMutableMap()
        current[service] = block(current[service] ?: ServiceSession(service))
        _sessions.value = current
    }

    /**
     * 判断一次响应是否「被踢回登录页」。
     * 由于 OkHttp 会自动跟随重定向，最终 URL 若落在 ICAS/auth 域即视为过期。
     */
    private fun isExpired(finalUrl: String): Boolean =
        finalUrl.contains("/cas/login") ||
            finalUrl.contains("icas.jnu.edu.cn") ||
            finalUrl.contains("auth4.jnu.edu.cn") ||
            finalUrl.contains("auth7.jnu.edu.cn")

    private fun httpGet(url: String): Pair<Int, String> = runCatching {
        val req = Request.Builder().url(url).get().build()
        HttpClient.get().newCall(req).execute().use { resp ->
            resp.code to (resp.request.url.toString())
        }
    }.getOrElse { -1 to "error:${it.message}" }

    private fun httpPostJson(url: String, body: String): Pair<Int, String> = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        HttpClient.get().newCall(req).execute().use { resp ->
            resp.code to (resp.request.url.toString())
        }
    }.getOrElse { -1 to "error:${it.message}" }

    /**
     * 探测 CAS 会话是否有效（用网上服务大厅，全体师生都有权限）。
     *
     * 不再用 Blackboard：它只对 JBJI 学生开放，其他学院会被拒，造成误判。
     */
    suspend fun probeCas(): Boolean = withContext(Dispatchers.IO) {
        // 必须用 jw 门户（登录落地页）：ehall 是独立服务大厅，会自己再走一次 CAS 换票，
        // 在「CAS 其实有效」时仍 302 到 icas，导致这里误判成未登录。
        // 全 App 三处探测（LoginActivity / UtilityCasProbe / 这里）现在统一用 CAS_PROBE。
        val (code, finalUrl) = httpGet(JnuHosts.CAS_PROBE)
        val ok = code in 200..399 && !isExpired(finalUrl)
        android.util.Log.d(
            "SessionManager",
            "cas probe: url=" + finalUrl + " code=" + code + " ok=" + ok,
        )
        ok
    }

    /** 探测 Blackboard（仅用于 BB 模块自身，不作为通用会话判据） */
    suspend fun probeBlackboard(): Boolean = withContext(Dispatchers.IO) {
        val (code, finalUrl) = httpGet(
            "${JnuHosts.BLACKBOARD}/webapps/portal/execute/tabs/tabAction?tab_tab_group_id=_1_1",
        )
        handle(Service.BLACKBOARD, code, finalUrl)
    }

    /** 探测水电（调用余额接口，最轻量） */
    suspend fun probeUtility(): Boolean = withContext(Dispatchers.IO) {
        val (code, finalUrl) = httpPostJson(
            "${JnuHosts.UTILITY}/ibsjnuweb/WebService/JNUService.asmx/GetAccountBalance",
            "",
        )
        handle(Service.UTILITY, code, finalUrl)
    }

    /** 探测校园网 */
    suspend fun probeCampusNet(): Boolean = withContext(Dispatchers.IO) {
        val (code, finalUrl) = httpGet("${JnuHosts.CAMPUS_NET}/app/customer/accounts/info")
        handle(Service.CAMPUS_NET, code, finalUrl)
    }

    private fun handle(service: Service, code: Int, finalUrl: String): Boolean {
        val expired = isExpired(finalUrl)
        val ok = code in 200..399 && !expired
        update(service) { s ->
            s.copy(
                state = if (ok) SessionState.LOGGED_IN else SessionState.EXPIRED,
                lastOkAt = if (ok) System.currentTimeMillis() else s.lastOkAt,
                message = if (ok) null else "会话已过期或未登录",
            )
        }
        return ok
    }

    /** 并发探测所有系统（登录成功后立即调用，用来验证「一次登录」是否成立） */
    suspend fun probeAll(): Map<Service, SessionState> = coroutineScope {
        // 探测前先把 WebView 的 Cookie 同步过来，避免假阴性
        syncFromWebViewIfPossible()
        val bb = async { probeBlackboard() }
        val ut = async { probeUtility() }
        val cn = async { probeCampusNet() }
        listOf(bb, ut, cn).forEach { it.await() }
        _sessions.value.mapValues { it.value.state }
    }

    /** 从 WebView 同步 Cookie 到 OkHttp（WebView 是登录态的唯一权威来源） */
    private fun syncFromWebViewIfPossible() {
        runCatching {
            val manager = android.webkit.CookieManager.getInstance()
            manager.flush()
            val map = mutableMapOf<String, String>()
            JnuHosts.cookieHosts.forEach { url ->
                val host = url.substringAfter("://").substringBefore('/')
                manager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { map[host] = it }
            }
            if (map.isNotEmpty()) HttpClient.cookieJar().syncFromWebView(map)
        }
    }

    /** 清理所有会话 */
    fun clearAll(context: Context) {
        HttpClient.cookieJar().clear()
        _sessions.value = Service.entries.associateWith { ServiceSession(it) }
    }
}