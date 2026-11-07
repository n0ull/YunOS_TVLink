package app.tvlink.platform

import android.content.Context

/** Holds the application context for platform adapters. Initialized by MainActivity. */
object AndroidPlatform {
    lateinit var appContext: Context

    val isInitialized: Boolean get() = ::appContext.isInitialized

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
