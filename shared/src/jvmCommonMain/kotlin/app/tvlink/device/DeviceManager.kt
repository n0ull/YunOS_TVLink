package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Facade over ConnectionManager + DiscoveryAgent, exposing StateFlows for the UI.
 * Lives in jvmCommonMain — no Android/desktop-specific code here.
 *
 * 公共 API 签名与原实现完全一致（服务层与测试零修改）；实现委托给两个子模块。
 *
 * ConnState / ConnectedDevice 为本类嵌套类型（见下方声明）；ConnectionManager / DiscoveryAgent
 * 同包引用时写作 DeviceManager.ConnState 形式。
 */
class DeviceManager {
    enum class ConnState { IDLE, SEARCHING, CONNECTING, CONNECTED, FAILED }

    data class ConnectedDevice(
        val ip: String,
        val name: String,
        val model: String,
        val uuid: String,
        /** MAC 来自 mDNS TXT deviceid;扫描/手输 IP 路径无来源,为空。 */
        val mac: String = "",
        val projectionPort: Int,
        /** IB 服务器版本(如 "3.29"),由 3988 探测产出;手动输入 IP 连接时为空。 */
        val ibVer: String = "",
        /** IB hello 响应中的 sid(会话标识),3988 探测产出;手动输入 IP 连接时为空。 */
        val ibSid: String = "",
        /** IB-only 连接:仅 IB 通道(3988)可达,IDC(13510/13511)未开放。
         *  遥控可用,投屏/截图/应用管理/系统属性不可用。 */
        val ibOnly: Boolean = false,
    )

    private val connectionManager = ConnectionManager()
    private val discoveryAgent = DiscoveryAgent()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** ScanState.DONE → ConnState.IDLE 的桥接收集器；全程单实例（审查 #1：原每次 startDiscovery
     *  都 launch 新 collect 且永不取消，N 次扫描泄漏 N 个常驻协程）。 */
    private var scanStateBridge: Job? = null

    fun addModuleListener(l: (name: String, moduleId: Int, online: Boolean) -> Unit) =
        connectionManager.addModuleListener(l)

    fun removeModuleListener(l: (name: String, moduleId: Int, online: Boolean) -> Unit) =
        connectionManager.removeModuleListener(l)

    fun addVConnListener(l: (Int, ByteArray) -> Unit) = connectionManager.addVConnListener(l)

    fun removeVConnListener(l: (Int, ByteArray) -> Unit) = connectionManager.removeVConnListener(l)

    val connState: StateFlow<ConnState> = connectionManager.connState

    val foundDevices: StateFlow<List<Discovery.FoundDevice>> = discoveryAgent.foundDevices

    val connected: StateFlow<ConnectedDevice?> = connectionManager.connected

    val modules: StateFlow<List<IdcConnection.ModuleInfo>> = connectionManager.modules

    /** Unmatched packets from the TV (IME events, screenshot resp, ...). */
    val packets: SharedFlow<IdcPacket> = connectionManager.packets

    /** 服务层（RcController 等）直接读/写 `deviceManager.connection?.send(...)`. */
    var connection: IdcConnection?
        get() = connectionManager.connection
        set(value) = connectionManager.internalSetConnection(value)

    fun startDiscovery() {
        // 与原实现一致：扫描开始置 SEARCHING；Discovery.onFinished 且仍在 SEARCHING 时回 IDLE。
        if (connectionManager.connState.value == ConnState.IDLE) {
            connectionManager.setConnStateForFacade(ConnState.SEARCHING)
            // 单实例桥接（见 scanStateBridge 注）：复用常驻 collect，不再每次扫描新挂一个。
            if (scanStateBridge?.isActive != true) {
                scanStateBridge = scope.launch {
                    discoveryAgent.scanState.collect { s ->
                        if (s == DiscoveryAgent.ScanState.DONE &&
                            connectionManager.connState.value == ConnState.SEARCHING
                        ) {
                            connectionManager.setConnStateForFacade(ConnState.IDLE)
                        }
                    }
                }
            }
        }
        discoveryAgent.startDiscovery()
    }

    fun stopDiscovery() = discoveryAgent.stopDiscovery()

    fun connect(device: Discovery.FoundDevice) {
        connect(device.ip, device.projectionPort, device.ibVer, device.ibSid, device.mac)
    }

    fun connect(
        ip: String,
        projectionPort: Int = 0,
        ibVer: String = "",
        ibSid: String = "",
        mac: String = "",
    ) = connectionManager.connect(ip, projectionPort, ibVer, ibSid, mac)

    fun connectIbOnly(
        ip: String,
        ibVer: String = "",
        ibSid: String = "",
    ) = connectionManager.connectIbOnly(ip, ibVer, ibSid)

    fun disconnect() = connectionManager.disconnect()

    fun lastDevice(): ConnectedDevice? = connectionManager.lastDevice()

    fun destroy() {
        stopDiscovery()
        disconnect()
        connectionManager.destroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    fun moduleId(name: String): Int? = connectionManager.moduleId(name)

    fun sendVConnJson(
        moduleId: Int,
        json: String,
    ) = connectionManager.sendVConnJson(moduleId, json)
}
