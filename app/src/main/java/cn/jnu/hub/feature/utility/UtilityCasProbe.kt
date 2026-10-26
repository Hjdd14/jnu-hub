package cn.jnu.hub.feature.utility

import android.util.Log
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * CAS 会话状态探测：区分「真的没登录」和「登录了但缺水电户号」。
 *
 * 为什么需要区分：
 * - 水电查询需要的是 jnu-lp Cookie（户号），CAS 登录**不会**写入它
 * - CAS 登录 + Blackboard 可用 != 水电能查
 * - 所以「去登录」按钮对缺户号的用户毫无意义（点了只会被弹到 Blackboard 再回来）
 *
 * 历史坑（务必注意）：
 * 早期版本先查本地有无 CASTGC cookie 作为前置条件，
 * 但 CASTGC 是 HttpOnly，我们的 OkHttp CookieJar 读不到它（WebView 侧也受 SDK 限制），
 * 导致前置判断永远为 false，所有用户都被判成未登录，形成点「去登录」的死循环。
 * 现在改为：不做任何本地 Cookie 预判，直接打一次 Blackboard 首页看真实结果。
 *
 * 判据与 feature/login/LoginActivity.probeBlackboard 保持一致（实测有效）：
 *   !redirectedToCas && code == 200 && body 含 "Welcome," && body 不含 "loginForm"
 */
object UtilityCasProbe {

    private const val TAG = "UtilityCasProbe"

    /**
     * 探测目标必须与 [cn.jnu.hub.feature.login.LoginActivity] 保持一致（jw 门户）。
     *
     * 曾经用 ehall：它是**独立的服务大厅**，需要自己走一次 CAS 换票，
     * 所以在「CAS 其实有效」的情况下它仍然 302 到 icas，
     * 导致这里误判成 LOGGED_OUT，水电页只显示「去登录」而拿不到户号同步入口。
     *
     * jw 门户是登录落地页，只要有 CAS 会话就必然能直接打开，是可靠的判据。
     */
    private val BB_HOME: String
        get() = JnuHosts.CAS_PROBE

    /** 探测结果三态：明确已登录 / 明确未登录 / 无法判断（网络异常等） */
    enum class State { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    /** CAS 是否可用（true = 已登录统一认证） */
    suspend fun isCasLoggedIn(): Boolean = check() == State.LOGGED_IN

    /**
     * 三态探测。
     *
     * 为什么需要 UNKNOWN：如果网络抖动导致探测失败就武断判「未登录」，
     * 用户会点「去登录」→ LoginActivity 发现 CAS 其实有效 → 立刻关闭 → 回来还是同一页，
     * 又形成循环。所以只有**服务端明确把我们踢回 CAS** 才认定为 LOGGED_OUT。
     */
    suspend fun check(): State = withContext(Dispatchers.IO) {
        val s = probeBlackboard()
        Log.d(TAG, "blackboard probe state=" + s)
        s
    }

    /** 用 OkHttp 请求 Blackboard 首页，返回三态结果 */
    private fun probeBlackboard(): State = runCatching {
        val req = Request.Builder()
            .url(BB_HOME)
            .get()
            .build()
        HttpClient.get().newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            val redirectedToCas = finalUrl.contains("icas.jnu.edu.cn") ||
                finalUrl.contains("/cas/login") ||
                finalUrl.contains("auth4.jnu.edu.cn") ||
                finalUrl.contains("auth7.jnu.edu.cn")
            // 页面内容佐证，避免「跳过去了但会话没建起来」被误判为成功
            val looksLoggedIn = body.contains("Welcome,") && !body.contains("loginForm")
            val result = when {
                // 被明确踢回 CAS 登录页 = 会话确实失效
                redirectedToCas -> State.LOGGED_OUT
                resp.code == 200 && looksLoggedIn -> State.LOGGED_IN
                resp.code == 200 && !looksLoggedIn -> State.UNKNOWN
                else -> State.UNKNOWN
            }
            Log.d(
                TAG,
                "probe url=" + finalUrl + " code=" + resp.code +
                    " casRedirect=" + redirectedToCas +
                    " looksLoggedIn=" + looksLoggedIn + " -> " + result,
            )
            result
        }
    }.getOrElse {
        // 网络异常：无法判断，绝不能据此判定「未登录」
        Log.w(TAG, "probe failed", it)
        State.UNKNOWN
    }
}