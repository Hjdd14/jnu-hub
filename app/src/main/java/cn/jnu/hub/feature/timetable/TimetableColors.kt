package cn.jnu.hub.feature.timetable

import androidx.compose.ui.graphics.Color

/**
 * 课表配色：8 组柔和色，按课程号稳定散列。
 *
 * 每组是 (容器色, 文字色)：容器色偏浅保证深色模式下也不刺眼，
 * 文字色取同色系更深的一档，保证对比度。
 */
object TimetableColors {

    data class Swatch(val container: Color, val content: Color)

    val palette: List<Swatch> = listOf(
        Swatch(Color(0xFFE3F0FB), Color(0xFF11487A)), // 蓝
        Swatch(Color(0xFFE4F3E6), Color(0xFF1F5C2A)), // 绿
        Swatch(Color(0xFFFCE8E6), Color(0xFF8A2C24)), // 红
        Swatch(Color(0xFFF3E8FB), Color(0xFF5B2C7A)), // 紫
        Swatch(Color(0xFFFFF3DC), Color(0xFF7A5410)), // 橙
        Swatch(Color(0xFFE0F4F4), Color(0xFF14595C)), // 青
        Swatch(Color(0xFFFBE7F1), Color(0xFF7A2148)), // 粉
        Swatch(Color(0xFFEDEBFA), Color(0xFF3B3480)), // 靛
    )

    /** 按课程哈希取色；[override] 为用户手动指定的索引 */
    fun forCourse(course: TimetableCourse, override: Int? = null): Swatch {
        val index = override ?: (course.colorSeed.let { if (it < 0) -it else it } % palette.size)
        return palette[index.coerceIn(0, palette.size - 1)]
    }

    /** 用户可选色板的大小 */
    val size: Int get() = palette.size
}
