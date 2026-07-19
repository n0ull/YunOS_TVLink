package app.tvlink.proto

import app.tvlink.proto.mdns.Mdns
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class MdnsTest {
    /** Build a minimal DNS response: 1 answer PTR + 1 answer TXT on the service name. */
    private fun buildResponse(): ByteArray {
        val out = ByteArrayOutputStream()
        // header: qd=0, an=2
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 2, 0, 0, 0, 0))
        val name = encodeName(Mdns.SERVICE)
        // PTR record: name -> "MyBox._alitv_remote_control._tcp.local"
        out.write(name)
        out.write(byteArrayOf(0, 12, 0, 1, 0, 0, 0, 60)) // TYPE=PTR, CLASS=IN, TTL=60
        val ptrTarget = encodeName("MyBox.${Mdns.SERVICE}")
        out.write(byteArrayOf((ptrTarget.size shr 8).toByte(), ptrTarget.size.toByte()))
        out.write(ptrTarget)
        // TXT record: deviceid + projectionPort
        out.write(name)
        out.write(byteArrayOf(0, 16, 0, 1, 0, 0, 0, 60)) // TYPE=TXT
        val txt = ByteArrayOutputStream()
        for (kv in listOf("deviceid=AA:BB:CC:DD:EE:FF", "projectionPort=13520")) {
            txt.write(kv.length)
            txt.write(kv.toByteArray())
        }
        val txtBytes = txt.toByteArray()
        out.write(byteArrayOf((txtBytes.size shr 8).toByte(), txtBytes.size.toByte()))
        out.write(txtBytes)
        return out.toByteArray()
    }

    private fun encodeName(fqdn: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in fqdn.removeSuffix(".").split(".")) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        return out.toByteArray()
    }

    @Test
    fun parsePtrAndTxt() {
        val devices = LinkedHashMap<String, Mdns.MdnsDevice>()
        Mdns.parse(buildResponse(), "192.168.1.50", devices)
        val d = checkNotNull(devices["192.168.1.50"]) { "expected parsed device for 192.168.1.50" }
        assertEquals("MyBox", d.name)
        assertEquals("AA:BB:CC:DD:EE:FF", d.mac)
        assertEquals(13520, d.projectionPort)
    }

    @Test
    fun queryIsWellFormed() {
        val q = Mdns.buildQuery()
        // header: qdcount=1 at bytes 4-5
        assertEquals(0, q[4].toInt())
        assertEquals(1, q[5].toInt())
        // ends with QTYPE=ANY(0x00FF) QCLASS=IN(0x0001) after root label 0
        val n = q.size
        assertEquals(0, q[n - 5].toInt())
        assertEquals(0xFF, q[n - 3].toInt() and 0xFF)
        assertEquals(1, q[n - 1].toInt())
    }
}
