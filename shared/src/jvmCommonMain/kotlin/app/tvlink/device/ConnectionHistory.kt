package app.tvlink.device

import app.tvlink.platform.KeyValueStore

enum class ConnectionMode { FULL, IB_ONLY }

data class DeviceTarget(
    val ip: String,
    val name: String = "",
    val model: String = "",
    val uuid: String = "",
    val mac: String = "",
    val projectionPort: Int = 0,
    val ibVer: String = "",
    val ibSid: String = "",
)

data class SavedSelection(
    val target: DeviceTarget,
    val mode: ConnectionMode,
)

interface ConnectionHistory {
    fun load(): SavedSelection?

    fun save(selection: SavedSelection)
}

class KeyValueConnectionHistory(
    private val store: KeyValueStore = KeyValueStore("device-history"),
) : ConnectionHistory {
    override fun load(): SavedSelection? {
        val ip = store.getString("last.ip") ?: return null
        val target =
            DeviceTarget(
                ip = ip,
                name = store.getString("last.name") ?: ip,
                model = store.getString("last.model") ?: "",
                uuid = store.getString("last.uuid") ?: "",
                mac = store.getString("last.mac") ?: "",
                projectionPort = store.getString("last.projectionPort")?.toIntOrNull() ?: 0,
            )
        val mode =
            store.getString("last.mode")?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
                ?: ConnectionMode.FULL
        return SavedSelection(target, mode)
    }

    override fun save(selection: SavedSelection) {
        val target = selection.target
        store.putString("last.ip", target.ip)
        store.putString("last.name", target.name)
        store.putString("last.model", target.model)
        store.putString("last.uuid", target.uuid)
        store.putString("last.mac", target.mac)
        store.putString("last.projectionPort", target.projectionPort.toString())
        store.putString("last.mode", selection.mode.name)
    }
}
