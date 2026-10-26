package cn.jnu.hub.feature.jw

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * 把隐藏 WebView 挂进当前 Activity 的窗口。
 *
 * ## 为什么必须挂
 *
 * 未 attach 的 WebView 在部分 ROM / WebView 版本上**不会触发 onPageFinished**，
 * 也不会推进重定向链（实测 20 秒内零回调，只能靠超时收场）。
 * 挂进 decorView 之后行为与可见 WebView 一致，只是用户看不见（1x1、alpha=0）。
 *
 * 由 [JwJsFetcher] 与 [JwGidProbe] 共用。
 */
object JwViewHost {

    private const val TAG = "JwViewHost"

    fun attach(webView: WebView) {
        runCatching {
            val activity = currentActivity() ?: run {
                Log.d(TAG, "no activity, webview stays detached")
                return
            }
            val container = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                alpha = 0f
                isClickable = false
                isFocusable = false
                // 给一个接近真机的视口：门户是响应式布局，1x1 会让部分脚本判定为不可见而不渲染
                addView(webView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
            // 必须铺满屏幕：实测容器给 1x1 时 WebView 视口只有 4x4，
            // 门户按响应式布局把内容全塌缩，点击坐标也全部错位。
            (activity.window.decorView.rootView as? ViewGroup)?.addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            Log.d(TAG, "hidden webview attached")
        }.onFailure { Log.w(TAG, "attach failed", it) }
    }

    fun detach(webView: WebView) {
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            // 连同给它建的容器一起摘掉，避免残留视图堆积
            val host = (webView.parent as? ViewGroup)?.parent as? ViewGroup
            host?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
    }

    /** 通过 ActivityThread 反射取当前 Activity（隐藏 WebView 需要 window token） */
    private fun currentActivity(): Activity? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = cls.getMethod("currentActivityThread").invoke(null)
        val activities = cls.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread)
        (activities as? android.util.ArrayMap<*, *>)?.values?.asSequence()
            ?.mapNotNull { rec ->
                runCatching {
                    rec!!.javaClass.getDeclaredField("activity")
                        .apply { isAccessible = true }
                        .get(rec) as? Activity
                }.getOrNull()
            }
            ?.firstOrNull { it != null && !it.isFinishing }
    }.getOrNull()
}