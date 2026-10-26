package cn.jnu.hub.feature.pe

/**
 * 体测（师生云 s2.shishengyun.com）数据模型。
 *
 * 该站点是一套独立的 ASP.NET WebForms 系统，与学校 CAS 无关，
 * 需要单独账号（学号 + 默认密码 8 位生日）与验证码登录，
 * 所有页面都是服务端渲染的 .aspx，解析结果统一映射到本文件的结构体。
 */

/** 页面里带出来的学生基本信息（各页面共有的头部区域） */
data class PeStudentInfo(
    val name: String = "",
    val studentNo: String = "",
    /** 形如「男 / 大三 / 24级暨南大学伯明翰大学联合学院」 */
    val className: String = "",
) {
    val gender: String get() = className.substringBefore(" / ").trim()

    val grade: String
        get() {
            val second = className.split("/").getOrNull(1)?.trim().orEmpty()
            return second
        }

    val college: String
        get() = className.split("/").getOrNull(2)?.trim().orEmpty()
}

/** 单项测评结果 */
data class PeScoreItem(
    /** 项目名称，如「50米跑」「身高」 */
    val name: String = "",
    /** 实测成绩文本（已带单位），如 "7.90 秒" */
    val value: String = "",
    /** 得分（百分制） */
    val score: String = "",
    /** 等级：优秀 / 良好 / 及格 / 不及格 / 肥胖 / 超重 … */
    val grade: String = "",
    /** 折算分值（占总评的权重分） */
    val points: String = "",
) {
    val hasData: Boolean get() = value.isNotBlank() || score.isNotBlank()

    /** 按等级取语义色（UI 层负责映射到主题色，这里只给语义） */
    val gradeLevel: PeGradeLevel
        get() = when (grade.trim()) {
            "优秀" -> PeGradeLevel.EXCELLENT
            "良好" -> PeGradeLevel.GOOD
            "及格" -> PeGradeLevel.PASS
            "不及格" -> PeGradeLevel.FAIL
            "" -> PeGradeLevel.NONE
            else -> PeGradeLevel.NORMAL // 肥胖 / 超重 / 低体重 / 正常 等 BMI 描述
        }
}

enum class PeGradeLevel { EXCELLENT, GOOD, PASS, FAIL, NORMAL, NONE }

/** 视力表的四行（左眼 / 右眼） */
data class PeVisionRow(
    val label: String = "",
    val left: String = "",
    val right: String = "",
)

/** 一次体测的成绩单（当年或某个往年） */
data class PeScoreReport(
    val year: String = "",
    val student: PeStudentInfo = PeStudentInfo(),
    /** 站点提示语，例如「暂未开放体测成绩查询！」；正常时为空 */
    val notice: String = "",
    val items: List<PeScoreItem> = emptyList(),
    /** 标准分等级，如「不及格」 */
    val standardGrade: String = "",
    /** 标准分 */
    val standardScore: String = "",
    /** 总分等级 */
    val totalGrade: String = "",
    /** 总分 */
    val totalScore: String = "",
    val vision: List<PeVisionRow> = emptyList(),
    /** 视力：是否为角膜镜佩戴者 */
    val keratoscope: String = "",
    /** 页面上可点开的往年成绩年份（升序无关，按链接顺序） */
    val historyYears: List<String> = emptyList(),
) {
    val hasScores: Boolean get() = items.any { it.hasData } || totalScore.isNotBlank()

    val totalScoreNumber: Int? get() = totalScore.trim().toIntOrNull()
}

/** 评分标准里的一行 */
data class PeStandardRow(
    val project: String = "",
    val value: String = "",
    val score: String = "",
    val grade: String = "",
    val points: String = "",
)

/** 评分标准的单张表（一个测试项目对应一张表，如「肺活量」） */
data class PeStandardTable(
    val title: String = "",
    val rows: List<PeStandardRow> = emptyList(),
)

/** 评分标准页 */
data class PeStandardPage(
    val student: PeStudentInfo = PeStudentInfo(),
    /** 「大三男生的评分标准如下：」 */
    val tips: String = "",
    val tables: List<PeStandardTable> = emptyList(),
    val notice: String = "",
)

/** 主页概要 */
data class PeHome(
    val student: PeStudentInfo = PeStudentInfo(),
    val entries: List<PeEntry> = emptyList(),
)

data class PeEntry(val title: String, val url: String)

/** 免测申请页 */
data class PeExemptInfo(
    val notice: String = "",
    /** 申请须知正文 */
    val noticeText: String = "",
    /** 历史申请记录（每次一行文本） */
    val records: List<String> = emptyList(),
)

/** 预约页 */
data class PeBookingInfo(
    val notice: String = "",
    val tip: String = "",
    /** 项目名 → 预约状态（未 / 已预约 …） */
    val items: List<PeBookingItem> = emptyList(),
    val myBookings: List<String> = emptyList(),
)

data class PeBookingItem(val name: String, val status: String)

/** 运动处方页 */
data class PePrescription(
    val student: PeStudentInfo = PeStudentInfo(),
    val score: String = "",
    val grade: String = "",
    val gender: String = "",
    val gradeName: String = "",
    val age: String = "",
    val major: String = "",
    val items: List<PeScoreItem> = emptyList(),
    val diagnosis: List<PeDiagnosis> = emptyList(),
)

data class PeDiagnosis(val title: String, val text: String)
