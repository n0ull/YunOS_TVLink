package app.tvlink.proto

import app.tvlink.proto.ib.IbChannel
import app.tvlink.proto.ib.IbConst
import app.tvlink.proto.ib.IbHello
import app.tvlink.proto.ib.probeIbHello
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbChannelTest {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var clientSock: Socket? = null

    @AfterTest
    fun tearDown() {
        server.close()
        runCatching { clientSock?.close() }
    }

    /** 读一帧（20B 头 + body），仅握手期用；返回 type。 */
    private fun readFrameType(inp: DataInputStream): Int {
        val header = ByteArray(20)
        inp.readFully(header)
        val b = ByteBuffer.wrap(header)
        check(b.int == IbConst.MAGIC) { "bad magic" }
        val size = b.int
        val type = b.int
        if (size > 0) inp.readFully(ByteArray(size))
        return type
    }

    /** 写 hello 应答帧；握手期对端 helloId=0 → checksum = size + reserve。 */
    private fun writeHelloResp(
        s: Socket,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reserve = 7
        val buf = ByteBuffer.allocate(20 + bytes.size)
        buf.putInt(IbConst.MAGIC)
        buf.putInt(bytes.size)
        buf.putInt(IbConst.RSP_MASK or IbConst.REQ_HELLO)
        buf.putInt(reserve)
        buf.putInt(bytes.size + reserve) // xor helloId(0)
        buf.put(bytes)
        s.getOutputStream().write(buf.array())
        s.getOutputStream().flush()
    }

    /** 写下行帧,真机约定(2026-08-18 实测):TV 下行所有帧恒 reserve=0/checksum=0。 */
    private fun writeFrameRealTv(
        s: Socket,
        type: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(20 + bytes.size)
        buf.putInt(IbConst.MAGIC)
        buf.putInt(bytes.size)
        buf.putInt(type)
        buf.putInt(0) // reserve
        buf.putInt(0) // checksum
        buf.put(bytes)
        s.getOutputStream().write(buf.array())
        s.getOutputStream().flush()
    }

    /**
     * 握手读超时回归（H6）：对端 accept 后收包不应答——旧实现 readFrame 无限阻塞，
     * connect 调用线程永久泄漏、IB 通道永不重试。修复后 soTimeout 触发快速失败。
     */
    @Test
    fun connectFailsFastWhenPeerNeverResponds() {
        thread(isDaemon = true) {
            runCatching {
                val s = server.accept()
                clientSock = s
                val inp = DataInputStream(s.getInputStream())
                readFrameType(inp) // 收 hello，但永不应答
                Thread.sleep(10_000) // 保持连接开放，等不到应答
            }
        }
        val ch = IbChannel("127.0.0.1", server.localPort)
        val done = CountDownLatch(1)
        val result =
            java.util.concurrent.atomic
                .AtomicReference<Boolean?>(null)
        thread(isDaemon = true) {
            result.set(ch.connect(timeoutMs = 800))
            done.countDown()
        }
        assertTrue(done.await(6, TimeUnit.SECONDS), "connect did not return — handshake has no read timeout")
        assertEquals(false, result.get(), "connect should fail when peer never answers hello")
        assertEquals(IbChannel.State.DISCONNECTED, ch.state)
        ch.disconnect()
    }

    /** 握手正常路径：hello 应答解析 sid/ver（"3.29" → 329），建链后状态 READY，
     *  且稳态空闲超 timeoutMs 不被握手超时误伤（soTimeout 须在握手后复位为 0）。 */
    @Test
    fun helloHandshakeParsesServerVersion() {
        thread(isDaemon = true) {
            runCatching {
                val s = server.accept()
                clientSock = s
                val inp = DataInputStream(s.getInputStream())
                readFrameType(inp) // hello
                writeHelloResp(s, """{"sid":123,"ver":"3.29"}""")
                readFrameType(inp) // MODULEINFO
                readFrameType(inp) // CHANGETYPE
                Thread.sleep(4_000) // 覆盖 idle>timeoutMs 窗口
            }
        }
        val ch = IbChannel("127.0.0.1", server.localPort)
        assertTrue(ch.connect(timeoutMs = 2000), "connect failed")
        assertEquals(IbChannel.State.READY, ch.state)
        assertEquals(329, ch.serverVer)
        Thread.sleep(2_500) // 稳态空闲超过握手超时：reader 线程不应被 soTimeout 误杀
        assertEquals(IbChannel.State.READY, ch.state, "steady-state idle must not hit handshake timeout")
        ch.disconnect()
    }

    /**
     * 真机回归(2026-08-18 实测, ver 3.29):TV 下行所有帧恒
     * reserve=0/checksum=0,从不按公式 (size+reserve)^helloId 计算。
     * f076f90 的接收侧 checksum 校验会把 hello 应答及其后每条下行帧全部误判
     * 损坏 → connect 静默失败 / 通道 READY 后被首个 cur_app 推送杀死,
     * IB 永不可用,按键全部回退 IDC。接收侧不得校验 checksum。
     */
    @Test
    fun realTvZeroChecksumFramesKeepChannelAlive() {
        thread(isDaemon = true) {
            runCatching {
                val s = server.accept()
                clientSock = s
                val inp = DataInputStream(s.getInputStream())
                readFrameType(inp) // hello
                writeFrameRealTv(s, IbConst.RSP_MASK or IbConst.REQ_HELLO, """{"ver":"3.29", "sid":123}""")
                readFrameType(inp) // MODULEINFO
                readFrameType(inp) // CHANGETYPE
                writeFrameRealTv(s, 274, """{"cur_app":"com.test.app"}""") // PROTO_CURRENTAPP 推送
                Thread.sleep(1_000)
            }
        }
        val ch = IbChannel("127.0.0.1", server.localPort)
        val apps = java.util.concurrent.CopyOnWriteArrayList<String>()
        ch.onCurrentApp = { apps.add(it) }
        assertTrue(ch.connect(timeoutMs = 2000), "connect failed — zero-checksum hello response rejected")
        assertEquals(IbChannel.State.READY, ch.state)
        assertEquals(329, ch.serverVer)
        Thread.sleep(500) // 等 reader 消费 cur_app 推送
        assertEquals(listOf("com.test.app"), apps, "cur_app push dropped")
        assertEquals(IbChannel.State.READY, ch.state, "channel killed by post-handshake zero-checksum frame")
        ch.disconnect()
    }

    @Test
    fun probeExchangesHelloThenClosesWithoutStartingAnIbSession() {
        server.soTimeout = 2000
        val probe = FutureTask { probeIbHello("127.0.0.1", timeoutMs = 2000, port = server.localPort) }
        thread(isDaemon = true) { probe.run() }
        server.accept().use { socket ->
            socket.soTimeout = 2000
            val input = DataInputStream(socket.getInputStream())
            assertEquals(IbConst.MAGIC, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(IbConst.REQ_HELLO, input.readInt())
            val reserve = input.readInt()
            assertEquals(reserve, input.readInt())

            writeFrameRealTv(socket, IbConst.RSP_MASK or IbConst.REQ_HELLO, """{"ver":"3.29", "sid":123}""")
            assertEquals(IbHello(version = "3.29", sessionId = "123"), probe.get(2, TimeUnit.SECONDS))
            assertEquals(-1, input.read())
        }
    }
}
