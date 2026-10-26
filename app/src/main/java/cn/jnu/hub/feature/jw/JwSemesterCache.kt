package cn.jnu.hub.feature.jw

import android.util.Log

/**
 * 当前学年学期的内存缓存。
 *
 * ## 为什么需要
 *
 * 每个教务页面在 fetch() 里都会先取一次学期（dqxnxq），再取自己的数据。
 * 而取学期本身要跑一遍完整的 harvester（起隐藏 WebView、走门户、发请求），
 * 一次 ~2 秒。页面一多，同一个值被反复抓，既慢又容易把 WebView 拖到并发冲突。
 *
 * 学期代码在一个学期内不会变，缓存 6 小时足够。
 */
object JwSemesterCache {

    private const val TAG = "JwSemesterCache"

    private const val TTL_MS = 6L * 60 * 60 * 1000

    @Volatile
    private var cached: JwSemester? = null

    @Volatile
    private var savedAt: Long = 0L

    /**
     * 取学期：命中缓存直接返回，否则调用 [fetch] 并缓存。
     *
     * @param force 为 true 时忽略缓存（手动刷新场景）
     */
    suspend fun get(force: Boolean = false, fetch: suspend () -> JwSemester): JwSemester {
        val c = cached
        if (!force && c != null && System.currentTimeMillis() - savedAt < TTL_MS) {
            Log.d(TAG, "hit dm=" + c.dm)
            return c
        }
        val fresh = fetch()
        cached = fresh
        savedAt = System.currentTimeMillis()
        Log.d(TAG, "refresh dm=" + fresh.dm)
        return fresh
    }

    fun clear() {
        cached = null
        savedAt = 0L
    }
}
