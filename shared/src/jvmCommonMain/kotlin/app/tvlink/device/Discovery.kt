package app.tvlink.device

import app.tvlink.platform.localLanAddress
import app.tvlink.proto.ib.probeIbHello
import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.mdns.Mdns
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveryState(
    val scanning: Boolean = false,
    val devices: List<Discovery.FoundDevice> = emptyList(),
)

/**
 * Dual-channel device discovery (mirrors DevmgrBizBu.search):
 *  A) mDNS multicast query for _alitv_remote_control._tcp.local
 *  B) /24 subnet sweep probing TCP 13511 with a DETECT handshake
 */
class Discovery {
    data class FoundDevice(
        val ip: String,
        val name: String = "",
        val mac: String = "",
        val model: String = "",
        val uuid: String = "",
        val projectionPort: Int = 0,
        val source: String = "",
        /** IB 服务器版本(如 "3.29"),由 3988 探测响应 body 解析;IDC/mDNS 探测不产出。 */
        val ibVer: String = "",
        /** IB hello 响应中的 sid(会话标识),3988 探测产出;IDC/mDNS 探测不产出。 */
        val ibSid: String = "",
    )

    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state
    private val stateLock = Any()

    /** Test fixture for the blocking mDNS worker; production uses the real probe. */
    internal var mdnsProber: (epoch: Int, onResult: (Mdns.MdnsDevice) -> Unit) -> Unit =
        { epoch, onResult -> runMdns(epoch, onResult) }

    /** Observes worker joins in race tests; not a public scan-completion callback. */
    internal var joinFinished: (epoch: Int) -> Unit = { }

    /** 测试注入点:单 host 探测动作,默认走真实网络(probeIdc + probeIb)。 */
    internal var hostProber: (ip: String, epoch: Int) -> Unit = { ip, epoch -> probeHost(ip, epoch) }

    @Volatile
    private var running = false
    private val epoch =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val found = ConcurrentHashMap<String, FoundDevice>()
    private var threads = mutableListOf<Thread>()

    fun start(scanSubnet: Boolean = true) {
        stop()
        val myEpoch: Int
        synchronized(stateLock) {
            found.clear()
            running = true
            myEpoch = epoch.incrementAndGet()
            _state.value = DiscoveryState(scanning = true)
        }
        val tMdns =
            Thread(
                {
                    mdnsProber(myEpoch) { md ->
                        report(
                            FoundDevice(
                                ip = md.ip,
                                name = md.name,
                                mac = md.mac,
                                projectionPort = md.projectionPort,
                                source = "mdns",
                            ),
                            myEpoch,
                        )
                    }
                },
                "disc-mdns",
            ).apply {
                isDaemon = true
                start()
            }
        threads.add(tMdns)
        if (scanSubnet) {
            val tScan =
                Thread({ runSubnetScan(myEpoch) }, "disc-scan").apply {
                    isDaemon = true
                    start()
                }
            threads.add(tScan)
            Thread(
                {
                    tMdns.join()
                    tScan.join()
                    joinFinished(myEpoch)
                    finish(myEpoch)
                },
                "disc-join",
            ).apply {
                isDaemon = true
                start()
            }
        } else {
            Thread(
                {
                    tMdns.join()
                    joinFinished(myEpoch)
                    finish(myEpoch)
                },
                "disc-join",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun finish(myEpoch: Int) {
        synchronized(stateLock) {
            if (!active(myEpoch)) return
            _state.value = _state.value.copy(scanning = false)
        }
    }

    fun stop() {
        synchronized(stateLock) {
            running = false
            _state.value = _state.value.copy(scanning = false)
        }
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    private fun report(
        d: FoundDevice,
        myEpoch: Int,
    ) {
        synchronized(stateLock) {
            if (!active(myEpoch)) return
            found.compute(d.ip) { _, existing ->
                if (existing == null) {
                    d
                } else {
                    existing.copy(
                        name = d.name.ifEmpty { existing.name },
                        mac = d.mac.ifEmpty { existing.mac },
                        model = d.model.ifEmpty { existing.model },
                        uuid = d.uuid.ifEmpty { existing.uuid },
                        projectionPort = if (d.projectionPort != 0) d.projectionPort else existing.projectionPort,
                        ibVer = d.ibVer.ifEmpty { existing.ibVer },
                        ibSid = d.ibSid.ifEmpty { existing.ibSid },
                    )
                }
            }
            _state.value = _state.value.copy(devices = found.values.sortedBy { it.ip })
        }
    }

    private fun runMdns(
        myEpoch: Int,
        onResult: (Mdns.MdnsDevice) -> Unit,
    ) {
        if (!active(myEpoch)) return
        try {
            Mdns.discoverOnce(listenMs = 5000, bindAddr = localLanAddress()) { md ->
                if (active(myEpoch)) onResult(md)
            }
        } catch (e: Exception) {
            // no multicast on this network — subnet scan still runs
            System.err.println("Discovery: mDNS scan failed: ${e.message}")
        }
    }

    private fun active(myEpoch: Int) = running && epoch.get() == myEpoch

    private fun runSubnetScan(myEpoch: Int) {
        val local = localLanAddress() ?: return
        val selfIp = local.hostAddress ?: return
        val prefix = selfIp.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(24) { r -> Thread(r, "disc-probe").apply { isDaemon = true } }
        try {
            submitProbes(pool, prefix, selfIp, myEpoch)
        } finally {
            // 正常路径必须 shutdown()(排队任务全部跑完);shutdownNow() 会丢弃未启动的
            // 排队探测——submitProbes 仅入队即返回,/24 将只剩前 ~24 个地址被扫(e7940d2 回归)。
            // 取消路径:stop() interrupt 使 awaitTermination 抛 InterruptedException,
            // 此时再 shutdownNow() 丢弃队列并中断在跑探测,保留 M-2 的快速取消语义。
            pool.shutdown()
            try {
                pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                pool.shutdownNow()
            }
        }
    }

    private fun submitProbes(
        pool: ExecutorService,
        prefix: String,
        selfIp: String,
        myEpoch: Int,
    ) {
        for (i in 1..254) {
            if (!active(myEpoch)) return
            val ip = "$prefix.$i"
            if (ip == selfIp) continue
            pool.execute { hostProber(ip, myEpoch) }
        }
    }

    /**
     * 对单个 host 顺序探测 IDC(13511) 与 IB(3988)。某型号 TV 不监听 13511 时会漏扫,
     * 此时 IB 通道仍开放,可作为兜底发现路径。report() 按 IP 合并,双通时 IDC 的
     * name/model/uuid 信息更丰富,IB 仅补 source 标签。
     * 两者各自已有超时(1200ms)，在池线程内顺序执行即可，无需再启内层 Thread。
     */
    private fun probeHost(
        ip: String,
        myEpoch: Int,
    ) {
        if (!active(myEpoch)) return
        probeIdc(ip, myEpoch)
        if (!active(myEpoch)) return
        probeIb(ip, myEpoch)
    }

    private fun probeIdc(
        ip: String,
        myEpoch: Int,
    ) {
        if (!active(myEpoch)) return
        val conn = IdcConnection(ip, IdcConst.TCP_PORT_DETECT)
        try {
            val info = conn.detect(timeoutMs = 1200)
            if (info != null && active(myEpoch)) {
                report(
                    FoundDevice(
                        ip = ip,
                        name = info.name,
                        model = info.model,
                        uuid = info.uuid,
                        source = "scan",
                    ),
                    myEpoch,
                )
            }
        } finally {
            conn.shutdown()
        }
    }

    private fun probeIb(
        ip: String,
        myEpoch: Int,
    ) {
        if (!active(myEpoch)) return
        val hello = probeIbHello(ip, timeoutMs = 1200) { active(myEpoch) } ?: return
        report(FoundDevice(ip = ip, source = "ib-scan", ibVer = hello.version, ibSid = hello.sessionId), myEpoch)
    }
}
