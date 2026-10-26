package cn.jnu.hub.feature.utility

import android.util.Log
import cn.jnu.hub.core.network.HttpClient

/** 从 Cookie 解出的水电账号 */
data class UtilityIdentity(
    val userName: String,
    val customerId: String,
)

/**
 * 水电户号的自动识别（路径 B）。
 *
 * 站点前端逻辑（all-classes.js :: MainpageCtrl.onActivate）：
 *
 *   var a = Ext.util.crypt.decode;
 *   var k = Ext.util.Cookies.get("jnu-lp")  || "";   // AES 加密的 userName
 *   var m = Ext.util.Cookies.get("jnu-lpid")|| "";   // AES 加密的 customerId
 *   if (k && m) { g.ssoUserLogin({userName:a(k), userID:a(m)}) }
 *   else if (ticket) { g.checkTicket(ticket) } else { g.exitAndClear() }
 *
 * 因此：只要 CookieJar 里有 jnu-lp / jnu-lpid，就能本地解出户号，
 * 不需要解析任何 HTML（首页 HTML 是空壳，内容全部由 JS 渲染）。
 *
 * 这两个 Cookie 由网页端登录成功后 saveUserInfoToCookies() 写入，有效期到 2100 年。
 */
object UtilityIdentityResolver {

    private const val TAG = "UtilityIdentity"

    const val COOKIE_USER = "jnu-lp"
    const val COOKIE_USER_ID = "jnu-lpid"

    /** 读取并解密；无法解析时返回 null（调用方退回手动输入） */
    fun resolve(): UtilityIdentity? {
        val rawUser = rawCookie(COOKIE_USER)
        val rawId = rawCookie(COOKIE_USER_ID)

        Log.d(TAG, "raw jnu-lp=" + rawUser + " jnu-lpid=" + rawId)

        if (rawUser.isNullOrBlank() || rawId.isNullOrBlank()) {
            Log.d(TAG, "cookie missing -> need manual input")
            return null
        }

        val userName = UtilityCrypto.decrypt(rawUser)
        val customerId = UtilityCrypto.decrypt(rawId)

        Log.d(TAG, "decoded userName=" + userName + " customerId=" + customerId)

        if (userName.isNullOrBlank() || customerId.isNullOrBlank()) return null
        return UtilityIdentity(userName.trim(), customerId.trim())
    }

    /** 是否已存在 jnu-lp（用于决定是否提示去网页端同步一次） */
    fun hasCookies(): Boolean = !rawCookie(COOKIE_USER).isNullOrBlank()

    /**
     * 直接从 CookieJar 快照里取，而不是 loadForRequest(url)：
     * 后者会按过期时间 + URL 匹配过滤，WebView 同步过来的 cookie 只有 1 天有效期，
     * 且 domain 匹配规则严格，容易取不到。
     */
    private fun rawCookie(name: String): String? = runCatching {
        HttpClient.cookieJar()
            .snapshot()
            .firstOrNull { it.name == name && it.domain.contains("pynhcx") }
            ?.value
            ?: HttpClient.cookieJar().snapshot().firstOrNull { it.name == name }?.value
    }.getOrNull()
}
