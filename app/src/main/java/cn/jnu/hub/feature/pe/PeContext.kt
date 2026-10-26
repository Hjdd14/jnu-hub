package cn.jnu.hub.feature.pe

import android.content.Context
import cn.jnu.hub.core.network.HttpClient

/**
 * 体测模块的运行期初始化。
 *
 * [peFeatureInit] 在首个 Compose 页面里调用一次：确保网络客户端与偏好存储都不为 null。
 * 这里只依赖 core/network 的公开 API，不修改 core 任何文件。
 */
object PeContext {

    private var installed = false

    @Synchronized
    fun install(context: Context) {
        HttpClient.init(context)
        PePrefs.init(context)
        installed = true
    }

    val isInstalled: Boolean get() = installed
}

/** Compose 页面里的一行式初始化 */
fun peFeatureInit(context: Context) {
    if (!PeContext.isInstalled) PeContext.install(context)
}
