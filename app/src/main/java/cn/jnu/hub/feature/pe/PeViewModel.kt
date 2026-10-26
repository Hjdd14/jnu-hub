package cn.jnu.hub.feature.pe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 体测页面通用状态。
 *
 * [LoginRequired] 单独成一态：体测与 CAS 无关，会话过期意味着用户必须回登录页，
 * 而不是弹一个「重试」。用 ErrorState 渲染会让用户反复点重试却永远失败。
 */
sealed interface PeUiState<out T> {
    data object Loading : PeUiState<Nothing>

    /** 需要先登录体测系统（独立账号 + 验证码） */
    data object LoginRequired : PeUiState<Nothing>

    data class Error(val message: String) : PeUiState<Nothing>

    data class Content<T>(val value: T) : PeUiState<T>
}

/** 把一次请求异常映射成 UiState */
private fun <T> Throwable.toPeUiState(): PeUiState<T> =
    if (this is PeException.SessionExpired) PeUiState.LoginRequired
    else PeUiState.Error(toPeMessage())

/**
 * 体测页面的公共基类：负责「会话检查 → 拉数据 → 错误翻译」的编排。
 * 各业务 ViewModel 只实现 [fetch]。
 */
abstract class PeBaseViewModel<T> : ViewModel() {

    private val _state = MutableStateFlow<PeUiState<T>>(PeUiState.Loading)
    val state: StateFlow<PeUiState<T>> = _state.asStateFlow()

    private var job: Job? = null

    /** 子类实现：真正拉数据的地方 */
    protected abstract suspend fun fetch(): T

    /** 首次进入页面时调用；已在加载或已有数据则不重复请求 */
    fun ensureLoaded() {
        if (job?.isActive == true) return
        if (_state.value is PeUiState.Content) return
        load()
    }

    fun load() {
        job?.cancel()
        _state.value = PeUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                PeUiState.Content(fetch())
            } catch (t: Throwable) {
                t.toPeUiState()
            }
        }
    }

    /** 直接替换状态（登录页这类手动控制的场景用） */
    protected fun emit(next: PeUiState<T>) {
        _state.value = next
    }
}

// ———————————————————————————— 成绩 ————————————————————————————

/**
 * 成绩页 ViewModel：当前学年 + 往年任一年份。
 * [selectedYear] 为空表示「当年成绩」（PHTest_StudentScore.aspx）。
 */
class PeResultViewModel : PeBaseViewModel<PeScoreReport>() {

    private val _year = MutableStateFlow(PePrefs.lastYear)
    /** 空串 = 当年 */
    val year: StateFlow<String> = _year.asStateFlow()

    val isCurrentYear: Boolean get() = _year.value.isBlank()

    /**
     * 已发现的年份集合（累积）。
     *
     * 每次查询只返回「当前页面上列出的往年链接」，不同年份页面给的列表长度不同
     * （实测：本学年页给 2025+2024，2025 页只给 2025，2024 页只给 2024）。
     * 若直接用本次结果替换年份按钮列表，就会出现「点 2025 后 2024 按钮消失」。
     * 这里做并集，只增不减。
     */
    private var knownYears: Set<String> = emptySet()

    override suspend fun fetch(): PeScoreReport {
        val report = if (_year.value.isBlank()) {
            PeApi.fetchCurrentScore()
        } else {
            PeApi.fetchHistoryScore(_year.value)
        }
        knownYears = knownYears +
            report.historyYears.map { it.trim() }.filter { it.isNotBlank() } +
            // 当前选中的年份本身也必须在列表里，否则按钮会消失
            listOfNotNull(_year.value.takeIf { it.isNotBlank() })
        return report.copy(historyYears = knownYears.sortedDescending())
    }

    fun selectYear(year: String) {
        val normalized = year.trim()
        if (normalized == _year.value) return
        _year.value = normalized
        if (normalized.isNotBlank()) PePrefs.lastYear = normalized
        load()
    }
}

// ———————————————————————————— 评分标准 ————————————————————————————

class PeStandardViewModel : PeBaseViewModel<PeStandardPage>() {
    override suspend fun fetch(): PeStandardPage = PeApi.fetchStandard()
}

// ———————————————————————————— 主页 ————————————————————————————

class PeHomeViewModel : PeBaseViewModel<PeHome>() {
    override suspend fun fetch(): PeHome = PeApi.fetchHome()

    fun logout() {
        PeApi.logout()
        PePrefs.password = ""
    }
}

// ———————————————————————————— 预约 ————————————————————————————

class PeBookingViewModel : PeBaseViewModel<PeBookingInfo>() {
    override suspend fun fetch(): PeBookingInfo = PeApi.fetchBooking()
}

// ———————————————————————————— 免测 ————————————————————————————

class PeExemptViewModel : PeBaseViewModel<PeExemptInfo>() {
    override suspend fun fetch(): PeExemptInfo = PeApi.fetchExempt()
}

// ———————————————————————————— 运动处方 ————————————————————————————

class PePrescriptionViewModel : PeBaseViewModel<PePrescription>() {
    override suspend fun fetch(): PePrescription = PeApi.fetchPrescription()
}
