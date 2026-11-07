package app.tvlink.platform

actual class KeyValueStore actual constructor(
    name: String,
) {
    private val node =
        java.util.prefs.Preferences
            .userRoot()
            .node("app/tvlink/$name")

    actual fun getString(key: String): String? = node.get(key, null)

    actual fun putString(
        key: String,
        value: String,
    ) = node.put(key, value)

    actual fun remove(key: String) = node.remove(key)
}
