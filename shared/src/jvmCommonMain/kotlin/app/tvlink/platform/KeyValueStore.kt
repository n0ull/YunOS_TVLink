package app.tvlink.platform

expect class KeyValueStore(
    name: String,
) {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String,
    )

    fun remove(key: String)
}
