package app.tvlink.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设备发现代理：持有 Discovery 实例，暴露 foundDevices / scanState 两个 StateFlow。
 * 由 DeviceManager 门面持有并委托。
 */
class DiscoveryAgent {
    enum class ScanState { IDLE, SCANNING, DONE }

    private val discovery = Discovery()

    private val _foundDevices = MutableStateFlow<List<Discovery.FoundDevice>>(emptyList())
    val foundDevices: StateFlow<List<Discovery.FoundDevice>> = _foundDevices

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState

    init {
        discovery.onDeviceFound = { _ ->
            _foundDevices.value = discovery.devices.sortedBy { it.ip }
        }
        discovery.onFinished = {
            _scanState.value = ScanState.DONE
        }
    }

    fun startDiscovery() {
        _scanState.value = ScanState.SCANNING
        _foundDevices.value = emptyList()
        discovery.start(scanSubnet = true)
    }

    fun stopDiscovery() = discovery.stop()
}
