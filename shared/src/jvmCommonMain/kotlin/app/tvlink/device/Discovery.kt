package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.mdns.Mdns
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    )

    var onDeviceFound: ((FoundDevice) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    @Volatile
    private var running = false
    private val epoch =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val found = ConcurrentHashMap<String, FoundDevice>()
    private var threads = mutableListOf<Thread>()

    val devices: List<FoundDevice> get() = found.values.toList()

    fun start(scanSubnet: Boolean = true) {
        stop()
        running = true
        found.clear()
        val myEpoch = epoch.incrementAndGet()
        val tMdns =
            Thread({ runMdns(myEpoch) }, "disc-mdns").apply {
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
                    onFinished?.invoke()
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
                    onFinished?.invoke()
                },
                "disc-join",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        running = false
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    private fun report(d: FoundDevice) {
        val merged =
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
                    )
                }
            }!!
        onDeviceFound?.invoke(merged)
    }

    private fun runMdns(myEpoch: Int) {
        if (!active(myEpoch)) return
        try {
            Mdns.discoverOnce(listenMs = 5000, bindAddr = Mdns.localLanAddress()) { md ->
                if (!active(myEpoch)) return@discoverOnce
                report(
                    FoundDevice(
                        ip = md.ip,
                        name = md.name,
                        mac = md.mac,
                        projectionPort = md.projectionPort,
                        source = "mdns",
                    ),
                )
            }
        } catch (e: Exception) {
            // no multicast on this network — subnet scan still runs
            System.err.println("Discovery: mDNS scan failed: ${e.message}")
        }
    }

    private fun active(myEpoch: Int) = running && epoch.get() == myEpoch

    private fun runSubnetScan(myEpoch: Int) {
        val local = Mdns.localLanAddress() ?: return
        val selfIp = local.hostAddress ?: return
        val prefix = selfIp.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(24) { r -> Thread(r, "disc-probe").apply { isDaemon = true } }
        try {
            submitProbes(pool, prefix, selfIp, myEpoch)
        } finally {
            pool.shutdown()
            try {
                pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
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
            pool.execute { probeHost(ip, myEpoch) }
        }
    }

    private fun probeHost(
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
                )
            }
        } finally {
            conn.shutdown()
        }
    }
}
