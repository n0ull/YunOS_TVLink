package app.tvlink.proto.mdns

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Minimal mDNS (Bonjour) client for discovering `_alitv_remote_control._tcp.local`.
 * One-shot query + passive listen; parsing supports PTR/SRV/A/TXT with name compression.
 */
object Mdns {
    const val GROUP = "224.0.0.251"
    const val PORT = 5353
    const val SERVICE = "_alitv_remote_control._tcp.local"

    data class MdnsDevice(
        val name: String = "",
        val ip: String = "",
        val port: Int = 0,
        val mac: String = "",
        val projectionPort: Int = 0,
    )

    fun buildQuery(service: String = SERVICE): ByteArray {
        val out = ByteArrayOutputStream()
        // header: id=0, flags=0, qd=1, an=0, ns=0, ar=0
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in service.removeSuffix(".").split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.UTF_8))
        }
        out.write(0)
        out.write(byteArrayOf(0, 255.toByte(), 0, 1)) // QTYPE=ANY, QCLASS=IN
        return out.toByteArray()
    }

    /** Parse a DNS message, folding PTR/SRV/A/TXT into devices keyed by source ip. */
    fun parse(
        data: ByteArray,
        sourceIp: String,
        into: MutableMap<String, MdnsDevice>,
    ) {
        val buf = ByteBuffer.wrap(data)
        if (data.size < 12) return
        buf.position(4)
        val qd = buf.short.toInt() and 0xFFFF
        val an = buf.short.toInt() and 0xFFFF
        buf.short
        buf.short // ns, ar
        repeat(qd) {
            skipName(buf)
            buf.position(buf.position() + 4)
        }
        repeat(an) {
            skipName(buf)
            if (buf.remaining() < 10) return
            val type = buf.short.toInt() and 0xFFFF
            buf.short // class
            buf.int // ttl
            val rdLen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < rdLen) return
            val rdStart = buf.position()
            var dev = into.getOrPut(sourceIp) { MdnsDevice(ip = sourceIp) }
            when (type) {
                12 -> dev = dev.copy(name = readName(buf).removeSuffix(".$SERVICE").removeSuffix("."))
                33 -> { // SRV
                    buf.short
                    buf.short
                    dev = dev.copy(port = buf.short.toInt() and 0xFFFF)
                }

                1 -> { // A
                    if (rdLen == 4) {
                        val b = ByteArray(4)
                        buf.get(b)
                        dev = dev.copy(ip = b.joinToString(".") { (it.toInt() and 0xFF).toString() })
                    }
                }

                16 -> dev = parseTxt(buf, rdStart + rdLen, dev) // TXT
            }
            into[sourceIp] = dev
            buf.position(rdStart + rdLen)
        }
    }

    /** Fold TXT key/value pairs (deviceid, projectionPort) into [dev], returning the updated copy. */
    private fun parseTxt(
        buf: ByteBuffer,
        end: Int,
        dev: MdnsDevice,
    ): MdnsDevice {
        var updated = dev
        while (buf.position() < end) {
            val l = buf.get().toInt() and 0xFF
            if (l == 0 || buf.remaining() < l) break
            val kv = ByteArray(l)
            buf.get(kv)
            val s = String(kv, Charsets.UTF_8)
            val eq = s.indexOf('=')
            if (eq > 0) {
                when (s.substring(0, eq)) {
                    "deviceid" -> updated = updated.copy(mac = s.substring(eq + 1))
                    "projectionPort" -> updated = updated.copy(projectionPort = s.substring(eq + 1).toIntOrNull() ?: 0)
                }
            }
        }
        return updated
    }

    private fun skipName(buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val l = buf.get().toInt() and 0xFF
            when {
                l == 0 -> return
                l and 0xC0 == 0xC0 -> {
                    if (buf.hasRemaining()) buf.get()
                    return
                }

                else -> {
                    if (buf.remaining() < l) return
                    buf.position(buf.position() + l)
                }
            }
        }
    }

    private fun readName(buf: ByteBuffer): String {
        val sb = StringBuilder()
        var jumped = false
        var endPos = -1
        var guard = 0
        while (guard++ < 128) {
            val l = buf.get().toInt() and 0xFF
            when {
                l == 0 -> break
                l and 0xC0 == 0xC0 -> {
                    val l2 = buf.get().toInt() and 0xFF
                    val ptr = ((l and 0x3F) shl 8) or l2
                    if (!jumped) endPos = buf.position()
                    buf.position(ptr)
                    jumped = true
                }

                else -> {
                    val b = ByteArray(l)
                    buf.get(b)
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(b, Charsets.UTF_8))
                }
            }
        }
        if (jumped && endPos >= 0) buf.position(endPos)
        return sb.toString()
    }

    /**
     * Send one query, then collect responses until [listenMs] elapses.
     * [onDevice] fires for every newly-seen source ip. Blocking — call from a worker thread.
     */
    fun discoverOnce(
        listenMs: Long = 4000,
        bindAddr: InetAddress? = null,
        onDevice: (MdnsDevice) -> Unit,
    ) {
        val devices = LinkedHashMap<String, MdnsDevice>()
        val group = InetAddress.getByName(GROUP)
        val socket =
            if (bindAddr != null) {
                MulticastSocket(InetSocketAddress(bindAddr, PORT))
            } else {
                MulticastSocket(PORT)
            }
        socket.soTimeout = 500
        try {
            socket.joinGroup(InetSocketAddress(group, PORT), pickInterface(bindAddr))
            val query = buildQuery()
            socket.send(DatagramPacket(query, query.size, group, PORT))
            val deadline = System.currentTimeMillis() + listenMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    handlePacket(p, devices, onDevice)
                } catch (_: SocketTimeoutException) {
                    // soTimeout 短超时就是本循环的轮询节拍（每 500ms 必触发一次），按设计静默继续
                }
            }
        } finally {
            try {
                socket.leaveGroup(InetSocketAddress(group, PORT), pickInterface(bindAddr))
            } catch (_: Exception) {
            }
            socket.close()
        }
    }

    private fun handlePacket(
        p: DatagramPacket,
        devices: MutableMap<String, MdnsDevice>,
        onDevice: (MdnsDevice) -> Unit,
    ) {
        val src = p.address.hostAddress ?: return
        val isNew = src !in devices
        try {
            parse(p.data.copyOf(p.length), src, devices)
        } catch (e: Exception) {
            System.err.println("Mdns: skip bad packet from $src: ${e.message}")
        }
        devices[src]?.let { if (isNew || it.name.isNotEmpty()) onDevice(it) }
    }

    private fun pickInterface(preferred: InetAddress?): NetworkInterface? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
            if (preferred != null && ni.inetAddresses.asSequence().contains(preferred)) return ni
        }
        return null
    }

    /** Best-effort local IPv4 on the LAN (prefers wlan / eth interfaces on Android). */
    fun localLanAddress(): Inet4Address? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: Inet4Address? = null
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                if (addr !is Inet4Address || !addr.isSiteLocalAddress) continue
                if (ni.name.startsWith("wlan")) return addr
                if (fallback == null) fallback = addr
            }
        }
        return fallback
    }
}
