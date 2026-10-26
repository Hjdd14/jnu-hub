package cn.jnu.hub.feature.timetable

import android.content.Context
import androidx.lifecycle.viewModelScope
import cn.jnu.hub.feature.jw.JwApi
import cn.jnu.hub.feature.jw.JwBaseViewModel
import cn.jnu.hub.feature.jw.JwUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 课表页面状态。
 *
 * 相比其它教务页面，课表多了一层**本地缓存**：
 * 真实数据只能靠 WebView 注入 XHR 旁路抓取（教务对非浏览器请求恒 403），
 * 首次要等 10-25 秒。缓存让第二次进入秒开。
 */
class TimetableViewModel : JwBaseViewModel<TimetableData>() {

    private val _week = MutableStateFlow(1)
    val week: StateFlow<Int> = _week.asStateFlow()

    /** 用户是否手动选过周次；没选过时加载完自动跳到当前周 */
    private var weekTouched = false

    /** 成功渲染的数据是来自缓存（UI 用来显示「数据更新于 X 分钟前」） */
    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    /** 缓存写入时间（ms），0 表示无缓存 */
    private val _cachedAt = MutableStateFlow(0L)
    val cachedAt: StateFlow<Long> = _cachedAt.asStateFlow()

    /** 是否正在后台静默刷新（已有缓存时，UI 显示小进度条而不是整页 Loading） */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var prefs: TimetablePrefs? = null
    private var refreshJob: Job? = null

    /** 页面首次进入时注入 Context（缓存依赖它），然后带缓存加载 */
    fun attach(context: Context) {
        if (prefs == null) {
            prefs = TimetablePrefs.get(context)
        }
        if (content() != null || _refreshing.value) return
        loadWithCache()
    }

    /**
     * stale-while-revalidate：
     * 1. 有缓存 → 立刻用缓存渲染（体验：秒开），同时后台刷新
     * 2. 无缓存 → 整页 Loading，走正常网络请求
     *
     * 后台刷新成功后覆盖缓存；失败则**保留缓存继续展示**，不把用户已有内容清空。
     */
    /**
     * 有缓存就直接用缓存，**不再自动后台刷新**。
     *
     * 曾经的 stale-while-revalidate 每次进入都会触发一次抓取：抓取要开隐藏 WebView
     * 走完整门户流程（5-10 秒），期间抢走触摸焦点，导致课表页按钮点不动。
     * 用户明确要求：首次抓过之后，只有点右上角刷新按钮才重新抓。
     */
    fun loadWithCache() {
        val cached = prefs?.loadTimetable()
        if (cached != null) {
            applyData(cached)
            _fromCache.value = true
            _cachedAt.value = prefs?.timetableSavedAt() ?: 0L
        } else {
            load()
        }
    }

    /** 强制刷新：清缓存 → 整页重抓（用户点刷新按钮时走这条） */
    fun forceRefresh() {
        prefs?.clearTimetable()
        _fromCache.value = false
        _cachedAt.value = 0L
        load()
    }

    /**
     * 后台静默刷新：不切 Loading，失败也不动已有内容。
     * 用于「有缓存但有网」的场景。
     */
    fun refreshInBackground() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                val fresh = fetch()
                applyData(fresh)
                prefs?.saveTimetable(fresh)
                _fromCache.value = false
                _cachedAt.value = System.currentTimeMillis()
            } catch (t: Throwable) {
                // 静默失败：保留缓存内容。下次进入或用户手动刷新再试。
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun applyData(data: TimetableData) {
        publish(JwUiState.Content(data))
        if (!weekTouched) {
            _week.value = TimetableParser.currentWeek(data.semesterCode, data.weekCount)
        }
    }

    /**
     * 每次加载（含基类的 load / trySilentSync / WebView 回调）都会经过这里，
     * 所以在 fetch 内部写缓存，不用去 hook 基类的状态流转。
     */
    override suspend fun fetch(): TimetableData {
        val semester = JwApi.fetchCurrentSemester()
        val courses = JwApi.fetchTimetable(semester.dm)
        val data = TimetableParser.toTimetableData(
            semesterCode = semester.dm,
            semesterName = semester.mc,
            courses = courses,
        )
        // 落缓存：下次进入秒开
        prefs?.saveTimetable(data)
        _cachedAt.value = System.currentTimeMillis()
        _fromCache.value = false
        return data
    }

    fun selectWeek(week: Int) {
        val total = content()?.weekCount ?: return
        weekTouched = true
        _week.value = week.coerceIn(1, total)
    }

    fun nextWeek() = selectWeek(_week.value + 1)

    fun prevWeek() = selectWeek(_week.value - 1)

    /** 回到当前周 */
    fun jumpToCurrentWeek() {
        val data = content() ?: return
        weekTouched = false
        _week.value = TimetableParser.currentWeek(data.semesterCode, data.weekCount)
    }

    fun dataOrNull(): TimetableData? = content()
}
