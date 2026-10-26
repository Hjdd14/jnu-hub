package cn.jnu.hub.feature.jw

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * gid_ 的本地持久化。
 *
 * gid_ 是**按账号绑定**的教务会话令牌，每个学生登录后都不一样，
 * 所以只能「登录时抓一次、之后复用」，绝不能写死在代码里。
 *
 * 这里单独开一个 SharedPreferences（不能用 JwPrefs，那是用户可改的学号等信息），
 * 并**按学号分 key 存储**：同一台设备切换账号时不会互相污染。
 */
object JwGidStore {

    private const val TAG = "JwGidStore"
    private const val NAME = "jnu_jw_gid"
    private const val KEY_PREFIX = "gid_"
    private const val KEY_ANON = "gid__anonymous"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    /** 学号已知时按学号存；未知时存到 anonymous 槽，登录解析出学号后再迁移 */
    private fun key(): String {
        val xh = JwPrefs.studentId.trim()
        return if (xh.isBlank()) KEY_ANON else KEY_PREFIX + xh
    }

    /** 读取当前账号的 gid_；优先按学号，其次 anonymous 槽 */
    fun load(): String? {
        val store = p() ?: return null
        val xh = JwPrefs.studentId.trim()
        if (xh.isNotBlank()) {
            store.getString(KEY_PREFIX + xh, null)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return store.getString(KEY_ANON, null)?.takeIf { it.isNotBlank() }
    }

    fun save(gid: String) {
        val store = p() ?: return
        if (gid.isBlank()) return
        runCatching {
            store.edit().putString(key(), gid).apply()
            // 学号已知时同时写一份 anonymous 之外的兜底不必保留
            Log.d(TAG, "saved gid_ for " + key() + ", len=" + gid.length)
        }.onFailure { Log.w(TAG, "save gid failed", it) }
    }

    /** 学号从 anonymous 迁移时调用：把匿名槽的值搬到学号槽 */
    fun migrateToStudent(xh: String) {
        val store = p() ?: return
        val target = xh.trim()
        if (target.isBlank()) return
        runCatching {
            val anon = store.getString(KEY_ANON, null)
            if (!anon.isNullOrBlank() && store.getString(KEY_PREFIX + target, null).isNullOrBlank()) {
                store.edit().putString(KEY_PREFIX + target, anon).apply()
                Log.d(TAG, "migrated anonymous gid_ to " + target)
            }
        }
    }

    fun clear() {
        runCatching { p()?.edit()?.clear()?.apply() }
    }
}
