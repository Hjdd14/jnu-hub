package cn.jnu.hub.feature.jw

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 教务各页面数据的本地缓存。
 *
 * ## 为什么要它
 *
 * 教务取数只能靠 harvester（隐藏 WebView 走门户），单次 2~5 秒；
 * 一个页面往往要连取 2 次（学期 + 数据），首次打开要 20~30 秒。
 * 而成绩/考试/学分/学籍这些东西**一天内基本不会变**，没必要每次进来都重抓。
 *
 * 策略：
 *   - 有缓存 → 直接渲染，**不发任何请求**（秒开）
 *   - 用户点右上角「重新查询」→ 清缓存 + 强制重查
 *   - 缓存 6 小时自然过期（与 [JwSemesterCache] 一致）
 *
 * 存的是 JSON 字符串（各业务对象自己序列化），避免给每个模型都写 Parcelable。
 */
object JwDataCache {

    private const val TAG = "JwDataCache"
    private const val NAME = "jnu_jw_datacache"

    /** 缓存有效期：6 小时 */
    const val TTL_MS = 6L * 60 * 60 * 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    /** 一条缓存记录 */
    @Serializable
    data class Entry(val json: String, val savedAt: Long)

    fun put(key: String, payload: String) {
        val p = prefs ?: return
        runCatching {
            val entry = Entry(json = payload, savedAt = System.currentTimeMillis())
            p.edit().putString(key, json.encodeToString(Entry.serializer(), entry)).apply()
            Log.d(TAG, "put " + key + " len=" + payload.length)
        }.onFailure { Log.w(TAG, "put failed " + key, it) }
    }

    /** 读缓存；过期返回 null */
    fun get(key: String, ttlMs: Long = TTL_MS): Entry? {
        val p = prefs ?: return null
        val raw = p.getString(key, null) ?: return null
        return runCatching {
            val e = json.decodeFromString(Entry.serializer(), raw)
            val age = System.currentTimeMillis() - e.savedAt
            if (age < 0 || age > ttlMs) {
                Log.d(TAG, "expired " + key + " age=" + age + "ms")
                null
            } else {
                e
            }
        }.getOrNull()
    }

    fun clear(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
        Log.d(TAG, "clearAll")
    }

    // ---- 缓存 key ----
    const val KEY_GRADES = "grades_all"
    const val KEY_EXAMS = "exams"
    const val KEY_CREDITS = "credits"
    const val KEY_PROFILE = "profile"
}
