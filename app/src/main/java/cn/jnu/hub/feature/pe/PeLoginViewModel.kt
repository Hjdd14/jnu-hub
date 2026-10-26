package cn.jnu.hub.feature.pe

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 体测登录流程状态机。
 *
 * 体测站点的验证码与 ASP.NET 会话绑定：GET 登录页 → 下载验证码图 → POST 表单，
 * 三步必须在同一个 Cookie 会话里完成，所以整个流程放在 ViewModel 里串联，
 * 任何一步失败都要重新 GET 一次拿新验证码（服务端验证码一次性）。
 */
class PeLoginViewModel : ViewModel() {

    sealed interface State {
        /** 正在拉取登录页 */
        data object Preparing : State

        /** 登录页就绪，等待用户输入 */
        data class Ready(
            val captcha: Bitmap?,
            val captchaLoading: Boolean = false,
        ) : State

        data object Submitting : State

        /** 登录成功 */
        data object Success : State

        data class Failed(
            val message: String,
            /** 失败后需要换一张验证码 */
            val refreshCaptcha: Boolean = true,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Preparing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _username = MutableStateFlow(PePrefs.username)
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(if (PePrefs.remember) PePrefs.password else "")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _remember = MutableStateFlow(PePrefs.remember)
    val remember: StateFlow<Boolean> = _remember.asStateFlow()

    private val _captchaInput = MutableStateFlow("")
    val captchaInput: StateFlow<String> = _captchaInput.asStateFlow()

    private var page: PeApi.LoginPage? = null

    fun setUsername(v: String) { _username.value = v }
    fun setPassword(v: String) { _password.value = v }
    fun setCaptcha(v: String) { _captchaInput.value = v.filter { !it.isWhitespace() }.take(8) }

    fun setRemember(v: Boolean) {
        _remember.value = v
        PePrefs.remember = v
    }

    /** 首次进入：若已有会话直接跳过登录页 */
    fun start() {
        if (_state.value !is State.Preparing && _state.value !is State.Failed) {
            if (_state.value !is State.Ready) return
        }
        loadLoginPage()
    }

    fun loadLoginPage() {
        _state.value = State.Preparing
        viewModelScope.launch {
            try {
                val p = PeApi.fetchLoginPage()
                page = p
                _captchaInput.value = ""
                _state.value = State.Ready(captcha = null, captchaLoading = true)
                refreshCaptchaImage()
            } catch (t: Throwable) {
                _state.value = State.Failed(t.toPeMessage(), refreshCaptcha = false)
            }
        }
    }

    /** 换一张验证码（用户点图片 / 登录失败后自动调用） */
    fun refreshCaptcha() {
        val current = _state.value
        if (current is State.Submitting) return
        viewModelScope.launch {
            _captchaInput.value = ""
            val ready = current as? State.Ready
            _state.value = State.Ready(captcha = ready?.captcha, captchaLoading = true)
            refreshCaptchaImage()
        }
    }

    private suspend fun refreshCaptchaImage() {
        val p = page ?: return
        try {
            val bmp = PeApi.fetchCaptcha(p.captchaUrl)
            _state.value = State.Ready(captcha = bmp, captchaLoading = false)
        } catch (t: Throwable) {
            _state.value = State.Ready(captcha = null, captchaLoading = false)
        }
    }

    /** 提交登录 */
    /** 最近一次登录失败的提示。与 [State] 分离，避免刷新验证码时被覆盖。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearLastError() {
        _lastError.value = null
    }

    fun submit() {
        val p = page
        if (p == null) {
            loadLoginPage()
            return
        }
        val user = _username.value.trim()
        val pwd = _password.value
        val code = _captchaInput.value.trim()

        if (user.isBlank()) {
            _state.value = State.Failed("请输入学号", refreshCaptcha = false)
            return
        }
        if (pwd.isBlank()) {
            _state.value = State.Failed("请输入密码（初始密码为 8 位生日）", refreshCaptcha = false)
            return
        }
        if (code.isBlank()) {
            _state.value = State.Failed("请输入验证码", refreshCaptcha = false)
            return
        }

        _lastError.value = null
        _state.value = State.Submitting
        viewModelScope.launch {
            try {
                PeApi.login(p, user, pwd, code)
                PePrefs.username = user
                if (_remember.value) PePrefs.password = pwd else PePrefs.password = ""
                _state.value = State.Success
            } catch (t: Throwable) {
                // 失败后：
                // 1. 先记下错误原因
                // 2. 验证码是一次性的，必须换一张（只刷验证码图片，**不要**整页重载）
                // 之前这里调 loadLoginPage()，它会把状态重置成 Preparing→Ready，
                // 把刚设置的 Failed 覆盖掉 —— 用户只看到验证码刷新，没有任何错误提示。
                // 错误信息单独存，刷新验证码（会重置 State 为 Ready）时不会被冲掉
                _lastError.value = t.toPeMessage()
                // 验证码是一次性的，失败后必须换一张。
                // 注意 refreshCaptchaImage() 会把 State 置为 Ready(captcha=bmp)，
                // 所以错误文案必须走 _lastError，不能塞进 State.Failed。
                refreshCaptchaImage()
            }
        }
    }

    /** 登录页已经准备好时清掉错误提示（用户重新输入） */
    fun clearError() {
        val s = _state.value
        if (s is State.Failed && s.refreshCaptcha) {
            viewModelScope.launch {
                PePrefs.username = _username.value.trim()
                if (_remember.value) PePrefs.password = _password.value
            }
            loadLoginPage()
        }
    }
}
