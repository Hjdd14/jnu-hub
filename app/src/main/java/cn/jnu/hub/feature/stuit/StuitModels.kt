package cn.jnu.hub.feature.stuit

/**
 * 学工系统（stuit.jnu.edu.cn，D-Campus）数据模型。
 *
 * 学工系统是纯 JSP 服务端渲染，所有数据都藏在 <table> 里，
 * 因此这里的数据模型是「HTML 表格 → 结构化对象」的产物，
 * 而不是 JSON 反序列化的产物（对比 feature/jw 的 JSON 模型）。
 */

/** 带「排名 | 百分比」的分数单元格，如「64.54（31 | 44.29%）」 */
data class RankedValue(
    val value: Double,
    val rank: Int? = null,
    val percentile: Double? = null,
    val raw: String = "",
) {
    val hasRank: Boolean get() = rank != null
}

// ———————————————————————— ① 综合测评 ————————————————————————

/** 综合测评头部：学生身份信息 + 总分 + 班级排名 */
data class StuitEvalHeader(
    val studentId: String = "",
    val name: String = "",
    val gender: String = "",
    val college: String = "",
    val major: String = "",
    val grade: String = "",
    val className: String = "",
    val totalScore: Double? = null,
    val classRank: Int? = null,
    val classPercentile: Double? = null,
)

/** 基础性素养测评项（一般 5 项） */
data class StuitBasicItem(
    val index: String,
    val title: String,
    val range: String = "",
    val studentScore: Double? = null,
    val classScore: Double? = null,
    val collegeScore: Double? = null,
    val total: Double? = null,
)

/** 学业成绩课程行 */
data class StuitEvalCourse(
    val name: String,
    val type: String = "",
    val term: String = "",
    val score: String = "",
    val credit: String = "",
    val passed: String = "",
)

/** 五育单项 */
data class StuitFiveEdu(
    val name: String,
    val score: Double? = null,
    /** 画柱状图时用的参考满量程；0 表示未知 */
    val maxScore: Double = 0.0,
)

/** 综合测评完整数据 */
data class StuitEvaluation(
    val header: StuitEvalHeader = StuitEvalHeader(),
    val templateName: String = "",
    val basicScore: Double? = null,
    val basicItems: List<StuitBasicItem> = emptyList(),
    val academicScore: Double? = null,
    val academicCourses: List<StuitEvalCourse> = emptyList(),
    val comprehensiveScore: Double? = null,
    val fiveEdu: List<StuitFiveEdu> = emptyList(),
) {
    val hasAnyScore: Boolean
        get() = header.totalScore != null || basicScore != null ||
            academicScore != null || comprehensiveScore != null
}

// ———————————————————————— ② 专业排名 ————————————————————————

/** 排名表一行：每列都是「分值 + 排名 + 百分比」 */
data class StuitRankRow(
    val template: String = "",
    val college: String = "",
    val major: String = "",
    val grade: String = "",
    val total: RankedValue? = null,
    val basic: RankedValue? = null,
    val academic: RankedValue? = null,
    val comprehensive: RankedValue? = null,
    val moral: RankedValue? = null,
    val intellectual: RankedValue? = null,
    val physical: RankedValue? = null,
    val aesthetic: RankedValue? = null,
    val labor: RankedValue? = null,
)

/** 排名表中除五个「大类」以外的五育单项 */
data class StuitRankFiveEdu(
    val moral: RankedValue? = null,
    val intellectual: RankedValue? = null,
    val physical: RankedValue? = null,
    val aesthetic: RankedValue? = null,
    val labor: RankedValue? = null,
) {
    fun items(): List<Pair<String, RankedValue>> = buildList {
        moral?.let { add("德育" to it) }
        intellectual?.let { add("智育" to it) }
        physical?.let { add("体育" to it) }
        aesthetic?.let { add("美育" to it) }
        labor?.let { add("劳育" to it) }
    }
}

// ———————————————————————— ③ 成绩管理 ————————————————————————

/** 一条课程成绩 */
data class StuitGrade(
    val studentId: String = "",
    val name: String = "",
    val course: String,
    val courseType: String = "",
    val term: String = "",
    val score: String = "",
    val credit: String = "",
    val gpa: String = "",
    val passed: String = "",
    val retake: String = "",
    val makeup: String = "",
    val postpone: String = "",
    val auditStatus: String = "",
) {
    val scoreValue: Double? get() = score.trim().toDoubleOrNull() ?: firstNumber(score)
    val creditValue: Double? get() = credit.trim().toDoubleOrNull()
    val isPassed: Boolean get() = passed.contains("及格") || passed.contains("通过")
}

/** 某学年的课程成绩集合 */
data class StuitGradeSheet(
    val year: String = "",
    val grades: List<StuitGrade> = emptyList(),
) {
    val courseCount: Int get() = grades.size
    val totalCredit: Double get() = grades.sumOf { it.creditValue ?: 0.0 }

    /** 算术平均分 */
    val averageScore: Double?
        get() {
            val scored = grades.mapNotNull { it.scoreValue }
            if (scored.isEmpty()) return null
            return scored.average()
        }

    /** 学分加权平均分 */
    val weightedScore: Double?
        get() {
            val pairs = grades.mapNotNull { g ->
                val s = g.scoreValue ?: return@mapNotNull null
                val c = g.creditValue ?: return@mapNotNull null
                s to c
            }
            val total = pairs.sumOf { it.second }
            if (total <= 0.0) return null
            return pairs.sumOf { it.first * it.second } / total
        }
}

// ———————————————————————— ④ 学生基本信息 ————————————————————————

/** 基本信息的单个字段（保留原顺序 + 原始标签） */
data class StuitInfoField(
    val label: String,
    val value: String,
)

/** 学生基本信息 */
data class StuitBasicInfo(
    val fields: List<StuitInfoField> = emptyList(),
    val avatarUrl: String? = null,
) {
    private fun find(vararg keys: String): String {
        for (k in keys) {
            val hit = fields.firstOrNull { it.label.contains(k) && it.value.isNotBlank() }
            if (hit != null) return hit.value
        }
        return ""
    }

    val studentId: String get() = find("学号", "账号")
    val name: String get() = find("姓名")
    val gender: String get() = find("性别")
    val college: String get() = find("学院")
    val major: String get() = find("专业")
    val nation: String get() = find("民族")
    val birthday: String get() = find("出生年月", "出生日期")
    val political: String get() = find("政治面貌")
    val campus: String get() = find("校区")
    val graduateDate: String get() = find("毕业时间")

    /** 详情列表里排除已用于头部的字段 */
    fun remainingFields(): List<StuitInfoField> =
        fields.filter { it.value.isNotBlank() && it.label !in TOP_FIELDS }

    private companion object {
        val TOP_FIELDS = setOf("账号", "学号", "姓名", "性别", "学院", "专业")
    }
}

/** 成绩学年下拉选项：2025-2026学年 … 2013-2014学年 */
val STUIT_YEAR_OPTIONS: List<String> =
    (2025 downTo 2013).map { year -> year.toString() + "-" + (year + 1) + "学年" }

/** 从任意文本里抠第一个数字（成绩列可能带「缓考」「免修」之类的文字） */
internal fun firstNumber(text: String): Double? =
    Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull()

/** 隐藏敏感身份证号：保留前 3 位与后 4 位 */
fun maskIdCard(raw: String): String {
    val s = raw.trim()
    if (s.length <= 7) return s
    return s.substring(0, 3) + "*".repeat(s.length - 7) + s.takeLast(4)
}
