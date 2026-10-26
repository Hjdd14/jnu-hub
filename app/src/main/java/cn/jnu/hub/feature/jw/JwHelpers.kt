package cn.jnu.hub.feature.jw

import java.net.URLEncoder
import java.time.LocalDate

/** 纯函数工具：位串解析、文本抽取、表单编码。全部不依赖 Android，便于复用与排查。 */

internal const val JW_WEEKDAY_NAMES = "星期一,星期二,星期三,星期四,星期五,星期六,星期日"

fun jwWeekdayName(index: Int): String {
    if (index !in 1..7) return "星期?"
    return JW_WEEKDAY_NAMES.split(',').getOrElse(index - 1) { "星期?" }
}

fun jwWeekdayShort(index: Int): String = when (index) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"
    else -> "?"
}

/**
 * 把位串压缩为人类可读的周次区间。
 * "111011111111111111" -> "1-3,5-18"
 */
fun jwCompressWeekRanges(bits: String): String {
    if (bits.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = -1
    for (i in bits.indices) {
        val on = bits[i] == '1'
        if (on && start < 0) start = i
        if (!on && start >= 0) {
            parts += rangeText(start + 1, i)
            start = -1
        }
    }
    if (start >= 0) parts += rangeText(start + 1, bits.length)
    return parts.joinToString(",")
}

private fun rangeText(from: Int, to: Int): String =
    if (from == to) "$from" else "$from-$to"

/** 位串总周数 */
fun jwWeekCount(bits: String): Int = bits.length

/** 从 "2027-01-20 10:20-12:10(星期三)" 抽时间段 */
fun jwExtractTimeRange(text: String): String {
    val m = Regex("(\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2})").find(text)
    return m?.groupValues?.get(1)?.replace(" ", "") ?: ""
}

/** 从 "2027-01-20 10:20-12:10(星期三)" 抽星期 */
fun jwExtractWeekday(text: String): String {
    val m = Regex("星期[一二三四五六日天]").find(text)
    return m?.value ?: ""
}

/** yyyy-MM-dd -> "01月20日"；解析失败原样返回 */
fun jwFormatMonthDay(iso: String): String {
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return "%02d月%02d日".format(d.monthValue, d.dayOfMonth)
}

/** 表单编码，等价于标准 application/x-www-form-urlencoded */
fun jwEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

fun jwFormBody(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (k, v) -> "${jwEncode(k)}=${jwEncode(v)}" }

/** 从任意 JsonElement 安全取字符串（数字/布尔/null 均转字符串） */
fun jwJsonString(el: kotlinx.serialization.json.JsonElement?): String {
    if (el == null) return ""
    val p = el as? kotlinx.serialization.json.JsonPrimitive ?: return ""
    if (p is kotlinx.serialization.json.JsonNull) return ""
    return p.content
}

/** 从任意 JsonElement 安全取 Double，取不到返回 fallback */
fun jwJsonDouble(
    el: kotlinx.serialization.json.JsonElement?,
    fallback: Double? = null,
): Double? {
    if (el == null) return fallback
    val p = el as? kotlinx.serialization.json.JsonPrimitive ?: return fallback
    if (p is kotlinx.serialization.json.JsonNull) return fallback
    val raw = p.content.trim().removeSuffix("%")
    return raw.replace(",", "").toDoubleOrNull() ?: fallback
}

/** 两位小数展示，整数不补 .00 */
fun jwAmount(value: Double, digits: Int = 1): String {
    if (value.isNaN() || value.isInfinite()) return "-"
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.${digits}f".format(value)
}
