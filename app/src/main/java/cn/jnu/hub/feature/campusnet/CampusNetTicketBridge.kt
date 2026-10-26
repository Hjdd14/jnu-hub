package cn.jnu.hub.feature.campusnet

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 校园网 CAS 票据桥（独立实现，不复用 UtilityTicketBridge）。
 *
 * 为什么不复用 UtilityTicketBridge：
 * - 那个 bridge 是「一次性消费」的：水电页 ON_RESUME 时会 consume() 掉票据换户号。
 *   如果校园网也往里写，两个模块会互相抢票据，谁先醒谁拿走，另一个永远拿不到。
 * - 两个模块的生命周期完全独立（水电在 Tab 里常驻，校园网是二级页），
 *   共享状态会引入难以复现的时序 bug。
 * 因此这里维护一份完全独立的票据，只服务 mynet。
 *
 * ticket 从哪来（见 docs/02_接口台账.md C.1）：
 *   GET https://mynet.jnu.edu.cn/app/login
 *     → 302 https://icas.jnu.edu.cn/cas/login?service=https%3A%2F%2Fmynet.jnu.edu.cn%2Fapp%2Flogin
 *     → 302 https://mynet.jnu.edu.cn/app/login?ticket=ST-xxxx-cas   ← 我们在这里截获
 *     → 200 登录后首页（服务端 Set-Cookie: JSESSIONID）
 *
 * 这个 bridge 由内嵌 WebView 的 WebViewClient 在每一跳回调里调用 [offer]。
 */
object CampusNetTicketBridge {

    private const val TAG = "CampusNetTicket"

    /** CAS 票据前缀（ST-xxxx-cas） */
    const val TICKET_PREFIX = "ST-"

    private val _ticket = MutableStateFlow<String?>(null)

    /** 最近一次捕获到的 ticket；null 表示还没捕获 */
    val ticket: StateFlow<String?> = _ticket.asStateFlow()

    /** 是否已捕获到（消费后清空） */
    val hasTicket: Boolean get() = _ticket.value != null

    /**
     * 从 URL 里提取 ticket。
     * 兼容 ?ticket=ST-x 与 &ticket=ST-x，以及被编码的值。
     */
    fun extract(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val direct = uri.getQueryParameter("ticket") ?: uri.getQueryParameter("Ticket")
            val candidate = direct
                ?: Regex("[?&]ticket=([^&#]+)", RegexOption.IGNORE_CASE)
                    .find(url)?.groupValues?.getOrNull(1)
            val value = candidate
                ?.let { Uri.decode(it) }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            // 只接受以 ST- 开头的 CAS 票据，避免误判普通参数
            value?.takeIf { it.startsWith(TICKET_PREFIX, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "extract failed: " + url, e)
            null
        }
    }

    /** 由内嵌 WebView 调用：检测到票据就写入，返回是否捕获成功 */
    fun offer(url: String?): Boolean {
        val t = extract(url) ?: return false
        if (_ticket.value == t) return true
        _ticket.value = t
        Log.d(TAG, "captured ticket=" + t)
        return true
    }

    /** 消费票据（CAS 票据一次性，用过就不能再用） */
    fun consume(): String? {
        val t = _ticket.value
        _ticket.value = null
        return t
    }

    fun clear() {
        _ticket.value = null
    }
}
