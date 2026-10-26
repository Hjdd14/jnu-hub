package cn.jnu.hub.feature.timetable

import cn.jnu.hub.feature.jw.JwCourse
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 课表解析：把教务返回的原始字段转成可直接渲染的模型。
 *
 * 全部是纯函数（不依赖 Android），方便单测与排查。
 */
object TimetableParser {

    /** 默认总周数 */
    const val DEFAULT_WEEK_COUNT = 20

    /** 节次上限 */
    const val MAX_PERIOD = 12

    // ────────────────── SKZC 位串 ──────────────────

    /**
     * 解析周次位串。
     *
     * "111011111111111111" -> {1,2,3,5,6,...,18}
     * 下标 i 为 1 表示第 (i+1) 周上课。
     * 兼容带分隔符的写法（个别接口会返回 "1,1,1,0..."）。
     */
    fun parseWeeks(bits: String?): Set<Int> {
        if (bits.isNullOrBlank()) return emptySet()
        val clean = bits.filter { it == '0' || it == '1' }
        if (clean.isEmpty()) return emptySet()
        val out = mutableSetOf<Int>()
        clean.forEachIndexed { index, ch ->
            if (ch == '1') out += index + 1
        }
        return out
    }

    /** 位串长度 = 总周数；取不到时回退默认值 */
    fun weekCountOf(bits: String?): Int {
        val n = bits?.count { it == '0' || it == '1' } ?: 0
        return if (n > 0) n else DEFAULT_WEEK_COUNT
    }

    /**
     * 集合压缩成区间文本。
     * {1,2,3,5} -> "1-3,5"
     */
    fun compressWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val w = sorted[i]
            if (w == prev + 1) {
                prev = w
            } else {
                parts += if (start == prev) start.toString() else start.toString() + "-" + prev
                start = w
                prev = w
            }
        }
        parts += if (start == prev) start.toString() else start.toString() + "-" + prev
        return parts.joinToString(",")
    }

    // ────────────────── YPSJDD 调课解析 ──────────────────

    /**
     * 解析 YPSJDD（完整地点/时间文本），可能有多个时间段用逗号分隔：
     *
     *   "1-3周,5-18周 星期一 第6节-第8节 217室, 14周 星期六 第6节-第8节 217室"
     *
     * 注意逗号既用于分隔段落，也用于分隔周次区间（"1-3周,5-18周"），
     * 所以不能简单按逗号切，必须按「时间段模式」整体正则匹配。
     */
    fun parseSlots(
        ypsjdd: String?,
        fallbackWeekday: Int,
        fallbackStart: Int,
        fallbackEnd: Int,
        fallbackWeeks: Set<Int>,
        fallbackRoom: String,
    ): List<CourseSlot> {
        val text = ypsjdd?.trim().orEmpty()
        if (text.isBlank()) {
            return fallbackSlot(fallbackWeekday, fallbackStart, fallbackEnd, fallbackWeeks, fallbackRoom)
        }

        val parsed = SEGMENT_REGEX.findAll(text).mapNotNull { m ->
            val weekPart = m.groupValues[1]
            val weekday = weekdayFromText(m.groupValues[2])
            val start = m.groupValues[3].toIntOrNull() ?: 0
            val endRaw = m.groupValues[4].toIntOrNull() ?: start
            val room = m.groupValues[5].trim().trim(',')
            if (weekday == 0 || start <= 0) return@mapNotNull null
            CourseSlot(
                weekday = weekday,
                startPeriod = start,
                endPeriod = maxOf(start, endRaw),
                weeks = parseWeekText(weekPart),
                room = room,
            )
        }.toList()

        if (parsed.isEmpty()) {
            return fallbackSlot(fallbackWeekday, fallbackStart, fallbackEnd, fallbackWeeks, fallbackRoom)
        }

        // 第一段是正课，其余标记为调课
        return parsed.mapIndexed { index, slot ->
            if (index == 0) slot.copy(room = slot.room.ifBlank { fallbackRoom })
            else slot.copy(isAdjusted = true)
        }
    }

    private fun fallbackSlot(
        weekday: Int,
        start: Int,
        end: Int,
        weeks: Set<Int>,
        room: String,
    ): List<CourseSlot> {
        if (weekday == 0 || start <= 0) return emptyList()
        return listOf(
            CourseSlot(
                weekday = weekday,
                startPeriod = start,
                endPeriod = if (end >= start) end else start,
                weeks = weeks,
                room = room,
            ),
        )
    }

    /**
     * 周次片段：一个或多个「数字[区间]周」，之间允许逗号连接。
     * 必须这样显式写，不能用 [\d,\-]+ 之类的宽松字符类 ——
     * 惰性量词会在第一个逗号处截断，导致 "1-3周,5-18周" 只匹配到 ",5-18周"，
     * 第 1-3 周被静默丢失（已用真实样本验证过这个坑）。
     */
    private const val WEEKS_PART =
        "(?:\\d+(?:\\s*[-~至]\\s*\\d+)?\\s*周\\s*,?\\s*)+"

    /** 一段 = 周次 + 星期 + 节次 + 地点 */  
    private val SEGMENT_REGEX = Regex(
        "(" + WEEKS_PART + ")" +
            "\\s*(星期[一二三四五六日天])" +
            "\\s*(?:第?\\s*(\\d{1,2})\\s*节)?" +
            "\\s*(?:[-~至]\\s*(?:第)?\\s*(\\d{1,2})\\s*节)?" +
            "\\s*([^,]*?)" +
            "(?=\\s*,\\s*(?:" + WEEKS_PART + "|星期)|$)",
    )

    /** "1-3周,5-18周" / "第1-16周" -> Set<Int> */
    fun parseWeekText(text: String?): Set<Int> {
        val t = text?.replace(" ", "")?.removePrefix("第").orEmpty()
        if (t.isBlank()) return emptySet()
        val out = mutableSetOf<Int>()
        Regex("(\\d+)(?:\\s*[-~至]\\s*(\\d+))?").findAll(t).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull()
            if (b != null && b >= a) {
                for (w in a..b.coerceAtMost(DEFAULT_WEEK_COUNT * 2)) out += w
            } else if (a in 1..(DEFAULT_WEEK_COUNT * 2)) {
                out += a
            }
        }
        return out
    }

    /** 星期文本 -> 1..7 */
    fun weekdayFromText(text: String?): Int {
        if (text == null) return 0
        return when {
            text.contains("一") -> 1
            text.contains("二") -> 2
            text.contains("三") -> 3
            text.contains("四") -> 4
            text.contains("五") -> 5
            text.contains("六") -> 6
            text.contains("日") || text.contains("天") -> 7
            else -> 0
        }
    }

    // ────────────────── 组装 ──────────────────

    /** 把一行 JwCourse 转成 TimetableCourse */
    fun toTimetableCourse(course: JwCourse): TimetableCourse {
        val weeks = parseWeeks(course.SKZC)
        val slots = parseSlots(
            ypsjdd = course.SKSJ,
            fallbackWeekday = course.SKXQ,
            fallbackStart = course.KSJC,
            fallbackEnd = course.JSJC,
            fallbackWeeks = weeks,
            fallbackRoom = course.JASMC,
        )
        // 同一课程可能有重复段落，按「星期+节次+周次」去重
        val distinct = slots.distinctBy { s -> s.weekday * 10000 + s.startPeriod * 100 + s.endPeriod to s.weeks }

        return TimetableCourse(
            name = course.KCM,
            code = course.KCH,
            teacher = course.SKJS,
            timeText = course.SKSJ,
            slots = distinct,
            credits = course.XF,
            campus = course.XXXQDM_DISPLAY,
            rawWeeks = course.SKZC,
        )
    }

    /** 批量转换并计算总周数 */
    fun toTimetableData(
        semesterCode: String,
        semesterName: String,
        courses: List<JwCourse>,
    ): TimetableData {
        val converted = courses.map(::toTimetableCourse)
        val maxWeek = courses.maxOfOrNull { weekCountOf(it.SKZC) } ?: DEFAULT_WEEK_COUNT
        val fromSlots = converted.flatMap { it.allWeeks }.maxOrNull() ?: 0
        return TimetableData(
            semesterCode = semesterCode,
            semesterName = semesterName,
            courses = converted,
            weekCount = maxOf(maxWeek, fromSlots, 1),
        )
    }

    // ────────────────── 当前周推算 ──────────────────

    /**
     * 从学期代码推算开学日期，再算当前是第几周。
     *
     * 学期代码形如 "2026-2027-1"：
     *   第 1 学期 -> 9 月 1 日前后开学
     *   第 2 学期 -> 次年 2 月下旬开学
     * 拿不到或超出范围时返回 1。
     */
    fun currentWeek(semesterCode: String, weekCount: Int, today: LocalDate = LocalDate.now()): Int {
        val start = semesterStart(semesterCode) ?: return 1
        val days = ChronoUnit.DAYS.between(start, today)
        if (days < 0) return 1
        val week = (days / 7).toInt() + 1
        return week.coerceIn(1, weekCount.coerceAtLeast(1))
    }

    /** 推算学期第一天；返回 null 表示无法推算 */
    private fun semesterStart(semesterCode: String): LocalDate? {
        val m = Regex("(\\d{4})\\s*-\\s*(\\d{4})\\s*-\\s*([12])").find(semesterCode) ?: return null
        val startYear = m.groupValues[1].toIntOrNull() ?: return null
        val term = m.groupValues[3].toIntOrNull() ?: return null
        return if (term == 1) LocalDate.of(startYear, 9, 1) else LocalDate.of(startYear + 1, 2, 20)
    }
}
