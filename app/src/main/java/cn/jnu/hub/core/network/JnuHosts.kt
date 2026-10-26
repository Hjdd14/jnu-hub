package cn.jnu.hub.core.network

/** 暨大各业务系统域名集中管理，避免散落魔法字符串 */
object JnuHosts {
    const val ICAS = "https://icas.jnu.edu.cn"
    const val CAS_LOGIN = "$ICAS/cas/login"
    const val AUTH4 = "https://auth4.jnu.edu.cn"
    const val AUTH7 = "https://auth7.jnu.edu.cn"

    const val BLACKBOARD = "https://blackboard.jnu.edu.cn"
    const val UTILITY = "https://pynhcx.jnu.edu.cn"
    const val CAMPUS_NET = "https://mynet.jnu.edu.cn"
    const val MICRO = "https://webapi4.jnu.edu.cn"
    const val FACULTY = "https://faculty.jnu.edu.cn"
    const val PAY = "https://pay.jnu.edu.cn"
    const val PHYSICAL = "http://s2.shishengyun.com"

    /** 教务系统（CampusSphere 门户 + jwapp 子应用） */
    const val JW = "https://jw.jnu.edu.cn"
    const val JW_APP = "$JW/jwapp"

    /** 学生工作管理系统（D-Campus） */
    const val STUIT = "https://stuit.jnu.edu.cn"

    /** 网上服务大厅 —— 全体师生都有权限 */
    const val EHALL = "https://ehall.jnu.edu.cn"

    /**
     * 登录落地页（CAS 的 service 参数）。
     *
     * 教训：曾经用 EHALL 做落地页，但 ehall 首页自身有重定向循环
     * （取到 ticket 后 ehall 又跳回 CAS，CAS 再发新 ticket…）→ ERR_TOO_MANY_REDIRECTS。
     * 改用「教务门户 new/index.html」：它是简单静态页，不会自我循环，
     * 且全体师生都能打开，适合做登录后的统一落地。
     */
    const val LOGIN_LANDING = "https://jw.jnu.edu.cn/new/index.html"

    /**
     * 用于探测「CAS 会话是否有效」的最小入口。
     * 用一个轻量、不自我重定向的页面，避免循环。
     */
    const val CAS_PROBE = LOGIN_LANDING

    /**
     * 登录后统一落地页。
     *
     * 历史教训：曾经用 Blackboard 作为 service，但它只有 JBJI（伯明翰联合学院）的学生有权限，
     * 其他学院的学生登录后会被 BB 拒绝，导致「登录成功却什么都用不了」。
     * 现在改用「网上服务大厅」——全体师生都有，且同样是 auth4 网关，能建立完整 CAS 会话。
     */
    val loginEntry: String
        get() = "$CAS_LOGIN?service=${android.net.Uri.encode(LOGIN_LANDING)}"

    /** CAS 会话探测入口（与 loginEntry 保持一致，保证落地即鉴权） */
    val casProbeUrl: String
        get() = EHALL

    /** 所有需要共享会话的 Cookie 域名（用于 WebView 同步） */
    val cookieHosts = listOf(
        ICAS,
        AUTH4,
        AUTH7,
        BLACKBOARD,
        UTILITY,
        CAMPUS_NET,
        MICRO,
        PAY,
        FACULTY,
        PHYSICAL,
        JW,
        STUIT,
    )
}