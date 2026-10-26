package cn.jnu.hub.feature.timetable

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 带时间戳与版本号的课表快照 */
@Serializable
private data class CachedTimetable(
    val version: Int,
    /** 写入时间（ms） */
    val savedAt: Long,
    val data: TimetableData,
)

/** 用户对某门课的个性化设置 */
@Serializable
data class CourseCustomization(
    /** 自定义颜色索引 0..7；null 表示按哈希自动分配 */
    val colorIndex: Int? = null,
    /** 备注 */
    val note: String = "",
    /** 是否收藏 */
    val starred: Boolean = false,
)

/**
 * 课表本地设置：颜色 / 备注 / 收藏。
 *
 * 以课程号 KCH 为键（同名不同课不串色）。没有课程号时用课名兜底。
 * 数据量很小，整体存成一个 JSON 字符串即可。
 */
class TimetablePrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** Map<String, CourseCustomization> 的序列化器（泛型推断不出，需显式给） */
    private val serializer = MapSerializer(String.serializer(), CourseCustomization.serializer())

    /** 内存缓存，避免每次重组都解析 JSON */
    private var cache: Map<String, CourseCustomization>? = null

    fun keyOf(course: TimetableCourse): String =
        course.code.ifBlank { course.name }

    fun all(): Map<String, CourseCustomization> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_DATA, null)
        val parsed = if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching {
                json.decodeFromString(serializer, raw)
            }.getOrDefault(emptyMap())
        }
        cache = parsed
        return parsed
    }

    fun of(course: TimetableCourse): CourseCustomization =
        all()[keyOf(course)] ?: CourseCustomization()

    fun update(key: String, block: (CourseCustomization) -> CourseCustomization) {
        val current = all().toMutableMap()
        current[key] = block(current[key] ?: CourseCustomization())
        persist(current)
    }

    fun toggleStar(course: TimetableCourse) {
        update(keyOf(course)) { it.copy(starred = !it.starred) }
    }

    fun setNote(course: TimetableCourse, note: String) {
        update(keyOf(course)) { it.copy(note = note) }
    }

    fun setColor(course: TimetableCourse, index: Int?) {
        update(keyOf(course)) { it.copy(colorIndex = index) }
    }

    fun starredCourses(courses: List<TimetableCourse>): List<TimetableCourse> {
        val map = all()
        return courses.filter { map[keyOf(it)]?.starred == true }
    }

    fun clearAll() {
        prefs.edit().remove(KEY_DATA).apply()
        cache = null
    }

    // ───────────────────────── 课表数据缓存 ─────────────────────────
    //
    // 为什么必须缓存：教务对非浏览器请求恒 403，真实数据只能靠 WebView 注入
    // XHR 旁路抓取，首次进入要等 10-20 秒。没有缓存的话用户每次切回课程 Tab
    // 都要等这十几秒，体验不可接受。
    //
    // 缓存的是「解析后的模型 JSON」而不是原始接口 JSON：
    // 原始 JSON 还要再走一遍 parser，且字段结构随接口变化易崩；
    // 存模型则读出即可渲染，且与 parser 版本天然对齐（存 version 便于失效）。

    /**
     * 保存课表快照。
     *
     * @param data 已解析的课表
     * @return 是否保存成功
     */
    fun saveTimetable(data: TimetableData): Boolean = runCatching {
        val snapshot = CachedTimetable(
            version = CACHE_VERSION,
            savedAt = System.currentTimeMillis(),
            data = data,
        )
        prefs.edit()
            .putString(KEY_TIMETABLE, json.encodeToString(CachedTimetable.serializer(), snapshot))
            .apply()
        true
    }.getOrDefault(false)

    /**
     * 读缓存。返回 null 表示没有缓存 / 版本过期 / 内容损坏。
     * 调用方应把 null 当作「必须联网」处理。
     */
    fun loadTimetable(): TimetableData? {
        val raw = prefs.getString(KEY_TIMETABLE, null) ?: return null
        if (raw.isBlank()) return null
        return runCatching {
            val snapshot = json.decodeFromString(CachedTimetable.serializer(), raw)
            // 版本不匹配说明模型结构变了，直接丢弃，避免渲染出半残数据
            if (snapshot.version != CACHE_VERSION) return@runCatching null
            snapshot.data.takeIf { it.courses.isNotEmpty() }
        }.getOrNull()
    }

    /** 缓存写入时间（ms）；0 表示没有缓存 */
    fun timetableSavedAt(): Long {
        val raw = prefs.getString(KEY_TIMETABLE, null) ?: return 0L
        return runCatching {
            json.decodeFromString(CachedTimetable.serializer(), raw).savedAt
        }.getOrDefault(0L)
    }

    fun clearTimetable() {
        prefs.edit().remove(KEY_TIMETABLE).apply()
    }

    private fun persist(data: Map<String, CourseCustomization>) {
        cache = data
        runCatching {
            prefs.edit().putString(KEY_DATA, json.encodeToString(serializer, data)).apply()
        }
    }

    companion object {
        private const val PREF_NAME = "jnu_timetable_prefs"
        private const val KEY_DATA = "customizations"
        private const val KEY_TIMETABLE = "timetable_cache"

        /** 模型结构版本：TimetableData 字段变动时 +1，旧缓存自动失效 */
        private const val CACHE_VERSION = 1

        @Volatile
        private var instance: TimetablePrefs? = null

        fun get(context: Context): TimetablePrefs =
            instance ?: synchronized(this) {
                instance ?: TimetablePrefs(context).also { instance = it }
            }
    }
}
