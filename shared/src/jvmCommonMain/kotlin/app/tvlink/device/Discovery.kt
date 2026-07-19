package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.mdns.Mdns
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Dual-channel device discovery (mirrors DevmgrBizBu.search):
 *  A) mDNS multicast query for _alitv_remote_control._tcp.local
 *  B) /24 subnet sweep probing TCP 13511 with a DETECT handshake
 */
class Discovery {

    data class FoundDevice(
        val ip: String,
        var name: String = "",
        var mac: String = "",
        var model: String = "",
        var uuid: String = "",
        var projectionPort: Int = 0,
        var source: String = "",
    )

    var onDeviceFound: ((FoundDevice) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    @Volatile private var running = false
    private val epoch = java.util.concurrent.atomic.AtomicInteger(0)
    private val found = ConcurrentHashMap<String, FoundDevice>()
    private var threads = mutableListOf<Thread>()

    val devices: List<FoundDevice> get() = found.values.toList()

    fun start(scanSubnet: Boolean = true) {
        stop()
        running = true
        found.clear()
        val myEpoch = epoch.incrementAndGet()
        val tMdns = Thread({ runMdns(myEpoch) }, "disc-mdns").apply { isDaemon = true; start() }
        threads.add(tMdns)
        if (scanSubnet) {
            val tScan = Thread({ runSubnetScan(myEpoch) }, "disc-scan").apply { isDaemon = true; start() }
            threads.add(tScan)
            Thread({
                tMdns.join(); tScan.join()
                onFinished?.invoke()
            }, "disc-join").apply { isDaemon = true; start() }
        } else {
            Thread({
                tMdns.join()
                onFinished?.invoke()
            }, "disc-join").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        running = false
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    private fun report(d: FoundDevice) {
        val existing = found[d.ip]
        if (existing == null) {
            found[d.ip] = d
            onDeviceFound?.invoke(d)
        } else {
            var changed = false
            if (d.name.isNotEmpty() && existing.name != d.name) { existing.name = d.name; changed = true }
            if (d.mac.isNotEmpty() && existing.mac != d.mac) { existing.mac = d.mac; changed = true }
            if (d.model.isNotEmpty() && existing.model != d.model) { existing.model = d.model; changed = true }
            if (d.uuid.isNotEmpty() && existing.uuid != d.uuid) { existing.uuid = d.uuid; changed = true }
            if (d.projectionPort != 0 && existing.projectionPort != d.projectionPort) { existing.projectionPort = d.projectionPort; changed = true }
            if (changed) onDeviceFound?.invoke(existing)
        }
    }

    private fun runMdns(myEpoch: Int) {
        if (!active(myEpoch)) return
        try {
            Mdns.discoverOnce(listenMs = 5000, bindAddr = Mdns.localLanAddress()) { md ->
                if (!active(myEpoch)) return@discoverOnce
                report(FoundDevice(ip = md.ip, name = md.name, mac = md.mac, projectionPort = md.projectionPort, source = "mdns"))
            }
        } catch (e: Exception) {
            // no multicast on this network — subnet scan still runs
        }
    }

    private fun active(myEpoch: Int) = running && epoch.get() == myEpoch

    private fun runSubnetScan(myEpoch: Int) {
        val local = Mdns.localLanAddress() ?: return
        val selfIp = local.hostAddress ?: return
        val prefix = selfIp.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(24) { r -> Thread(r, "disc-probe").apply { isDaemon = true } }
        try {
            for (i in 1..254) {
                if (!active(myEpoch)) break
                val ip = "$prefix.$i"
                if (ip == selfIp) continue
                pool.execute {
                    if (!active(myEpoch)) return@execute
                    if (probePort(ip, IdcConst.TCP_PORT_DETECT, 800)) {
                        val conn = IdcConnection(ip, IdcConst.TCP_PORT_DETECT)
                        try {
                            val info = conn.detect(timeoutMs = 1200)
                            if (info != null && active(myEpoch)) {
                                report(FoundDevice(ip = ip, name = info.name, model = info.model, uuid = info.uuid, source = "scan"))
                            }
                        } finally {
                            conn.shutdown()
                        }
                    }
                }
            }
        } finally {
            pool.shutdown()
            try { pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
    }

    private fun probePort(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }
}
