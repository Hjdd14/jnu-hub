package cn.jnu.hub.feature.jw

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 页面通用状态。
 *
 * [SessionRequired] 单独成一态而不是塞进 Error：
 * 它不是「失败」，而是「需要用户点一次同步」，
 * 用 ErrorState 渲染会让用户以为是自己网络坏了。
 */
private const val TAG_VM = "JwSession"

/** 缓存序列化用的 Json 实例（宽松解析，容忍字段增删） */
private val JW_CACHE_JSON = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

sealed interface JwUiState<out T> {
    data object Loading : JwUiState<Nothing>

    /** 需要先建立 jw 域会话（见 JwSession 类注释） */
    data object SessionRequired : JwUiState<Nothing>

    /**
     * 会话其实已经建立，但教务系统拒绝了这次访问（403）。
     *
     * 与 [SessionRequired] 分开：前者要用户去登录，后者要用户去确认账号权限，
     * 文案和主操作完全不同，混在一起会把人引到错误的方向。
     */
    data object PermissionDenied : JwUiState<Nothing>

    data class Error(val message: String) : JwUiState<Nothing>

    data class Content<T>(val value: T) : JwUiState<T>
}

/** 把一次请求异常映射成 UiState，统一处理会话缺失 */
internal fun <T> Throwable.toUiState(): JwUiState<T> = when (this) {
    is JwPermissionDeniedException -> JwUiState.PermissionDenied
    is JwException.SessionExpired -> JwUiState.SessionRequired
    else -> JwUiState.Error(toJwMessage())
}

/**
 * 所有教务页面的基类：负责「先确认会话 → 再拉数据」的编排。
 * 每个业务 ViewModel 只需实现 [fetch]，会话与重试逻辑在这里复用。
 */
abstract class JwBaseViewModel<T> : androidx.lifecycle.ViewModel() {

    private val _state = MutableStateFlow<JwUiState<T>>(JwUiState.Loading)
    val state: StateFlow<JwUiState<T>> = _state.asStateFlow()

    private var job: Job? = null

    /** 子类实现：真正拉数据的地方。抛 JwException.SessionExpired 会被转成引导态 */
    protected abstract suspend fun fetch(): T

    /**
     * 缓存 key；返回 null 表示这个页面不缓存。
     *
     * 子类同时要重写 [encode]/[decode] 才能生效。
     */
    protected open fun cacheKey(): String? = null

    /** 把数据序列化成 JSON 存盘 */
    protected open fun encode(value: T): String? = null

    /** 从 JSON 还原 */
    protected open fun decode(raw: String): T? = null

    /** 缓存时间戳（毫秒），0 表示当前数据不是来自缓存 */
    private val _cacheSavedAt = MutableStateFlow(0L)

    /** 数据落盘时间（0 表示当前数据不是来自缓存） */
    val cacheSavedAt: StateFlow<Long> = _cacheSavedAt.asStateFlow()

    /** 数据是否来自缓存（UI 用来显示「数据更新于 X 分钟前」） */
    val servedFromCache: Boolean get() = _cacheSavedAt.value > 0L

    /**
     * UI 的「重新查询」按钮调用：清缓存 + 强制重抓。
     *
     * 与 [loadCached] 的区别只在于 force = true，语义上单列出来，
     * 让页面里的刷新按钮读起来就是「重新查询」。
     */
    fun refresh() = loadCached(force = true)

    /**
     * 先读缓存，命中就直接渲染（不发请求）；未命中再联网。
     *
     * @param force 用户点「重新查询」时为 true：清缓存并强制重查
     */
    fun loadCached(force: Boolean = false) {
        val key = cacheKey()
        if (key != null) {
            if (force) {
                JwDataCache.clear(key)
            } else {
                val hit = JwDataCache.get(key)
                val restored = hit?.let { runCatching { decode(it.json) }.getOrNull() }
                if (restored != null) {
                    _cacheSavedAt.value = hit.savedAt
                    _state.value = JwUiState.Content(restored)
                    Log.d(TAG_VM, "cache hit " + key)
                    return
                }
            }
        }
        _cacheSavedAt.value = 0L
        load()
    }

    /** 首次进入页面时调用；已在加载或有数据则不重复请求 */
    fun ensureLoaded() {
        if (job?.isActive == true) return
        if (_state.value is JwUiState.Content) return
        loadCached(force = false)
    }

    fun load() {
        job?.cancel()
        _state.value = JwUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val value = fetch()
                saveToCache(value)
                JwUiState.Content(value)
            } catch (t: Throwable) {
                t.toUiState()
            }
        }
    }

    /** 成功拿到数据后写盘；失败不影响主流程 */
    private fun saveToCache(value: T) {
        val key = cacheKey() ?: return
        runCatching {
            val payload = encode(value) ?: return
            JwDataCache.put(key, payload)
            _cacheSavedAt.value = System.currentTimeMillis()
        }.onFailure { Log.w(TAG_VM, "save cache failed " + key, it) }
    }

    /**
     * 用户点「同步教务账号」后先试静默同步：
     * 用 OkHttp 走一次 jw 自己的 CAS 跳转链，成功就直接加载。
     *
     * 注意：没有 jw 域会话时它一定返回 false（OkHttp 侧拿不到 HttpOnly 的 CASTGC），
     * 这不是错误，而是预期路径 —— 调用方应随即打开 WebView 并轮询 cookie。
     *
     * @param onResult true 表示静默成功，UI 不必再弹 WebView
     */
    fun trySilentSync(onResult: (Boolean) -> Unit = {}) {
        job?.cancel()
        _state.value = JwUiState.Loading
        job = viewModelScope.launch {
            val ok = JwSession.tryBootstrapWithHttp()
            if (ok) {
                loadContent()
                onResult(_state.value is JwUiState.Content<*>)
            } else {
                _state.value = JwUiState.SessionRequired
                onResult(false)
            }
        }
    }

    /**
     * 统一收尾：无论成功失败，UI 都必须离开 Loading 态。
     *
     * 曾经出现过的 bug：JwJsFetcher 加载到 CAS 页后静默跳过，抛不出异常也收不到结果，
     * 页面就一直停在「正在获取课表…」。所以这里兜底把异常翻译成
     * [JwUiState.SessionRequired]（会话问题）或 [JwUiState.Error]（其它问题）。
     */
    private suspend fun loadContent() {
        _state.value = JwUiState.Loading
        _state.value = try {
            JwUiState.Content(fetch())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG_VM, "fetch failed: " + t.javaClass.simpleName + " " + t.message)
            t.toUiState()
        }
    }

    /**
     * WebView 弹层打开期间与之后：主动轮询 jw 域 CookieManager，直到出现会话 cookie。
     *
     * 完全不依赖 WebViewClient 回调（快速 302 链上可能一次回调都不给），
     * 判定标准只有「jw 域 cookie 里出现 JSESSIONID」一条，见 [JwSession.isReadyMarker]。
     *
     * 轮询超时后还有一条兜底：WebView 已经换到票（MOD_AMP_AUTH / CASTGC）时，
     * 直接用同步过来的 cookie 打一次接口 —— 接口返回 JSON 也算成功。
     * 这是唯一可靠的判定，因为服务端可能确实不下发 JSESSIONID。
     *
     * @param onDone true = 会话可用且已拿到数据；false = 仍未建立
     */
    fun syncWithWebView(onDone: (Boolean) -> Unit = {}) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            Log.d(TAG_VM, "start polling jw session cookie")
            val gotJSession = JwSession.pollSession { elapsed ->
                if (elapsed > 0 && elapsed % 3000L == 0L) {
                    Log.d(TAG_VM, "waiting jw JSESSIONID " + elapsed + "ms, webview=" + JwSession.webViewAuthMarkers())
                }
            }

            // 超时后：WebView 只要换到了票，就把 cookie 同步过来让接口自己说话
            if (!gotJSession) {
                val hasTicket = JwSession.hasWebViewTicket()
                Log.w(
                    TAG_VM,
                    "no JSESSIONID after " + JwSession.POLL_TOTAL_SECONDS + "s, ticket=" + hasTicket +
                        ", webview=" + JwSession.webViewAuthMarkers(),
                )
                JwSession.syncBusinessCookies()
            }

            val ok = JwSession.probeSession()
            if (!ok) {
                Log.w(TAG_VM, "jw probe refused; jar=" + JwSession.jwCookieNames())
                // 不能只回调 onDone 就结束：页面必须离开 Loading，否则用户看到永久转圈
                _state.value = JwUiState.SessionRequired
                onDone(false)
                return@launch
            }
            Log.i(TAG_VM, "jw session usable (jsessionid=" + gotJSession + "), loading data")
            loadContent()
            val finalState = _state.value
            onDone(finalState is JwUiState.Content<*>)
        }
    }

    /** 供子类切换筛选条件后局部刷新 */
    protected fun publish(value: JwUiState<T>) {
        _state.value = value
    }

    protected fun content(): T? = (_state.value as? JwUiState.Content)?.value
}

// ———————————————————————————— 课表 ————————————————————————————

@kotlinx.serialization.Serializable
data class TimetableContent(
    val semester: JwSemester,
    val courses: List<JwCourse>,
    /** 位串长度，即总周数 */
    val weekCount: Int,
)

class JwTimetableViewModel : JwBaseViewModel<TimetableContent>() {

    private val _week = MutableStateFlow(1)
    val week: StateFlow<Int> = _week.asStateFlow()

    override suspend fun fetch(): TimetableContent {
        val semester = JwApi.fetchCurrentSemester()
        JwPrefs.lastSemester = semester.dm
        val courses = JwApi.fetchTimetable(semester.dm)
        val weeks = courses.maxOfOrNull { it.SKZC.length }?.coerceAtLeast(1) ?: 1
        return TimetableContent(semester = semester, courses = courses, weekCount = weeks)
    }

    fun selectWeek(week: Int) {
        val total = content()?.weekCount ?: return
        _week.value = week.coerceIn(1, total)
    }
}

// ———————————————————————————— 成绩 ————————————————————————————

@kotlinx.serialization.Serializable
data class GradesContent(
    val semesters: List<String>,
    val selected: String,
    val grades: List<JwGrade>,
    /** 全部成绩，按学期分组；切换学期时本地过滤，不再发请求 */
    val allGrades: Map<String, List<JwGrade>> = emptyMap(),
) {
    val stats: JwGradeStats? = jwComputeStats(grades)
}

fun jwComputeStats(grades: List<JwGrade>): JwGradeStats? {
    var credits = 0.0
    var weighted = 0.0
    var count = 0
    grades.forEach { g ->
        val score = g.numericScore ?: return@forEach
        val credit = g.XF.toDoubleOrNull() ?: 0.0
        credits += credit
        weighted += score * credit
        count++
    }
    if (count == 0) return null
    return JwGradeStats(
        credits = credits,
        weightedScore = if (credits > 0) weighted / credits else 0.0,
        count = count,
    )
}

class JwGradesViewModel : JwBaseViewModel<GradesContent>() {

    private var current: String = ""

    override fun cacheKey(): String = JwDataCache.KEY_GRADES

    override fun encode(value: GradesContent): String =
        JW_CACHE_JSON.encodeToString(GradesContent.serializer(), value)

    override fun decode(raw: String): GradesContent? =
        runCatching { JW_CACHE_JSON.decodeFromString(GradesContent.serializer(), raw) }.getOrNull()

    /**
     * 一次拉**全部**成绩，再在本地按学期分组。
     *
     * 之前是「先取学期列表 → 按选中学期查」，但学校数据里"最近两学期"本身没有成绩
     * （官网「最近两学期」页签也是空白），所以查出来永远是空的。
     * 改为查全部（对应官网「全部」页签，实测 12 条），分组完全在客户端完成，
     * 切换学期不再发请求。
     */
    override suspend fun fetch(): GradesContent {
        val all = JwApi.fetchGrades()
        val groups = all.groupBy { it.XNXQDM.ifBlank { "未知学期" } }
        // 学期倒序（新→旧），取第一条作为默认
        val semesters = groups.keys.sortedDescending()
        val selected = semesters.firstOrNull().orEmpty()
        current = selected
        return GradesContent(
            semesters = semesters,
            selected = selected,
            grades = groups[selected].orEmpty(),
            allGrades = groups,
        )
    }

    /** 切换学期：纯本地过滤，不再请求网络 */
    fun selectSemester(semester: String) {
        if (semester == current) return
        current = semester
        val prev = content() ?: return
        publish(JwUiState.Content(prev.copy(selected = semester, grades = prev.allGrades[semester].orEmpty())))
    }
}


// ———————————————————————————— 考试 ————————————————————————————

@kotlinx.serialization.Serializable
data class ExamsContent(
    val semester: JwSemester,
    val exams: List<JwExam>,
)

class JwExamsViewModel : JwBaseViewModel<ExamsContent>() {

    override fun cacheKey(): String = JwDataCache.KEY_EXAMS

    override fun encode(value: ExamsContent): String =
        JW_CACHE_JSON.encodeToString(ExamsContent.serializer(), value)

    override fun decode(raw: String): ExamsContent? =
        runCatching { JW_CACHE_JSON.decodeFromString(ExamsContent.serializer(), raw) }.getOrNull()

    override suspend fun fetch(): ExamsContent {
        val semester = JwApi.fetchCurrentSemester()
        val exams = JwApi.fetchExams(semester.dm)
            .sortedWith(compareByDescending<JwExam> { it.KSRQ }.thenByDescending { it.KSSJMS })
        return ExamsContent(semester, exams)
    }
}

// ———————————————————————————— 学分 ————————————————————————————

class JwCreditsViewModel : JwBaseViewModel<JwCreditSummary>() {

    override fun cacheKey(): String = JwDataCache.KEY_CREDITS

    override fun encode(value: JwCreditSummary): String =
        JW_CACHE_JSON.encodeToString(JwCreditSummary.serializer(), value)

    override fun decode(raw: String): JwCreditSummary? =
        runCatching { JW_CACHE_JSON.decodeFromString(JwCreditSummary.serializer(), raw) }.getOrNull()

    private val _xh = MutableStateFlow(JwPrefs.studentId)
    val xh: StateFlow<String> = _xh.asStateFlow()

    /** 学号解析失败（要求用户手填）时置位，UI 显示输入框而不是错误页 */
    private val _needStudentId = MutableStateFlow(false)
    val needStudentId: StateFlow<Boolean> = _needStudentId.asStateFlow()

    fun submitStudentId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        JwPrefs.studentId = trimmed
        _xh.value = trimmed
        _needStudentId.value = false
        // 换了学号，旧缓存作废
        JwDataCache.clear(JwDataCache.KEY_CREDITS)
        JwDataCache.clear(JwDataCache.KEY_PROFILE)
        load()
    }

    override suspend fun fetch(): JwCreditSummary {
        val id = _xh.value.ifBlank { JwPrefs.studentId }
            .ifBlank {
                // 会话刚建立时社工一次「我是谁」
                JwSession.resolveStudentId()?.also { _xh.value = it }.orEmpty()
            }
        if (id.isBlank()) {
            _needStudentId.value = true
            throw JwException.Empty("学分查询需要学号，请填写后重试")
        }
        val pyfadm = JwApi.fetchPyfadm(id)
        return JwApi.fetchCreditSummary(id, pyfadm)
    }
}

// ———————————————————————————— 个人信息 ————————————————————————————

class JwProfileViewModel : JwBaseViewModel<JwProfile>() {

    override fun cacheKey(): String = JwDataCache.KEY_PROFILE

    override fun encode(value: JwProfile): String =
        JW_CACHE_JSON.encodeToString(JwProfile.serializer(), value)

    override fun decode(raw: String): JwProfile? =
        runCatching { JW_CACHE_JSON.decodeFromString(JwProfile.serializer(), raw) }.getOrNull()

    private val _xh = MutableStateFlow(JwPrefs.studentId)
    val xh: StateFlow<String> = _xh.asStateFlow()

    private val _needStudentId = MutableStateFlow(false)
    val needStudentId: StateFlow<Boolean> = _needStudentId.asStateFlow()

    fun submitStudentId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        JwPrefs.studentId = trimmed
        _xh.value = trimmed
        _needStudentId.value = false
        // 换了学号，旧缓存作废
        JwDataCache.clear(JwDataCache.KEY_CREDITS)
        JwDataCache.clear(JwDataCache.KEY_PROFILE)
        load()
    }

    override suspend fun fetch(): JwProfile {
        val id = _xh.value.ifBlank { JwPrefs.studentId }
            .ifBlank {
                JwSession.resolveStudentId()?.also { _xh.value = it }.orEmpty()
            }
        if (id.isBlank()) {
            _needStudentId.value = true
            throw JwException.Empty("个人信息查询需要学号，请填写后重试")
        }
        return JwApi.fetchProfile(id)
    }
}