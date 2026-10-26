package cn.jnu.hub.feature.utility

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CAS ticket 桥：WebPageActivity 捕获到 ST- 票据后写入这里，
 * 水电查询页读取后调 CheckTicketAndLogin 换取水电户号。
 *
 * 为什么需要 ticket（见 all-classes.js :: MainpageCtrl.onActivate）：
 *
 *   k = Cookies.get("jnu-lp");  m = Cookies.get("jnu-lpid");
 *   if (k && m)       ssoUserLogin({userName: decode(k), userID: decode(m)})
 *   else if (ticket)  checkTicket(ticket)      // <-- 我们走这条
 *   else              exitAndClear()
 *
 * 而 saveUserInfoToCookies()（写入 jnu-lp）只在上面两条路径成功之后才执行。
 * 所以「没有 jnu-lp」的用户必须靠 URL 上的 ?ticket=ST-xxx 打破死循环。
 *
 * 站点取票据的方式（getRequest）：
 *   window.location.search.substring(1).split("=")[1]
 * 即取第一个参数的值 —— 参数名就是 ticket。
 */
object UtilityTicketBridge {

    private const val TAG = "UtilityTicket"

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
            val direct = uri.getQueryParameter("ticket")
                ?: uri.getQueryParameter("Ticket")
            val candidate = direct ?: Regex("[?&]ticket=([^&#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
            val value = candidate
                ?.let { Uri.decode(it) }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            // 只接受以 ST- 开头的 CAS 票据，避免误判
            value?.takeIf { it.startsWith(TICKET_PREFIX, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "extract failed: " + url, e)
            null
        }
    }

    /** 由 WebPageActivity 调用：检测到票据就写入，返回是否捕获成功 */
    fun offer(url: String?): Boolean {
        val t = extract(url) ?: return false
        _ticket.value = t
        Log.d(TAG, "captured ticket=" + t)
        return true
    }

    /** 查询页消费后调用，避免重复使用同一个票据（CAS 票据一次性） */
    fun consume(): String? {
        val t = _ticket.value
        _ticket.value = null
        return t
    }

    fun clear() {
        _ticket.value = null
    }
}
