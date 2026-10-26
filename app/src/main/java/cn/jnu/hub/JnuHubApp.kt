package cn.jnu.hub

import android.app.Application
import android.util.Log
import android.webkit.CookieManager
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.feature.jw.JwSession

class JnuHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClient.init(this)
        // 教务接口的 WebView 兜底路径需要 Context（详见 JwJsFetcher）
        cn.jnu.hub.feature.jw.JwApi.install(this)
        cn.jnu.hub.feature.jw.JwContext.install(this)
        // App 冷启动后，WebView 里可能已经有上次登录留下的 Cookie，
        // 但 OkHttp 的 CookieJar 是另一套存储，必须主动同步一次，
        // 否则会出现「WebView 有 Cookie、OkHttp 探测却说已过期」的假阴性。
        syncWebViewCookies()
        // 临时诊断：验证 authUrl 纯 HTTP 链路是否可行（验证完会删掉）
        Thread {
            Thread.sleep(8000)
            runCatching { cn.jnu.hub.feature.jw.JwApi.verifyAuthUrlChain() }
        }.start()
        // gid_ 按账号绑定，冷启动先从本地恢复（真正的刷新在需要时进行）
        cn.jnu.hub.feature.jw.JwGidStore.init(this)
        cn.jnu.hub.feature.jw.JwSession.preloadGid()?.let {
            Log.i("JnuHubApp", "preloaded gid_ = " + it.take(20) + "…")
        }
    }

    private fun syncWebViewCookies() {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.flush()
            val map = mutableMapOf<String, String>()
            JnuHosts.cookieHosts.forEach { url ->
                val host = url.substringAfter("://").substringBefore('/')
                val raw = manager.getCookie(url)
                if (!raw.isNullOrBlank()) map[host] = raw
            }
            if (map.isNotEmpty()) {
                HttpClient.cookieJar().syncFromWebView(map)
                Log.i("JnuHubApp", "cold start synced " + map.size + " hosts from WebView")
            }
        }.onFailure { Log.w("JnuHubApp", "cookie sync failed", it) }

        syncJwSession()
    }

    /**
     * 单独把教务会话（jw 域）捞一次。
     *
     * 为什么不能只靠上面那轮通用同步：
     * 1. jw 的 loginSuccess 落点是 **http** 开头，http 与 https 是不同 origin，
     *    只查 https 时读不到 WebView 里 http origin 上的 cookie；
     * 2. 通用同步用的是 JnuHosts 里的 https 地址，正好漏掉这种情况。
     *
     * 判定标准与 JwSession 保持一致：**MOD_AMP_AUTH / JSESSIONID 等会话 cookie 任一存在**。
     * CampusSphere 实测只下发 MOD_AMP_AUTH，JNU_AUTH_VERIFY_TOKEN 是网关守卫 cookie，
     * 不能单独当作「已登录」的证据。
     */
    private fun syncJwSession() {
        runCatching {
            val manager = CookieManager.getInstance()
            manager.flush()
            val map = mutableMapOf<String, String>()
            // http 与 https 是两个 origin，两处都要读
            listOf("https://jw.jnu.edu.cn/", "http://jw.jnu.edu.cn/").forEach { url ->
                manager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { raw ->
                    map["jw.jnu.edu.cn"] = (map["jw.jnu.edu.cn"]?.plus("; ") ?: "") + raw
                }
            }
            val merged = map["jw.jnu.edu.cn"].orEmpty()
            if (merged.isNotBlank()) {
                HttpClient.cookieJar().syncFromWebView(map)
                val hasSession = merged.contains("MOD_AMP_AUTH", true) ||
                    merged.contains("JSESSIONID", true) ||
                    merged.contains("session", true) ||
                    merged.contains("route", true)
                Log.i(
                    "JnuHubApp",
                    "jw session restored from WebView: hasSession=" + hasSession +
                        ", len=" + merged.length,
                )
            }
        }.onFailure { Log.w("JnuHubApp", "jw session sync failed", it) }
    }
}