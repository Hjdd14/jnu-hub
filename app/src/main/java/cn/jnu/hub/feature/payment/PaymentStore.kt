package cn.jnu.hub.feature.payment

import android.content.Context
import android.content.SharedPreferences

/**
 * 缴费表单的本地记忆（SharedPreferences）。
 *
 * 只记非敏感、用户希望免重复输入的内容：学工号 / 姓名 / 手机 / 楼栋 / 房间号。
 * 校园卡号不记忆（属于卡片凭据类信息，默认不落盘）。
 *
 * 说明：SharedPreferences 存放在应用私有目录，其它应用不可读；
 * 如后续需要更强保护，可切换到 core/security/Crypto 做密文存储。
 */
class PaymentStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PaymentForm {
        val building = prefs.getString(KEY_BUILDING, null)
            ?.takeIf { Buildings.isValid(it) }
            ?: PaymentForm().building
        return PaymentForm(
            jnuId = prefs.getString(KEY_JNU_ID, "").orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            cardNo = "",
            phone = prefs.getString(KEY_PHONE, "").orEmpty(),
            building = building,
            roomNo = prefs.getString(KEY_ROOM_NO, "").orEmpty(),
        )
    }

    /** 只持久化需要记忆的字段 */
    fun save(form: PaymentForm) {
        prefs.edit()
            .putString(KEY_JNU_ID, form.jnuId.trim())
            .putString(KEY_USERNAME, form.username.trim())
            .putString(KEY_PHONE, form.phone.trim())
            .putString(KEY_BUILDING, form.building)
            .putString(KEY_ROOM_NO, form.roomNo.trim())
            .putString(KEY_LAST_MONEY, form.money.trim())
            .apply()
    }

    fun lastMoney(): String = prefs.getString(KEY_LAST_MONEY, "").orEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "jnu_hub_payment"
        const val KEY_JNU_ID = "jnuId"
        const val KEY_USERNAME = "username"
        const val KEY_PHONE = "phone"
        const val KEY_BUILDING = "building"
        const val KEY_ROOM_NO = "roomNo"
        const val KEY_LAST_MONEY = "lastMoney"
    }
}
