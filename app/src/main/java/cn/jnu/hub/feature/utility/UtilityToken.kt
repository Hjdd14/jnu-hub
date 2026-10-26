package cn.jnu.hub.feature.utility

import android.util.Log
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 复刻站点全局 Ajax 拦截器（all-classes.js :: Ext.Ajax.on("beforerequest")）：
 *
 *   var h = new Date();
 *   var g = EEM.user || {userID: 0};
 *   var k = { userID: g.userID, tokenTime: Ext.Date.format(h, "Y-m-d H:i:s") };
 *   k = Ext.encode(k);
 *   k = Ext.util.crypt.AES.encrypt(k, "CetSoftEEMSysWeb");
 *   k = k.toString();
 *   k = encodeURI(k);
 *   j.headers.Token = k;
 *   j.headers.DateTime = Ext.Date.format(h, "Y-m-d H:i:s");
 *
 * 即：Token = URLEncode(AES({userID, tokenTime}))，DateTime = 同一时间串。
 * 这两个头是站点每个请求都带的，缺了服务端可能判定为非受信调用。
 */
object UtilityToken {

    private const val TAG = "UtilityToken"

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    data class Headers(val token: String, val dateTime: String)

    /**
     * 生成 Token / DateTime。
     * @param userId 当前已登录的 customerId；未登录时传 "0"（与前端 EEM.user||{userID:0} 一致）
     */
    fun build(userId: String?): Headers {
        val now = Date()
        val timeStr = timeFmt.format(now)
        val uid = userId?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: "0"

        val payload = "{\"userID\":" + uid + ",\"tokenTime\":\"" + timeStr + "\"}"

        val encrypted = runCatching { UtilityCrypto.encrypt(payload) }.getOrElse { e ->
            Log.w(TAG, "encrypt failed", e)
            ""
        }

        val token = if (encrypted.isEmpty()) {
            ""
        } else {
            // 前端用 encodeURI：不编码 A-Z a-z 0-9 - _ . ! ~ * ( ) 以及 ; , / ? : @ & = + $ #
            encodeUri(encrypted)
        }

        return Headers(token, timeStr)
    }

    /**
     * 等价 JS 的 encodeURI：保留 URI 保留字符，只编码其余。
     * 这里用 URLEncoder 再还原被过度编码的保留字符（URLEncoder 会把空格变 +，这里不会出现空格）。
     */
    private fun encodeUri(s: String): String {
        val encoded = URLEncoder.encode(s, "UTF-8")
        return encoded
            .replace("+", "%20")
            .replace("%2F", "/").replace("%2f", "/")
            .replace("%3A", ":").replace("%3a", ":")
            .replace("%3D", "=").replace("%3d", "=")
            .replace("%2B", "+").replace("%2b", "+")
            .replace("%2C", ",").replace("%2c", ",")
            .replace("%3B", ";").replace("%3b", ";")
            .replace("%24", "$")
            .replace("%26", "&")
            .replace("%23", "#")
            .replace("%40", "@")
            .replace("%3F", "?").replace("%3f", "?")
            .replace("*", "*")
            .replace("%28", "(").replace("%29", ")")
            .replace("%27", "'")
            .replace("%21", "!")
            .replace("%7E", "~")
    }
}
