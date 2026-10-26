package cn.jnu.hub.feature.utility

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 水电查询的领域模型。
 *
 * 服务端一律返回 `{"d": {...}}` 包裹的 JSON，字段大小写混乱（Success / success），
 * 所以这里全部用 kotlinx.serialization 的 JsonObject 手动容错解析，不使用 @Serializable 强绑定，
 * 避免任何一个字段缺失导致整页失败。
 */

/** 能源类型编号（与服务端 EnergyType 一致） */
object EnergyTypes {
    const val ELECTRIC = 2
    const val COLD_WATER = 3
    const val HOT_WATER = 4

    /** 请求里的 0 = 全部 */
    const val ALL = 0

    val ordered = listOf(ELECTRIC, COLD_WATER, HOT_WATER)

    fun label(type: Int): String = when (type) {
        ELECTRIC -> "电"
        COLD_WATER -> "冷水"
        HOT_WATER -> "热水"
        else -> "能源"
    }

    fun defaultUnit(type: Int): String = when (type) {
        ELECTRIC -> "度"
        else -> "吨"
    }
}

/** CheckSessionAndLogin 的登录结果 */
data class UtilityLoginResult(
    val isSuccess: Boolean,
    val customerId: Long?,
    val userName: String?,
    val message: String?,
)

/** 账号总览：账号名 / 余额 / 补贴 / 单价 */
data class UtilityAccount(
    val accountName: String? = null,
    val balance: Double? = null,
    val subsidyPeople: String? = null,
    /** keyName → keyValue，原样保留（含单位） */
    val allowance: List<KeyValueRow> = emptyList(),
    val unitPrice: List<KeyValueRow> = emptyList(),
)

/** 单个能源的账单条目 */
data class BillItem(
    val energyType: Int,
    val energyName: String,
    val allowance: Double?,
    val unitPrice: Double?,
    val preValue: Double?,
    val curValue: Double?,
    val energyValue: Double?,
    val chargeValue: Double?,
    val meterName: String?,
) {
    val displayName: String get() = energyName.ifBlank { EnergyTypes.label(energyType) }
}

/** 补贴额度 */
data class SubsidyItem(
    val itemType: Int,
    val itemName: String,
    val itemUnit: String,
    val totalValue: Double,
    /** 服务端拼写就是 avalibleValue（不是 available） */
    val availableValue: Double,
) {
    val displayName: String
        get() = itemName.ifBlank { EnergyTypes.label(itemType) + "补贴" }
}

/** 每日用量数据点 */
data class UsagePoint(
    val timeMillis: Long,
    val value: Double,
)

/** 单个能源的时间序列（每日用量 / 月度消费共用） */
data class EnergySeries(
    val energyType: Int,
    val energyName: String,
    val points: List<UsagePoint>,
)

/** 充值 / 消费记录 */
data class PaymentRecord(
    val logTime: Long,
    val paymentType: String,
    val itemType: String,
    val value: Double,
    val unit: String,
)

/** 一次完整查询的结果 */
data class UtilitySnapshot(
    val account: UtilityAccount,
    val subsidies: List<SubsidyItem>,
    val bills: List<BillItem>,
    val dailyUsage: List<EnergySeries>,
    val monthly: List<EnergySeries>,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val LOW_BALANCE_THRESHOLD = 30.0
    }

    val isLowBalance: Boolean
        get() = (account.balance ?: Double.MAX_VALUE) < LOW_BALANCE_THRESHOLD
}

/** 解析过程中的容错工具 */
internal object UtilityJson {

    fun objOfAny(el: kotlinx.serialization.json.JsonElement?): JsonObject? =
        runCatching { el?.jsonObject }.getOrNull()

    /** 从 [startIndex] 开始解析，返回结果与下一个 offset */
    fun parseWarnings(vararg messages: String?): List<String> =
        messages.filterNotNull().filter { it.isNotBlank() && !it.equals("Succeed", true) }
}
