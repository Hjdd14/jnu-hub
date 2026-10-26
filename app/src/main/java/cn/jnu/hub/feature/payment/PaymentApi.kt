package cn.jnu.hub.feature.payment

import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/** 缴费提交结果 */
sealed class PaymentSubmitResult {
    /** 服务端返回了一个可打开支付页的 URL */
    data class Redirect(val url: String) : PaymentSubmitResult()

    /** 服务端直接回了 HTML（收银台页面嵌在响应里），用 data:URL 交给 WebView */
    data class InlinePage(val html: String, val baseUrl: String) : PaymentSubmitResult()

    /** 服务端明确报错 */
    data class Failed(val message: String) : PaymentSubmitResult()
}

class PaymentException(message: String) : Exception(message)

/**
 * 水电缴费提交。
 *
 * POST https://pay.jnu.edu.cn/jnushouyintai/pyxssssdf/order
 * Content-Type: application/x-www-form-urlencoded
 * 字段：jnuId / username / cardNo / phone / building / roomNo / money
 *
 * 说明：缴费无需 CAS 登录；提交后拿到官方支付页地址或页面，交给 WebPageActivity 打开。
 * App 不接触任何支付凭据。
 */
object PaymentApi {

    private const val ORDER_URL = "${JnuHosts.PAY}/jnushouyintai/pyxssssdf/order"
    private const val PAY_URL = "${JnuHosts.PAY}/jnushouyintai/pyxssssdf/pay"

    /** 商户号（番禺学生宿舍水电费），仅用于页面展示 */
    const val MERCHANT_ID = "5001083"
    const val MERCHANT_NAME = "番禺学生宿舍水电费"

    /** 官方收银台入口（备用/用户手动前往） */
    val payPageUrl: String get() = PAY_URL

    suspend fun submit(form: PaymentForm): PaymentSubmitResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("jnuId", form.jnuId.trim())
            .add("username", form.username.trim())
            .add("cardNo", form.cardNo.trim())
            .add("phone", form.phone.trim())
            .add("building", form.building.trim())
            .add("roomNo", form.roomNo.trim())
            .add("money", formatMoney(form.money))
            .build()

        val request = Request.Builder()
            .url(ORDER_URL)
            .post(body)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", PAY_URL)
            .build()

        val (code, finalUrl, text) = try {
            HttpClient.get().newCall(request).execute().use { resp ->
                Triple(resp.code, resp.request.url.toString(), resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            throw PaymentException("网络异常：${e.message ?: "请检查网络后重试"}")
        }

        if (code !in 200..399) {
            return@withContext PaymentSubmitResult.Failed("提交失败（HTTP $code），请稍后重试")
        }

        interpret(finalUrl, text)
    }

    /**
     * 解析服务端响应，三种情况：
     * 1. 已被 302 到支付页 → 用最终 URL 打开
     * 2. 返回 JSON，含 url / redirectUrl 等字段 → 打开该 URL
     * 3. 返回 HTML 页面 → 用 data:URL 内联打开
     */
    private fun interpret(finalUrl: String, text: String): PaymentSubmitResult {
        val trimmed = text.trim()

        // 1) 已重定向到官方支付/收银台
        if (finalUrl.contains("/pay") && finalUrl != ORDER_URL) {
            return PaymentSubmitResult.Redirect(finalUrl)
        }

        // 2) JSON 响应
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            extractUrl(trimmed)?.let { return PaymentSubmitResult.Redirect(it) }
            val msg = extractJsonString(trimmed, "message")
                ?: extractJsonString(trimmed, "msg")
                ?: extractJsonString(trimmed, "errorMsg")
            return PaymentSubmitResult.Failed(msg?.takeIf { it.isNotBlank() } ?: "服务端未返回可用的支付页地址")
        }

        // 3) HTML 响应
        if (trimmed.startsWith("<")) {
            // 页面里带 meta refresh / window.location 时优先取真实地址
            extractHtmlRedirect(trimmed)?.let { return PaymentSubmitResult.Redirect(it) }
            if (looksLikeError(trimmed)) {
                val msg = extractErrText(trimmed)
                if (msg != null) return PaymentSubmitResult.Failed(msg)
            }
            return PaymentSubmitResult.InlinePage(trimmed, ORDER_URL)
        }

        return PaymentSubmitResult.Failed("无法识别的服务端响应，请前往官方收银台重试")
    }

    private fun formatMoney(raw: String): String {
        val v = raw.trim().toDoubleOrNull() ?: return raw.trim()
        return if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
    }

    private fun extractUrl(text: String): String? {
        val keys = listOf("url", "redirectUrl", "redirect_url", "payUrl", "pay_url", "location", "data")
        for (key in keys) {
            val v = extractJsonString(text, key)?.trim().orEmpty()
            if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("/")) {
                return if (v.startsWith("/")) "${JnuHosts.PAY}$v" else v
            }
        }
        return null
    }

    private fun extractJsonString(text: String, key: String): String? {
        val regex = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractHtmlRedirect(html: String): String? {
        // <meta http-equiv="refresh" content="0;url=...">
        Regex("(?i)url=([^\"'\\s>]+)").find(html)?.let { m ->
            val u = m.groupValues[1].trim()
            if (u.startsWith("http")) return u
        }
        // window.location.href = "..."
        Regex("(?i)location\\.(?:href\\s*=\\s*|replace\\(\\s*)\"([^\"]+)\"")
            .find(html)?.let { m ->
                val u = m.groupValues[1]
                if (u.startsWith("http")) return u
            }
        return null
    }

    private fun looksLikeError(html: String): Boolean {
        val lower = html.lowercase()
        return listOf("错误", "失败", "请输入", "不存在", "error").any { lower.contains(it.lowercase()) }
    }

    private fun extractErrText(html: String): String? {
        // 优先取常见提示节点的文本
        Regex("(?is)<(?:span|div|p)[^>]*class=[\"']?[^\"'>]*(?:error|tip|warn|alert)[^\"'>]*[\"']?[^>]*>(.*?)</")
            .find(html)?.let { m ->
                val t = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                if (t.isNotBlank()) return t
            }
        return null
    }
}
