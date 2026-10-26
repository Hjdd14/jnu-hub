package cn.jnu.hub.feature.jw

import android.content.Context

/**
 * 持有 ApplicationContext，供 SharedPreferences 初始化。
 *
 * 为什么不强行依赖 JnuHubApp：feature/jw 是独立模块，不应假设宿主 Application
 * 一定帮忙调用 init；这里用「首次使用时惰性初始化」兜底，
 * 由任意一个 Route 在 Compose 启动时调一次 [install] 即可。
 */
object JwContext {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = ctx
                    JwPrefs.init(ctx)
                    JwGidStore.init(ctx)
                    JwDataCache.init(ctx)
                    // 教务接口的 WebView 兜底需要 Context
                    JwApi.install(ctx)
                }
            }
        }
    }

    /** 在 install 之前调用 JwPrefs 也不会崩，只是读写为空 */
    fun get(): Context? = appContext
}