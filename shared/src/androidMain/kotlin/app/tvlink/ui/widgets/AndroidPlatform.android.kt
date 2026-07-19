package app.tvlink.ui.widgets

import android.content.Context

/** Holds the application context for platform services. Initialized by MainActivity. */
object AndroidPlatform {
    lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
