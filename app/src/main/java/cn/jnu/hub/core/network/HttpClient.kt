package cn.jnu.hub.core.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * 全局单例 OkHttpClient：
 * - 复用持久化 CookieJar（与 WebView 共享 SSO 会话）
 * - 手动跟随重定向，便于识别「被重定向到 CAS 登录页」= 会话过期
 * - UA 与 WebView 保持一致，避免风控差异
 */
object HttpClient {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; PJD110) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var jar: PersistentCookieJar? = null

    fun init(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            val cookieJar = PersistentCookieJar(context.applicationContext)
            jar = cookieJar

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                    chain.proceed(req)
                }
                .addInterceptor(logging)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    fun get(): OkHttpClient = client ?: error("HttpClient 未初始化，请先在 Application 中调用 init()")

    fun cookieJar(): PersistentCookieJar = jar ?: error("HttpClient 未初始化")
}
