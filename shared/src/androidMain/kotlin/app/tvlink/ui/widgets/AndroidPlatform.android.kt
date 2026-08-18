package app.tvlink.ui.widgets

import android.content.Context

/** Holds the application context for platform services. Initialized by MainActivity. */
object AndroidPlatform {
    lateinit var appContext: Context

    /** 外部判初始化用(lateinit 的 isInitialized 仅对象内部可见)。 */
    val isInitialized: Boolean get() = ::appContext.isInitialized

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
