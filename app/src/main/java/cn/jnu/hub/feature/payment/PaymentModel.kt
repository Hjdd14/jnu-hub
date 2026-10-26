package cn.jnu.hub.feature.payment

/**
 * 水电缴费（番禺学生宿舍水电费）表单模型。
 *
 * 依据 docs/05_水电模块拆分设计.md 第三节实测：
 * 商户号固定 5001083，提交到 pay.jnu.edu.cn/jnushouyintai/pyxssssdf/order，
 * 表单编码 application/x-www-form-urlencoded。
 * App 不接触任何支付凭据，只负责把表单送到官方页面。
 */

/** 楼栋下拉选项：T1 – T12 */
object Buildings {
    val ALL: List<String> = (1..12).map { "T$it" }

    fun isValid(value: String): Boolean = value in ALL
}

/** 一次性缴费表单的当前输入值 */
data class PaymentForm(
    val jnuId: String = "",
    val username: String = "",
    val cardNo: String = "",
    val phone: String = "",
    val building: String = "T1",
    val roomNo: String = "",
    val money: String = "",
    val useCustomMoney: Boolean = false,
) {
    /** 快捷金额 */
    val quickAmounts: List<Int> get() = listOf(50, 100, 200, 500)
}

/** 单个字段的校验结果 */
data class FieldError(val field: PaymentField, val message: String)

enum class PaymentField {
    JNU_ID, USERNAME, CARD_NO, PHONE, BUILDING, ROOM_NO, MONEY,
}

/** 校验 */
object PaymentValidator {

    private val phoneRegex = Regex("^1[3-9]\\d{9}$")
    private val moneyRegex = Regex("^\\d+(\\.\\d{1,2})?$")

    fun validate(form: PaymentForm): List<FieldError> {
        val errors = mutableListOf<FieldError>()

        if (form.jnuId.isBlank()) {
            errors += FieldError(PaymentField.JNU_ID, "请填写学工号")
        } else if (form.jnuId.trim().length < 5) {
            errors += FieldError(PaymentField.JNU_ID, "学工号看起来不太对，请核对")
        }

        if (form.username.isBlank()) {
            errors += FieldError(PaymentField.USERNAME, "请填写姓名")
        }

        if (form.cardNo.isNotBlank() && form.cardNo.trim().length < 4) {
            errors += FieldError(PaymentField.CARD_NO, "校园卡号格式不正确（选填，可留空）")
        }

        val phone = form.phone.trim()
        if (phone.isBlank()) {
            errors += FieldError(PaymentField.PHONE, "请填写手机号码")
        } else if (!phoneRegex.matches(phone)) {
            errors += FieldError(PaymentField.PHONE, "请填写 11 位手机号码")
        }

        if (form.building.isBlank() || !Buildings.isValid(form.building)) {
            errors += FieldError(PaymentField.BUILDING, "请选择楼栋")
        }

        if (form.roomNo.isBlank()) {
            errors += FieldError(PaymentField.ROOM_NO, "请填写房间号")
        }

        val money = form.money.trim()
        when {
            money.isBlank() -> errors += FieldError(PaymentField.MONEY, "请选择或输入充值金额")
            !moneyRegex.matches(money) -> errors += FieldError(PaymentField.MONEY, "金额需为数字，最多两位小数")
            (money.toDoubleOrNull() ?: 0.0) <= 0.0 ->
                errors += FieldError(PaymentField.MONEY, "金额必须大于 0")
            (money.toDoubleOrNull() ?: 0.0) > 10000.0 ->
                errors += FieldError(PaymentField.MONEY, "单次充值请不要超过 10000 元")
        }

        return errors
    }

    fun amountOf(form: PaymentForm): Double = form.money.trim().toDoubleOrNull() ?: 0.0
}

/** 卡片上呈现的「易读地址」：T3-412 */
fun PaymentForm.addressLabel(): String =
    if (roomNo.isBlank()) building else "${building}-${roomNo.trim()}"
