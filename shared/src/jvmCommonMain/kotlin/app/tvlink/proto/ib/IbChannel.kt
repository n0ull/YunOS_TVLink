package app.tvlink.proto.ib

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val FRAME_HEADER_SIZE = 20
private const val MAX_FRAME_BODY_SIZE = 1024 * 1024
private const val MAX_PROBE_BODY_SIZE = 1024

internal data class IbHello(
    val version: String,
    val sessionId: String,
)

/** One HELLO exchange with no steady-state reader, writer or keepalive. */
internal fun probeIbHello(
    host: String,
    timeoutMs: Int,
    port: Int = IbConst.PORT,
    isActive: () -> Boolean = { true },
): IbHello? {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            output.write(encodeFrame(IbConst.REQ_HELLO, ByteArray(0), Random().nextInt(), helloId = 0))
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val header = readHeader(input, MAX_PROBE_BODY_SIZE) ?: return null
            if (header.type != (IbConst.RSP_MASK or IbConst.REQ_HELLO) || !isActive()) return null
            parseHello(readBody(input, header.bodySize))
        }
    } catch (_: IOException) {
        null
    }
}

/**
 * IB (InputBoost) fast input channel — TCP 3988, 20-byte big-endian header + text body.
 * See docs/re/02-remote-control.md.
 */

class IbChannel(
    private val host: String,
    private val port: Int = IbConst.PORT,
) {
    enum class State { DISCONNECTED, CONNECTING, READY }

    @Volatile
    var state = State.DISCONNECTED
        private set

    @Volatile
    var serverVer = 0
        private set
    var onStateChanged: ((State) -> Unit)? = null
    var onCurrentApp: ((String) -> Unit)? = null

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var dataIn: DataInputStream? = null
    private var helloId = 0
    private val reserve = Random().nextInt()
    private val sendLock = Any()

    /** Bound by the remote owner and checked by the FIFO writer before each steady-state frame. */
    internal var writerGuard: (() -> Boolean)? = null

    @Volatile
    private var sendExecutor: ExecutorService? = null

    @Volatile
    private var readerRunning = false
    private var keepaliveThread: Thread? = null

    fun connect(timeoutMs: Int = 6000): Boolean {
        disconnect()
        // 重置 helloId：否则同实例二次 connect 携带旧 sid,握手期 checksum 门会被旧值
        // 重新武装,把新 hello 应答误判为损坏帧(当前调用方均新建实例,防御性复位)。
        helloId = 0
        setState(State.CONNECTING)
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            // 握手期读超时（照 IdcConnection.connect 模式）：对端收包不应答则快速失败，
            // 否则 readFrame 无限阻塞——connect 调用线程永久泄漏、IB 通道永不重试
            s.soTimeout = timeoutMs
            socket = s
            out = s.getOutputStream()
            dataIn = DataInputStream(s.getInputStream())
            // hello handshake
            sendFrame(IbConst.REQ_HELLO, ByteArray(0))
            val rsp = readFrame()
            if (rsp == null || rsp.first != (IbConst.RSP_MASK or IbConst.REQ_HELLO)) {
                disconnect()
                return false
            }
            val hello = parseHello(rsp.second)
            helloId = hello.sessionId.toIntOrNull() ?: 0
            serverVer = parseVer(hello.version)
            sendFrame(IbConst.REQ_MODULEINFO, ByteArray(0))
            // 对齐原 IbConn：建链后即发 DEFAULT 模式，消除「首帧前模式不确定」（docs/re/02 §2.1）
            sendFrame(IbConst.REQ_CHANGETYPE, "[${IbConst.CHANGETYPE_DEFAULT}]".toByteArray(Charsets.UTF_8))
            // 握手完成即复位：稳态 reader 依赖无限阻塞读，soTimeout 残留会把空闲通道误判超时
            s.soTimeout = 0
            sendExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ib-send").apply { isDaemon = true } }
            setState(State.READY)
            startReader()
            startKeepalive()
            true
        } catch (e: Exception) {
            System.err.println("IbChannel: connect failed: ${e.message}")
            disconnect()
            false
        }
    }

    fun changeType(type: Int) = sendBody(IbConst.REQ_CHANGETYPE, "[$type]")

    fun keyEvent(
        key: RcKey,
        down: Boolean,
    ) {
        if (key.needIb313 && serverVer < 313) return
        sendBody(IbConst.PROTO_MOUSE, "[${IbConst.EV_KEY},${key.ibVal},0,0,${if (down) 1 else 0}]")
    }

    fun keyClick(key: RcKey) {
        keyEvent(key, true)
        keyEvent(key, false)
    }

    fun mouseMove(
        dx: Int,
        dy: Int,
    ) = sendBody(IbConst.PROTO_MOUSE, "[${IbConst.EV_REL},0,$dx,$dy,0]")

    fun mouseClick() {
        sendBody(IbConst.PROTO_MOUSE, "[${IbConst.EV_KEY},${IbConst.BTN_LEFT},0,0,1]")
        sendBody(IbConst.PROTO_MOUSE, "[${IbConst.EV_KEY},${IbConst.BTN_LEFT},0,0,0]")
    }

    /** axis values already mapped to 0..255 (center 128) */
    fun joystick(axes: List<Pair<Int, Int>>) {
        val body = axes.joinToString(",", "[", "]") { "{\"axis\":${it.first},\"value\":${it.second}}" }
        sendBody(IbConst.PROTO_JOYSTICK, body)
    }

    fun accel(
        x: Int,
        y: Int,
        z: Int,
    ) = sendBody(IbConst.PROTO_G_SENSOR, "[$x,$y,$z]")

    fun gyro(
        x: Int,
        y: Int,
        z: Int,
    ) = sendBody(IbConst.PROTO_GYRO_SENSOR, "[$x,$y,$z]")

    /**
     * Queue an event frame. Writes run on a single background thread (FIFO preserved) —
     * socket IO on the Android main thread crashes with NetworkOnMainThreadException.
     */
    private fun sendBody(
        type: Int,
        body: String,
    ) {
        if (state != State.READY) return
        val ex = sendExecutor ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        try {
            ex.execute { sendFrame(type, bytes) }
        } catch (_: RejectedExecutionException) {
            // disconnected between the state check and execute — drop the event
        }
    }

    private fun sendFrame(
        type: Int,
        body: ByteArray,
    ) {
        val o = out ?: return
        val frame = encodeFrame(type, body, reserve, helloId)
        // Covers FIFO input and keepalive; once the permit is acquired, revocation cannot cancel this write.
        if (writerGuard?.invoke() == false) return
        synchronized(sendLock) {
            try {
                o.write(frame)
                o.flush()
            } catch (e: Exception) {
                System.err.println("IbChannel: send failed: ${e.message}")
                disconnect()
            }
        }
    }

    private fun readFrame(): Pair<Int, ByteArray>? {
        val inp = dataIn ?: return null
        return try {
            val header = readHeader(inp, MAX_FRAME_BODY_SIZE) ?: return null
            header.type to readBody(inp, header.bodySize)
        } catch (e: Exception) {
            System.err.println("IbChannel: read failed: ${e.message}")
            null
        }
    }

    /** Parse "x.yz" version string into an integer like x*100 + yz, without floating-point rounding. */
    private fun parseVer(ver: String?): Int {
        if (ver.isNullOrBlank()) return 0
        val parts = ver.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor =
            parts
                .getOrNull(1)
                ?.padEnd(2, '0')
                ?.take(2)
                ?.toIntOrNull() ?: 0
        return major * 100 + minor
    }

    private fun startReader() {
        readerRunning = true
        Thread(
            {
                while (readerRunning && state == State.READY) {
                    val f = readFrame() ?: break
                    if (f.first == IbConst.PROTO_CURRENTAPP) {
                        val app =
                            Regex("\"cur_app\"\\s*:\\s*\"([^\"]*)\"")
                                .find(String(f.second, Charsets.UTF_8))
                                ?.groupValues
                                ?.get(1)
                        if (app != null) onCurrentApp?.invoke(app)
                    } else if (f.first != (IbConst.RSP_MASK or IbConst.REQ_KEEPALIVE)) {
                        // 诊断:TV 主动下推的非预期帧(IME 排查 2026-07-25);keepalive 应答不记
                        System.err.println(
                            "IbChannel: unhandled frame type=${f.first} body=" +
                                String(f.second, Charsets.UTF_8).take(96),
                        )
                    }
                }
                if (state != State.DISCONNECTED) disconnect()
            },
            "ib-reader",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startKeepalive() {
        keepaliveThread =
            Thread(
                {
                    try {
                        Thread.sleep(10_000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    while (state == State.READY) {
                        sendFrame(IbConst.REQ_KEEPALIVE, ByteArray(0))
                        try {
                            Thread.sleep(15_000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                },
                "ib-keepalive",
            ).apply {
                isDaemon = true
                start()
            }
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun disconnect() {
        if (state == State.DISCONNECTED && socket == null) return
        readerRunning = false
        // shutdownNow：disconnect 后不再执行排队发送（socket 正在关闭，与 IdcConnection.close 一致）
        sendExecutor?.shutdownNow()
        sendExecutor = null
        keepaliveThread?.interrupt()
        try {
            dataIn?.close()
        } catch (_: Exception) {
        }
        dataIn = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        setState(State.DISCONNECTED)
    }
}

private data class IbFrameHeader(
    val type: Int,
    val bodySize: Int,
)

private fun encodeFrame(
    type: Int,
    body: ByteArray,
    reserve: Int,
    helloId: Int,
): ByteArray {
    val frame = ByteBuffer.allocate(FRAME_HEADER_SIZE + body.size)
    frame.putInt(IbConst.MAGIC)
    frame.putInt(body.size)
    frame.putInt(type)
    frame.putInt(reserve)
    frame.putInt((body.size + reserve) xor helloId)
    frame.put(body)
    return frame.array()
}

private fun readHeader(
    input: DataInputStream,
    maxBodySize: Int,
): IbFrameHeader? {
    val bytes = ByteArray(FRAME_HEADER_SIZE)
    input.readFully(bytes)
    val header = ByteBuffer.wrap(bytes)
    if (header.int != IbConst.MAGIC) return null
    val bodySize = header.int
    if (bodySize !in 0..maxBodySize) return null
    // The tested TV sends zero reserve and checksum on inbound frames (docs/re/02 §2.2).
    return IbFrameHeader(type = header.int, bodySize = bodySize)
}

private fun readBody(input: DataInputStream, size: Int): ByteArray = ByteArray(size).also { input.readFully(it) }

private val helloVersion = Regex("\"ver\"\\s*:\\s*\"([0-9.]+)\"")
private val helloSessionId = Regex("\"sid\"\\s*:\\s*(\\d+)")

private fun parseHello(bytes: ByteArray): IbHello {
    val body = String(bytes, Charsets.UTF_8)
    return IbHello(
        version = helloVersion.find(body)?.groupValues?.get(1).orEmpty(),
        sessionId = helloSessionId.find(body)?.groupValues?.get(1).orEmpty(),
    )
}
