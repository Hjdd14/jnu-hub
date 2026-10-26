package cn.jnu.hub.feature.pe

import android.content.Context
import android.content.SharedPreferences

/**
 * 体测模块本地偏好（独立于其它模块的 key 前缀）。
 *
 * 体测系统是独立的 ASP.NET 站点，与 CAS 无关，必须单独记住账号密码，
 * 否则每次打开都要手输 8 位生日密码 + 验证码。
 */
object PePrefs {

    private const val NAME = "jnu_pe_prefs"
    private const val KEY_USER = "username"
    private const val KEY_PWD = "password"
    private const val KEY_REMEMBER = "remember"
    private const val KEY_YEAR = "last_year"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    var username: String
        get() = p()?.getString(KEY_USER, "").orEmpty()
        set(v) { p()?.edit()?.putString(KEY_USER, v.trim())?.apply() }

    var password: String
        get() = p()?.getString(KEY_PWD, "").orEmpty()
        set(v) { p()?.edit()?.putString(KEY_PWD, v)?.apply() }

    /** 是否记住账号密码；关闭时清除密码 */
    var remember: Boolean
        get() = p()?.getBoolean(KEY_REMEMBER, true) ?: true
        set(v) {
            p()?.edit()?.putBoolean(KEY_REMEMBER, v)?.apply()
            if (!v) password = ""
        }

    var lastYear: String
        get() = p()?.getString(KEY_YEAR, "").orEmpty()
        set(v) { p()?.edit()?.putString(KEY_YEAR, v)?.apply() }
}
