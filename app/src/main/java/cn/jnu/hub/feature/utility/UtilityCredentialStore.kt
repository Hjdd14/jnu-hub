package cn.jnu.hub.feature.utility

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户手动填写的水电户号（路径 C，保底方案）。
 *
 * 当 Cookie 自动识别失败（用户从没在网页端登录过水电系统）时，
 * 允许用户手填 T 开头的户号，本地记住，下次自动填充。
 *
 * 户号本身不是敏感凭据（就是宿舍水电账号），用普通 SharedPreferences 即可。
 */
class UtilityCredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存户号与客户号 */
    fun saveAccount(userName: String, customerId: String = "") {
        prefs.edit()
            .putString(KEY_USER_NAME, userName.trim())
            .putString(KEY_USER_ID, customerId.trim())
            .apply()
    }

    fun accountName(): String = prefs.getString(KEY_USER_NAME, "").orEmpty()

    fun customerId(): String = prefs.getString(KEY_USER_ID, "").orEmpty()

    fun hasAccount(): Boolean = accountName().isNotBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "jnu_hub_utility"
        const val KEY_USER_NAME = "utility_user_name"
        const val KEY_USER_ID = "utility_customer_id"
    }
}
