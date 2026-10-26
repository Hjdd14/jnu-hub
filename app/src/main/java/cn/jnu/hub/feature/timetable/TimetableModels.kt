package cn.jnu.hub.feature.timetable

import kotlinx.serialization.Serializable

/**
 * 周课表的领域模型。
 *
 * 数据源是教务 xskcb.do 的 rows；这里把原始字段整理成「一次上课占用一个格子」的模型，
 * 便于网格布局与列表分组同时使用。
 */

/** 一节课在网格里的位置：星期 + 起止节次 + 周次集合 */
@Serializable
data class CourseSlot(
    /** 星期，1=周一 … 7=周日 */
    val weekday: Int,
    /** 开始节次，1..12 */
    val startPeriod: Int,
    /** 结束节次，>= startPeriod */
    val endPeriod: Int,
    /** 上课周次集合，如 {1,2,3,5,...} */
    val weeks: Set<Int>,
    /** 教室；可能为空 */
    val room: String = "",
    /** 该段是否来自「调课信息」（YPSJDD 的第二段及以后） */
    val isAdjusted: Boolean = false,
) {
    /** 占用的节数（渲染时用于跨行合并） */
    val span: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)

    /** 是否在指定周上课 */
    fun activeIn(week: Int): Boolean = week in weeks
}

/** 一门课（一行 xskcb 记录，可能含多个时间段） */
@Serializable
data class TimetableCourse(
    val name: String,
    val code: String,
    val teacher: String,
    /** 原始时间文本 SKSJ，用于详情展示 */
    val timeText: String,
    /** 全部上课时间段；通常 1 个，调课会有多个 */
    val slots: List<CourseSlot>,
    val credits: Double,
    val campus: String = "",
    val className: String = "",
    val rawWeeks: String = "",
) {
    /** 主时间段（第一个），用于列表与详情摘要 */
    val primary: CourseSlot? get() = slots.firstOrNull()

    /** 全部周次的并集 */
    val allWeeks: Set<Int> get() = slots.flatMapTo(mutableSetOf()) { it.weeks }

    /** 人类可读周次，如 "1-3,5-18" */
    val weekText: String get() = TimetableParser.compressWeeks(allWeeks)

    /** 稳定配色索引：按课程号（缺失时按课名）散列 */
    val colorSeed: Int get() = (if (code.isNotBlank()) code else name).hashCode()

    fun activeIn(week: Int): Boolean = slots.any { it.activeIn(week) }
}

/** 一个学期的课表数据 */
@Serializable
data class TimetableData(
    val semesterCode: String,
    val semesterName: String,
    val courses: List<TimetableCourse>,
    /** 总周数，默认 20 */
    val weekCount: Int,
)
