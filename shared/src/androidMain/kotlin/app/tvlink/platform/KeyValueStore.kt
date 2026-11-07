package app.tvlink.platform

actual class KeyValueStore actual constructor(
    name: String,
) {
    private val prefs = AndroidPlatform.appContext.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
