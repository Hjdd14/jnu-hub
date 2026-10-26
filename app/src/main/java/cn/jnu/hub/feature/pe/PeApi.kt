package cn.jnu.hub.feature.pe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cn.jnu.hub.core.network.HttpClient
import cn.jnu.hub.core.network.JnuHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.net.URLEncoder

/**
 * 体测系统（师生云 s2.shishengyun.com）HTTP 客户端。
 *
 * ## 与其它模块的差异
 *
 * 1. 站点是独立的 ASP.NET WebForms 系统，与学校 CAS **完全无关**，
 *    必须用体测自己的账号（学号 + 默认 8 位生日）登录，且要过验证码。
 * 2. 无 JSON API，所有数据都在服务端渲染的 HTML 里，解析交给 [PeParser]。
 * 3. 登录成功后会话由共享 CookieJar（[HttpClient.cookieJar]）持有，
 *    后续 .aspx 直接带 cookie 就能打开，不需要回填 __VIEWSTATE（GET 即可）。
 *
 * 注意：必须走 **http**（站点在 80 端口），走 https 会被重定向或证书失败。
 */
object PeApi {

    private val BASE: String = JnuHosts.PHYSICAL

    private const val LOGIN_PATH = "/Src/PlatForm/User/Login.aspx"
    private const val LOGIN_QUERY = "?Mode=2"

    const val HOME_PATH = "/Src/PlatForm/Personal/index_m.aspx"
    const val SCORE_PATH = "/Src/PlatForm/Personal/PHTest_StudentScore.aspx"
    const val SCORE_HISTORY_PATH = "/Src/PlatForm/Personal/PHTest_StudentScore_H.aspx"
    const val STANDARD_PATH = "/Src/PlatForm/Personal/PHTest_Standard.aspx"
    const val BOOKING_PATH = "/Src/PlatForm/Personal/PHTest_Booking.aspx"
    const val EXEMPT_PATH = "/Src/PlatForm/Personal/PHTest_ExemptApply.aspx"
    const val PRESCRIPTION_PATH = "/Src/PlatForm/Personal/PHTest_ExercisePrescription.aspx"

    private const val ANY_TYPE = "*" + "/" + "*"
    private const val ACCEPT_HTML =
        "text/html,application/xhtml+xml,application/xml;q=0.9," + ANY_TYPE + ";q=0.8"
    private const val ACCEPT_ANY = ANY_TYPE

    /** 登录页里固定下发的隐藏域（实测值，见 docs/02_接口台账.md E.1） */
    private const val HID_SCHOOL_NO = "13071"
    private const val HID_ROLE = "2"
    private const val HID_CODE = "D4D4130C7FF0A057C8654DD1A357947E"
    private const val HID_CATEGORY = "13"
    private const val HID_EDU_YEARS = "4"
    private const val HID_ADVANCE_TEST = "0"
    private const val HID_STUDENT_PWD_MODE = "1"

    /**
     * 加密登录密码。
     *
     * 师生云前端在提交前会调 userlogin.js 的 encryptAES()：
     *   key = SHA256(hidSchoolNo) 的十六进制串取前 32 字符（= 16 字节，AES-128）
     *   iv  = UTF8(hidCode)，CryptoJS 的 CBC 只取前 16 字节
     *   AES/CBC/PKCS7，结果 Base64
     *
     * 不加密直接发明文，服务端会抛
     * 「AESHelper.DecryptAES ... 解密失败：输入数据不是完整的块」。
     */
    private fun encryptPassword(plain: String): String {
        val keyHex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(HID_SCHOOL_NO.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 32)
        val keyBytes = ByteArray(16) { i ->
            keyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val ivBytes = HID_CODE.toByteArray(Charsets.UTF_8).copyOfRange(0, 16)

        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.IvParameterSpec(ivBytes),
        )
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }
    private const val HID_IS_BRIDGE = "0"
    private const val HID_MODEL_WEIXIN = "1"

    /** 表单提交头：WebForms 要求 Referer/Origin 才能过 ASP.NET 的跨域校验 */
    private val loginUrl: String get() = BASE + LOGIN_PATH + LOGIN_QUERY

    // ———————————————————————————— 登录 ————————————————————————————

    /** 登录页上拿来登录用的隐藏域 + 验证码图片地址 */
    data class LoginPage(
        val viewState: String,
        val viewStateGenerator: String,
        val captchaUrl: String,
        /** 当前学年 / 学期 / 学历年限等，页面若下发则优先使用页面值 */
        val currentYear: String = "",
        val currentTerm: String = "",
    )

    /**
     * 第一步：GET 登录页，抽取 __VIEWSTATE / __VIEWSTATEGENERATOR / 验证码图片地址。
     * 同时会把 ASP.NET 会话 cookie 写进共享 CookieJar，验证码与提交必须是同一个会话。
     */
    suspend fun fetchLoginPage(): LoginPage = withContext(Dispatchers.IO) {
        // 登录页本身当然「长得像登录页」，必须豁免该项检查
        val html = getText(loginUrl, referer = "$BASE/", allowLoginPage = true)
        val (vs, vsg) = PeParser.parseViewState(html)
        val captcha = PeParser.parseCaptchaUrl(html)
            ?: throw PeException.Parse("没有在登录页找到验证码图片")
        LoginPage(
            viewState = vs,
            viewStateGenerator = vsg,
            captchaUrl = absoluteUrl(captcha),
            currentYear = PeParser.parseHiddenValue(html, "hidCurrentSchoolYear"),
            currentTerm = PeParser.parseHiddenValue(html, "hidCurrentSchoolTerm"),
        )
    }

    /**
     * 第二步：下载验证码图片。
     * 必须复用同一个 CookieJar（OkHttp 默认已共享），否则服务端不认这张图。
     */
    suspend fun fetchCaptcha(url: String): Bitmap = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "image/avif,image/webp,image/apng,image/*," + ANY_TYPE + ";q=0.8")
            .header("Referer", loginUrl)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val bytes = try {
            HttpClient.get().newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw PeException.Http(resp.code)
                resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: PeException) {
            throw e
        } catch (t: Throwable) {
            throw PeException.Network(t.message ?: "验证码下载失败")
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw PeException.Parse("验证码不是有效的图片")
    }

    /**
     * 第三步：POST 登录。
     *
     * @return 登录是否成功。失败原因写在 [PeException.Login] 里。
     */
    suspend fun login(
        page: LoginPage,
        username: String,
        password: String,
        vercode: String,
    ): Unit = withContext(Dispatchers.IO) {
        val year = page.currentYear.ifBlank { currentSchoolYear().toString() }
        val term = page.currentTerm.ifBlank { currentSchoolTerm().toString() }

        val body = FormBody.Builder()
            .add("__VIEWSTATE", page.viewState)
            .add("__VIEWSTATEGENERATOR", page.viewStateGenerator)
            .add("username", username.trim())
            .add("password", encryptPassword(password))
            .add("vercode", vercode.trim())
            .add("hidSchoolNo", HID_SCHOOL_NO)
            .add("hidRole", HID_ROLE)
            .add("hidCode", HID_CODE)
            .add("hidMac", "")
            .add("hidCategory", HID_CATEGORY)
            .add("hidEduYears", HID_EDU_YEARS)
            .add("hidCurrentSchoolYear", year)
            .add("hidCurrentSchoolTerm", term)
            .add("hidAdvanceTest", HID_ADVANCE_TEST)
            .add("hidStudentLoginPwdMode", HID_STUDENT_PWD_MODE)
            .add("hidStudentLoginPwdText", "")
            .add("hidIsBridge", HID_IS_BRIDGE)
            .add("hidModelWeixin", HID_MODEL_WEIXIN)
            .add("hidReferUrl", "")
            .add("BtnSubmit", "登 入")
            .build()

        val req = Request.Builder()
            .url(loginUrl)
            .post(body)
            .header("Accept", ACCEPT_HTML)
            .header("Referer", loginUrl)
            .header("Origin", BASE)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val html = try {
            HttpClient.get().newCall(req).execute().use { resp ->
                if (resp.code !in 200..299 && resp.code != 302) {
                    throw PeException.Http(resp.code)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: PeException) {
            throw e
        } catch (t: Throwable) {
            throw PeException.Network(t.message ?: "登录请求失败")
        }

        val failure = PeParser.parseLoginFailure(html)
        if (failure != null) throw PeException.Login(failure)

        // 校验：登录成功一定会落 cookie；再拿主页确认一次
        val loggedIn = html.contains("index_m.aspx") ||
            html.contains("PHTest_StudentScore.aspx") ||
            runCatching { isLoggedIn() }.getOrDefault(false)
        if (!loggedIn && PeParser.looksLikeLoginPage(html)) {
            throw PeException.Login("账号或密码错误，或验证码不正确")
        }
    }

    /** 是否已登录（请求主页，被弹回登录页即未登录） */
    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(BASE + HOME_PATH)
                .header("Accept", ACCEPT_HTML)
                .get()
                .build()
            HttpClient.get().newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val text = resp.body?.string().orEmpty()
                if (PeParser.looksLikeLoginPage(finalUrl) || PeParser.looksLikeLoginPage(text)) {
                    false
                } else {
                    text.contains("lblStudentName") || text.contains("PHTest_")
                }
            }
        }.getOrDefault(false)
    }

    /** 清掉体测域的 cookie（退出登录）。只清体测域名，不影响统一认证会话。 */
    fun logout() {
        runCatching { HttpClient.cookieJar().removeDomain("s2.shishengyun.com") }
    }

    // ———————————————————————————— 各业务页 ————————————————————————————

    suspend fun fetchHome(): PeHome = withContext(Dispatchers.IO) {
        PeParser.parseHome(getText(BASE + HOME_PATH))
    }

    /** 当年成绩 */
    suspend fun fetchCurrentScore(): PeScoreReport = withContext(Dispatchers.IO) {
        PeParser.parseScore(getText(BASE + SCORE_PATH), year = "")
    }

    /** 往年成绩 */
    suspend fun fetchHistoryScore(year: String): PeScoreReport = withContext(Dispatchers.IO) {
        PeParser.parseScore(getText(BASE + SCORE_HISTORY_PATH + "?DataYear=" + enc(year)), year)
    }

    suspend fun fetchStandard(): PeStandardPage = withContext(Dispatchers.IO) {
        PeParser.parseStandard(getText(BASE + STANDARD_PATH))
    }

    suspend fun fetchBooking(): PeBookingInfo = withContext(Dispatchers.IO) {
        PeParser.parseBooking(getText(BASE + BOOKING_PATH))
    }

    suspend fun fetchExempt(): PeExemptInfo = withContext(Dispatchers.IO) {
        PeParser.parseExempt(getText(BASE + EXEMPT_PATH))
    }

    suspend fun fetchPrescription(): PePrescription = withContext(Dispatchers.IO) {
        PeParser.parsePrescription(getText(BASE + PRESCRIPTION_PATH))
    }

    // ———————————————————————————— 底层 ————————————————————————————

    /**
     * 抓取页面 HTML。
     *
     * @param allowLoginPage 是否允许返回登录页本身。
     *   请求登录页时**必须**为 true —— 否则 looksLikeLoginPage 会把登录页判成
     *   「会话已过期」并抛 SessionExpired，用户看到「体测登录已过期，请重新登录」，
     *   重试也永远失败。
     */
    private fun getText(
        url: String,
        referer: String = BASE + "/",
        allowLoginPage: Boolean = false,
    ): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_HTML)
            .header("Referer", referer)
            .get()
            .build()
        val (finalUrl, body) = try {
            HttpClient.get().newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) throw PeException.Http(resp.code)
                resp.request.url.toString() to resp.body?.string().orEmpty()
            }
        } catch (e: PeException) {
            throw e
        } catch (t: Throwable) {
            throw PeException.Network(t.message ?: "网络连接失败")
        }
        if (!allowLoginPage &&
            (PeParser.looksLikeLoginPage(finalUrl) || PeParser.looksLikeLoginPage(body))
        ) {
            throw PeException.SessionExpired
        }
        if (body.isBlank()) throw PeException.Parse("服务器返回了空内容")
        return body
    }

    private fun absoluteUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "http:" + raw
        raw.startsWith("/") -> BASE + raw
        else -> BASE + "/Src/PlatForm/User/" + raw
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 当前学年，如 2026（9 月起算新学年，与服务端 hidCurrentSchoolYear 语义一致） */
    fun currentSchoolYear(): Int {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return if (month >= 9) year else year - 1
    }

    /** 当前学期：1 = 秋季学期，2 = 春季学期 */
    fun currentSchoolTerm(): Int {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return if (month >= 9 || month <= 1) 1 else 2
    }

    /** 日志用的表单摘要（排障用，不含明文密码） */
    internal fun debugForm(captcha: String): String = "vercode=" + captcha
}

/** 体测接口异常，[userMessage] 可直接展示给用户 */
sealed class PeException(userMessage: String) : Exception(userMessage) {

    /** 会话过期或未登录，需要回到登录页重新登录 */
    object SessionExpired : PeException("体测登录已过期，请重新登录")

    /** 登录被服务端拒绝（密码错、验证码错、账号不存在…） */
    data class Login(val reason: String) : PeException(reason)

    data class Network(val reason: String) : PeException("网络异常：$reason")

    data class Http(val code: Int) : PeException("服务器返回 HTTP $code，请稍后重试")

    data class Parse(val reason: String) : PeException("数据解析失败：$reason")

    val userMessage: String get() = message ?: "未知错误"
}

/** 统一把异常翻译成一句给用户看的话 */
fun Throwable.toPeMessage(): String = when (this) {
    is PeException -> userMessage
    else -> message?.takeIf { it.isNotBlank() } ?: "未知错误，请稍后重试"
}
