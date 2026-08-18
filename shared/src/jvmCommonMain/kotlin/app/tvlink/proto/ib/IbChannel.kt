package app.tvlink.proto.ib

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * IB (InputBoost) fast input channel — TCP 3988, 20-byte big-endian header + text body.
 * See docs/re/02-remote-control.md.
 */

class IbChannel(
    private val host: String,
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
            s.connect(InetSocketAddress(host, IbConst.PORT), timeoutMs)
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
            val body = String(rsp.second, Charsets.UTF_8)
            helloId = Regex("\"sid\"\\s*:\\s*(\\d+)")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0
            val verStr = Regex("\"ver\"\\s*:\\s*\"([0-9.]+)\"").find(body)?.groupValues?.get(1)
            serverVer = parseVer(verStr)
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
        val buf = ByteBuffer.allocate(20 + body.size)
        buf.putInt(IbConst.MAGIC)
        buf.putInt(body.size)
        buf.putInt(type)
        buf.putInt(reserve)
        buf.putInt((body.size + reserve) xor helloId)
        buf.put(body)
        synchronized(sendLock) {
            try {
                o.write(buf.array())
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
            val header = ByteArray(20)
            inp.readFully(header)
            val b = ByteBuffer.wrap(header)
            if (b.int != IbConst.MAGIC) return null
            val size = b.int
            if (size < 0 || size > 1024 * 1024) return null
            val type = b.int
            b.int // reserve
            b.int // checksum
            // 2026-08-18 真机实测(ver 3.29):TV 下行所有帧恒
            // reserve=0/checksum=0(hello 应答、cur_app、moduleinfo 应答、keepalive
            // 应答均如此),从不按文档公式计算——接收侧任何 checksum 校验都会把每条
            // 下行帧误判损坏(f076f90 加的校验因此必杀通道)。校验已移除,帧界防护
            // 由 magic + size 范围检查承担;发送侧仍按公式 (size+reserve)^helloId
            // 计算,真机已验证接受。
            val body = ByteArray(size)
            if (size > 0) inp.readFully(body)
            type to body
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
