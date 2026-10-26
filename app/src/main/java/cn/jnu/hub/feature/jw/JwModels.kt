package cn.jnu.hub.feature.jw

import kotlinx.serialization.Serializable

/** 教务系统数据模型（字段名与 CampusSphere 返回的 JSON key 保持一致，便于排查） */

// —— 课表 ——

@Serializable
data class JwSemester(val dm: String, val mc: String)

@Serializable
data class JwCourse(
    val KCM: String,
    val KCH: String,
    val SKJS: String,
    val SKSJ: String,
    val JASMC: String,
    /** 上课周次位串，下标 i 表示第 i+1 周 */
    val SKZC: String,
    /** 星期几 1..7 */
    val SKXQ: Int,
    /** 开始节次 1..12（0 表示解析失败） */
    val KSJC: Int,
    /** 结束节次 1..12 */
    val JSJC: Int,
    val XF: Double,
    val XXXQDM_DISPLAY: String = "",
    val JXBID: String = "",
) {
    /** 该课程在指定周次是否上课 */
    fun activeInWeek(week: Int): Boolean =
        week in 1..SKZC.length && SKZC[week - 1] == '1'

    /** 连续上课的周次区间，如 "1-3,5-18" */
    fun weekRanges(): String = jwCompressWeekRanges(SKZC)

    /** 节次文本，如 "第 6-8 节" / "第 3 节" */
    val periodText: String
        get() = when {
            KSJC <= 0 -> "节次未知"
            JSJC > KSJC -> "第 ${KSJC}-${JSJC} 节"
            else -> "第 ${KSJC} 节"
        }

    val weekdayText: String get() = jwWeekdayName(SKXQ)
}

// —— 成绩 ——

@Serializable
data class JwGrade(
    val XNXQDM: String,
    val KCM: String,
    val KCH: String,
    val displayScore: String,
    val numericScore: Double?,
    val JD: String,
    val XF: String,
    val KCKSDM_DISPLAY: String,
    val KCLBDM_DISPLAY: String,
    val KCCJ_DISPLAY: String,
)

@Serializable
data class JwGradeStats(
    val credits: Double,
    val weightedScore: Double,
    val count: Int,
)

// —— 考试 ——

@Serializable
data class JwExam(
    val KCM: String,
    val KCH: String,
    val KSMC: String,
    val KSRQ: String,
    val KSSJMS: String,
    val ZJJSXM: String,
    val JASMC: String,
    val ZWH: String,
    val XNXQDM_DISPLAY: String,
) {
    /** 从 KSSJMS 里抽出的时间段，如 "10:20-12:10"；抽不到返回空串 */
    val timeRange: String get() = jwExtractTimeRange(KSSJMS)

    /** 从 KSSJMS 里抽出的星期，如 "星期三" */
    val weekdayText: String get() = jwExtractWeekday(KSSJMS)
}

// —— 学分 ——

@Serializable
data class JwCreditSummary(
    val PYFAMC: String,
    val YQXF: Double,
    val WCXF: Double,
    val BXYXXF: Double,
    val ZYJYBXYQXF: Double,
    val JCJYBXYQXF: Double,
    val TSJYBXYQXF: Double,
    /** 各模块已完成学分（接口缺失时为 null，进度条按 0 展示） */
    val ZYJYBXZXF: Double? = null,
    val JCJYBXZXF: Double? = null,
    val TSJYBXZXF: Double? = null,
    val XXYXXF: Double = 0.0,
    val KZYYXXF: Double = 0.0,
) {
    val ratio: Float
        get() = if (YQXF <= 0) 0f else (WCXF / YQXF).toFloat().coerceIn(0f, 1f)
}

// —— 个人信息 ——

@Serializable
data class JwProfile(
    val XH: String,
    val XM: String,
    val CSRQ: String,
    val XBDM_DISPLAY: String,
    val YXDM_DISPLAY: String,
    val ZYMC: String,
    val RXNY: String,
    val MZDM_DISPLAY: String,
    val ZZMMDM_DISPLAY: String,
    val SFZJH: String,
    val KSH: String,
    val NJDM_DISPLAY: String,
    val PYCCDM_DISPLAY: String,
    val XQDM_DISPLAY: String,
) {
    /** 身份证号打码：前 6 后 4 */
    val maskedSfzjh: String
        get() = when {
            SFZJH.length < 10 -> SFZJH
            else -> SFZJH.take(6) + "********" + SFZJH.takeLast(4)
        }
}