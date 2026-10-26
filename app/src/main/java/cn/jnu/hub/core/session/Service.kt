package cn.jnu.hub.core.session

/** App 集成的各业务系统 */
enum class Service(val label: String) {
    BLACKBOARD("Blackboard"),
    UTILITY("水电费"),
    CAMPUS_NET("校园网"),
    PHYSICAL("体测"),
    FACULTY("师资"),
}

/** 会话状态 */
enum class SessionState { UNKNOWN, LOGGED_IN, EXPIRED, ERROR }

data class ServiceSession(
    val service: Service,
    val state: SessionState = SessionState.UNKNOWN,
    /** 最近一次成功探测的时间戳（ms） */
    val lastOkAt: Long = 0L,
    /** 预估剩余可用分钟数；-1 表示未知 */
    val remainingMinutes: Int = -1,
    val message: String? = null,
)
