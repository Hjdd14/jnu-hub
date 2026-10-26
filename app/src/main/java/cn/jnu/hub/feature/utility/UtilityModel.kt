package cn.jnu.hub.feature.utility

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 水电查询返回的原始「键值对」行。
 *
 * 服务端很多字段（账号名称、补贴余量、单价…）都是以
 * `[{keyName, keyValue}]` 的形式下发，这里统一成可查询的结构。
 */
data class KeyValueRow(
    val keyName: String,
    val keyValue: String,
) {
    companion object {
        /** 从 jsonObject 的安全视图里取 [keyName, keyValue] 列表 */
        fun parse(items: List<JsonObject>?): List<KeyValueRow> =
            items.orEmpty().map { obj ->
                KeyValueRow(
                    keyName = obj.stringOrEmpty("keyName"),
                    keyValue = obj.stringOrEmpty("keyValue"),
                )
            }

        /** 在列表里按名称模糊查找（去掉空格/全角差异，宽容大小写） */
        fun find(rows: List<KeyValueRow>, vararg names: String): KeyValueRow? {
            val targets = names.map { it.replace(" ", "") }
            return rows.firstOrNull { row ->
                val k = row.keyName.replace(" ", "")
                targets.any { t -> k == t || k.contains(t) }
            }
        }

        /** 从「0.6259元/度」这类文本里抠出第一个数字 */
        fun number(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            return Regex("-?\\d+(\\.\\d+)?").find(text.replace(",", ""))
                ?.value
                ?.toDoubleOrNull()
        }

        /** 从「0.6259元/度」这类文本里抠出单位（元/度 → 元/度） */
        fun unit(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val m = Regex("[^\\d.\\-]+").findAll(text).map { it.value }.joinToString("")
            return m.takeIf { it.isNotBlank() }
        }
    }
}

/** 统一取字符串，缺失返回空串 */
internal fun JsonObject.stringOrEmpty(key: String): String {
    val el = this[key] ?: return ""
    return el.toString().trim('"')
}

/** 取数字（服务端有时给 "438.27" 字符串） */
internal fun JsonObject.doubleOrNull(key: String): Double? {
    val el = this[key] ?: return null
    val raw = el.toString().trim('"')
    return raw.toDoubleOrNull()
}

/** 取嵌套对象 */
internal fun JsonObject.objOrNull(key: String): JsonObject? =
    runCatching { this[key]?.jsonObject }.getOrNull()

/** 取布尔（true / "true" / 1） */
internal fun JsonObject.boolOrNull(key: String): Boolean? {
    val raw = this[key]?.toString()?.trim('"') ?: return null
    return when (raw.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
