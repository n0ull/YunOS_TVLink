package app.tvlink.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.tvlink.ui.App

fun main() {
    System.setProperty("java.awt.headless", "false")
    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "TVLink",
                resizable = true,
            ) {
                App()
            }
        }
    } catch (e: Throwable) {
        System.err.println("TVLink failed to start: ${e.javaClass.simpleName}: ${e.message}")
        System.err.println(e.stackTraceToString())
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("This application requires a display/GUI environment.")
        }
        System.exit(1)
    }
}
