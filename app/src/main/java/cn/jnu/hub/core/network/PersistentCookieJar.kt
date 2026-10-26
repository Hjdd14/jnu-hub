package cn.jnu.hub.core.network

import android.content.Context
import android.util.Log
import android.webkit.CookieManager as WebCookieManager
import cn.jnu.hub.core.security.Crypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File

/**
 * 共享 CookieJar：OkHttp 与 WebView 共用同一份 Cookie。
 *
 * 关键设计（均为实测踩坑结论）：
 * 1. domain 必须存纯域名（如 icas.jnu.edu.cn），不能存完整 URL，
 *    否则 Cookie.matches() 永远不成立，请求会被重定向回 CAS 登录页。
 * 2. WebView 的 HttpOnly Cookie（含 CASTGC）无法通过 getCookie() 读取，
 *    也因 SDK stub 限制无法调用 getAllCookies()。因此改为「把 OkHttp 侧的
 *    Cookie 主动注入 WebView」+「WebView 侧用 JS 读取非 HttpOnly 部分」双通道。
 * 3. 落盘加密（AES-GCM，密钥在 Android Keystore 内，不可导出）。
 */
class PersistentCookieJar(context: Context) : CookieJar {

    @Serializable
    data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Stored.serializer())
    private val file: File = File(context.filesDir, "cookies.enc")

    /** key = "domain|path|name" */
    private val cookies = LinkedHashMap<String, Stored>()
    private val lock = Any()

    init {
        load()
    }

    private fun keyOf(c: Stored) = c.domain + "|" + c.path + "|" + c.name

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            val text = String(Crypto.decrypt(file.readText()), Charsets.UTF_8)
            json.decodeFromString(serializer, text).forEach { cookies[keyOf(it)] = it }
        }.onFailure { Log.w(TAG, "load failed", it) }
    }

    @Synchronized
    private fun persist() {
        runCatching {
            val text = json.encodeToString(serializer, cookies.values.toList())
            file.writeText(Crypto.encrypt(text.toByteArray(Charsets.UTF_8)))
        }.onFailure { Log.w(TAG, "persist failed", it) }
    }

    /**
     * 从 WebView 同步 Cookie（key 为纯域名，value 为 "name=value; name2=value2"）。
     * 由 WebView 页面的 JS（document.cookie）或 getCookie() 提供。
     */
    @Synchronized
    fun syncFromWebView(hostCookies: Map<String, String>) {
        val now = System.currentTimeMillis()
        hostCookies.forEach { (rawHost, header) ->
            val host = normalizeHost(rawHost) ?: return@forEach
            if (header.isBlank()) return@forEach
            header.split(';').forEach { pair ->
                val trimmed = pair.trim()
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@forEach
                val name = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (name.isEmpty()) return@forEach
                val stored = Stored(
                    name = name,
                    value = value,
                    domain = host,
                    path = "/",
                    expiresAt = now + ONE_DAY_MS,
                    secure = true,
                    httpOnly = false,
                    hostOnly = true,
                )
                cookies[keyOf(stored)] = stored
            }
        }
        persist()
    }

    /** 把 OkHttp 侧 Cookie 推回 WebView，保证内嵌网页也带登录态 */
    fun syncToWebView() {
        val manager = WebCookieManager.getInstance()
        snapshot().forEach { s ->
            val scheme = if (s.secure) "https" else "http"
            val url = scheme + "://" + s.domain + s.path
            runCatching { manager.setCookie(url, s.name + "=" + s.value + "; Path=" + s.path) }
        }
        runCatching { manager.flush() }
    }

    /** 剥离协议、端口、路径，得到纯域名 */
    fun normalizeHost(raw: String?): String? {
        if (raw == null) return null
        var h = raw.trim()
        if (h.isEmpty()) return null
        h = h.substringAfter("://", h)
        h = h.substringBefore('/')
        h = h.substringBefore(':')
        h = h.removePrefix(".")
        if (h.isEmpty() || !h.contains('.')) return null
        return h.lowercase()
    }

    /**
     * 清除指定域名在 WebView 与 CookieJar 两侧的全部 Cookie。
     * 用于修复「旧业务 Cookie 与新 CAS 票据冲突导致 ERR_TOO_MANY_REDIRECTS」。
     */
    fun clearWebViewDomain(hosts: List<String>) {
        runCatching {
            val manager = WebCookieManager.getInstance()
            hosts.forEach { host ->
                val h = normalizeHost(host) ?: return@forEach
                // WebView 的 setCookie 传过期时间即为删除
                val expiry = "Thu, 01 Jan 1970 00:00:00 GMT"
                listOf("https", "http").forEach { scheme ->
                    listOf("$scheme://$h/", "$scheme://.$h/").forEach { url ->
                        runCatching { manager.setCookie(url, "JSESSIONID=; Max-Age=0; Expires=$expiry; Path=/") }
                        runCatching { manager.setCookie(url, "SERVERID=; Max-Age=0; Expires=$expiry; Path=/") }
                        runCatching { manager.setCookie(url, "session_id=; Max-Age=0; Expires=$expiry; Path=/") }
                    }
                }
            }
            manager.flush()
            // OkHttp 侧整体清掉这些域
            hosts.forEach { removeDomain(it) }
        }
    }

    /** 只清除某个域名下的 Cookie（用于退出单个业务系统，不影响统一认证会话） */
    @Synchronized
    fun removeDomain(host: String) {
        val h = normalizeHost(host) ?: return
        cookies.entries.removeAll { it.value.domain == h }
        persist()
        runCatching {
            val manager = WebCookieManager.getInstance()
            // WebView 侧无法按域删除单条，这里只做 OkHttp 侧清理
            manager.flush()
        }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        file.delete()
        runCatching {
            val manager = WebCookieManager.getInstance()
            manager.removeAllCookies(null)
            manager.flush()
        }
    }

    @Synchronized
    fun snapshot(): List<Stored> = cookies.values.toList()

    /**
     * 永远返回 false —— 请勿使用。
     *
     * CASTGC 是 HttpOnly Cookie，OkHttp 侧 CookieJar 读不到
     * （WebView 的 getCookie() 同样过滤 HttpOnly，SDK stub 不导出 getAllCookies）。
     * 曾有代码用它做「是否登录」前置判断，导致所有用户都被判成未登录：
     * 点「去登录」→ LoginActivity 发现会话其实有效 → 立刻关闭 → 回到原页仍未登录 → 无限循环。
     *
     * 判断登录态请改用服务端探测：
     * feature/login/LoginActivity.probeBlackboard() 或 feature/utility/UtilityCasProbe。
     */
    @Deprecated(
        "CASTGC 是 HttpOnly，CookieJar 读不到，此方法恒为 false。请改用服务端探测。",
        level = DeprecationLevel.WARNING,
    )
    @Synchronized
    fun hasCasTicket(): Boolean = false

    @Synchronized
    fun cookieNamesByDomain(): Map<String, List<String>> =
        cookies.values.groupBy({ it.domain }, { it.name })

    /** 手动注入一条 Cookie（供 WebView JS 抓取后回传） */
    @Synchronized
    fun put(name: String, value: String, domain: String, httpOnly: Boolean = false) {
        val host = normalizeHost(domain) ?: return
        if (name.isBlank()) return
        val stored = Stored(
            name = name,
            value = value,
            domain = host,
            path = "/",
            expiresAt = System.currentTimeMillis() + ONE_DAY_MS,
            secure = true,
            httpOnly = httpOnly,
            hostOnly = true,
        )
        cookies[keyOf(stored)] = stored
        persist()
    }

    override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            newCookies.forEach { c ->
                if (c.expiresAt < now) {
                    cookies.remove(c.domain + "|" + c.path + "|" + c.name)
                } else {
                    val host = normalizeHost(c.domain)
                    if (host != null) {
                        val stored = Stored(
                            name = c.name,
                            value = c.value,
                            domain = host,
                            path = if (c.path.isEmpty()) "/" else c.path,
                            expiresAt = c.expiresAt,
                            secure = c.secure,
                            httpOnly = c.httpOnly,
                            hostOnly = c.hostOnly,
                        )
                        cookies[keyOf(stored)] = stored
                    }
                }
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cookies.values.removeAll { it.expiresAt < now }
            return cookies.values.mapNotNull { s ->
                runCatching {
                    Cookie.Builder()
                        .name(s.name)
                        .value(s.value)
                        .domain(s.domain)
                        .path(s.path)
                        .expiresAt(s.expiresAt)
                        .apply {
                            if (s.secure) secure()
                            if (s.httpOnly) httpOnly()
                        }
                        .build()
                }.getOrNull()
            }.filter { it.matches(url) }
        }
    }

    companion object {
        private const val TAG = "CookieJar"
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    }
}