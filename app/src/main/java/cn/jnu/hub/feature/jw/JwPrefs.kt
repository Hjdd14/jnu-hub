package cn.jnu.hub.feature.jw

import android.content.Context
import android.content.SharedPreferences

/**
 * 教务模块本地偏好。
 *
 * 保存学号是为了让 5 个页面都能在用户没登录会话缓存的情况下直接查询；
 * 这是本组私有的轻量存储，不依赖 core/，也不与其他模块共享 key 前缀。
 */
object JwPrefs {

    private const val NAME = "jnu_jw_prefs"
    private const val KEY_XH = "xh"
    private const val KEY_SEMESTER = "semester"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    var studentId: String
        get() = p()?.getString(KEY_XH, "").orEmpty()
        set(value) {
            p()?.edit()?.putString(KEY_XH, value.trim())?.apply()
        }

    var lastSemester: String
        get() = p()?.getString(KEY_SEMESTER, "").orEmpty()
        set(value) {
            p()?.edit()?.putString(KEY_SEMESTER, value)?.apply()
        }
}
