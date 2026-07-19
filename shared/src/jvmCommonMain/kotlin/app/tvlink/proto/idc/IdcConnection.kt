package app.tvlink.proto.idc

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One IDC control session to a TV/box (TCP 13510).
 * Blocking socket + reader thread; callbacks are invoked on the reader thread —
 * implementations must hop to a UI/IO dispatcher as needed.
 */
class IdcConnection(
    val host: String,
    val port: Int = IdcConst.TCP_PORT,
) {
    enum class State { DISCONNECTED, CONNECTING, ESTABLISHED }

    data class ModuleInfo(
        val id: Int,
        val name: String,
        val ver: Int,
        val extProp: String,
        val online: Boolean,
    )

    data class DeviceInfo(
        val ip: String,
        val name: String = "",
        val model: String = "",
        val uuid: String = "",
        val os: String = "",
        val osVer: String = "",
        val udpPort: Int = 0,
        val ddhParams: MutableMap<String, ByteArray> = mutableMapOf(),
    )

    @Volatile
    var state = State.DISCONNECTED
        private set

    @Volatile
    var deviceInfo: DeviceInfo? = null
        private set

    @Volatile
    var sessionKey: ByteArray? = null
        private set

    /** module id -> info, as reported by the TV. Concurrent because reader thread writes and UI threads read. */
    val modules = java.util.concurrent.ConcurrentHashMap<Int, ModuleInfo>()

    var onStateChanged: ((State) -> Unit)? = null
    var onModulesChanged: (() -> Unit)? = null

    /** unmatched packets: IME events, screenshot resp, dev-name updates */
    var onPacket: ((IdcPacket) -> Unit)? = null
    var onVConnData: ((moduleId: Int, payload: ByteArray) -> Unit)? = null

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var dataIn: DataInputStream? = null

    @Volatile
    private var connKey = IdcConst.UNASSIGNED_KEY
    private val sendLock = Any()
    private var scheduler: ScheduledExecutorService? = null
    private var hbFuture: ScheduledFuture<*>? = null
    private val hbSeq = AtomicInteger(1)

    @Volatile
    private var lastHbAck = 0

    /** Connect and perform the login handshake. Returns true on ESTABLISHED. */
    fun connect(
        login: LoginReq,
        timeoutMs: Int = 8000,
    ): Boolean {
        if (state != State.DISCONNECTED) close()
        setState(State.CONNECTING)
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            socket = s
            out = s.getOutputStream()
            dataIn = DataInputStream(s.getInputStream())

            sendRaw(login)
            if (!awaitLogin(System.currentTimeMillis() + timeoutMs)) {
                close()
                return false
            }
            s.soTimeout = 0
            setState(State.ESTABLISHED)
            startHeartbeat()
            startReader()
            true
        } catch (e: Exception) {
            System.err.println("IdcConnection: connect failed: ${e.message}")
            close()
            false
        }
    }

    /** Read packets until a LoginResp arrives or [deadline] passes; returns true once the login handshake completed. */
    private fun awaitLogin(deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            val p = readPacket() ?: return false
            when (p) {
                is LoginEncryptionResp -> {
                    // ver=0 sessions shouldn't get this; ignore and keep waiting for LoginResp
                }

                is LoginResp -> {
                    applyLoginResp(p)
                    return true
                }

                else -> onPacket?.let { it(p) }
            }
        }
        return false
    }

    private fun applyLoginResp(p: LoginResp) {
        connKey = p.connKey
        sessionKey = null // ver=0: plain session
        deviceInfo =
            DeviceInfo(
                ip = host,
                name = p.devName,
                model = p.devModel,
                uuid = p.devUuid,
                os = p.devOs,
                osVer = p.devOsVer,
                udpPort = p.udpPort,
                ddhParams = p.ddhParams,
            )
    }

    /** Lightweight DETECT probe (used by discovery): login DETECT, read LoginResp, disconnect. */
    fun detect(timeoutMs: Int = 1500): DeviceInfo? {
        val req = LoginReq(loginType = IdcLoginType.DETECT)
        return if (connect(req, timeoutMs)) {
            val info = deviceInfo
            close()
            info
        } else {
            null
        }
    }

    fun send(packet: IdcPacket) {
        if (state == State.DISCONNECTED) return
        packet.key = connKey
        sendRaw(packet)
    }

    private fun sendRaw(packet: IdcPacket) {
        val o = out ?: return
        val buf = packet.encode(sessionKey)
        synchronized(sendLock) {
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            o.write(arr)
            o.flush()
        }
    }

    fun openVConn(moduleId: Int) = send(VConnSyn(moduleId))

    fun sendVConnData(
        moduleId: Int,
        payload: ByteArray,
    ) = send(VConnData(moduleId, payload))

    fun closeVConn(moduleId: Int) = send(VConnFin(moduleId))

    fun moduleIdByName(name: String): Int? = modules.values.firstOrNull { it.name == name && it.online }?.id

    private fun startHeartbeat() {
        val sched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "idc-hb").apply { isDaemon = true } }
        scheduler = sched
        hbFuture =
            sched.scheduleWithFixedDelay(
                {
                    try {
                        val seq = hbSeq.get()
                        send(HeartBeat(seq))
                        // TV echoes the same seq; if it lags 2 beats behind, consider the link dead
                        if (seq - lastHbAck > 2) close()
                        hbSeq.incrementAndGet()
                    } catch (e: Exception) {
                        System.err.println("IdcConnection: heartbeat failed: ${e.message}")
                        close()
                    }
                },
                20,
                20,
                TimeUnit.SECONDS,
            )
    }

    @Volatile
    private var readerThread: Thread? = null

    private fun startReader() {
        val t =
            Thread(
                {
                    try {
                        while (state == State.ESTABLISHED) {
                            val p = readPacket() ?: break
                            dispatch(p)
                        }
                    } catch (e: Exception) {
                        // socket closed or IO error -> fall through to close
                        System.err.println("IdcConnection: reader failed: ${e.message}")
                    }
                    if (state != State.DISCONNECTED) close()
                },
                "idc-reader",
            )
        t.isDaemon = true
        readerThread = t
        t.start()
    }

    private fun dispatch(p: IdcPacket) {
        when (p) {
            is HeartBeat -> lastHbAck = p.seq
            is ModuleAvailability -> {
                if (p.online) {
                    modules[p.moduleId] =
                        ModuleInfo(p.moduleId, p.moduleName, p.moduleVer, p.moduleExtProp, true)
                } else {
                    modules.remove(p.moduleId)
                }
                onModulesChanged?.invoke()
            }

            is VConnSyn -> { // TV-initiated vconn: accept implicitly
            }

            is VConnData -> onVConnData?.invoke(p.moduleId, p.payload)
            is DevNameUpdate -> deviceInfo = deviceInfo?.copy(name = p.devName)
            else -> onPacket?.invoke(p)
        }
    }

    /** Read one full frame from the socket. Null on EOF/error. */
    private fun readPacket(): IdcPacket? {
        val inp = dataIn ?: return null
        return try {
            val header = ByteArray(IdcConst.HEADER_LEN)
            inp.readFully(header)
            val hb = java.nio.ByteBuffer.wrap(header)
            val magic = hb.int
            if (magic != IdcConst.MAGIC) return null
            hb.int // key
            hb.int // packetId
            val total = hb.int
            if (total < IdcConst.HEADER_LEN || total > 16 * 1024 * 1024) return null
            val body = ByteArray(total - IdcConst.HEADER_LEN)
            if (body.isNotEmpty()) inp.readFully(body)
            val frame = java.nio.ByteBuffer.allocate(total)
            frame.put(header)
            frame.put(body)
            frame.flip()
            IdcPacket.decode(frame, sessionKey)
        } catch (e: Exception) {
            System.err.println("IdcConnection: read failed: ${e.message}")
            null
        }
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun close() {
        if (state == State.DISCONNECTED && socket == null && scheduler == null) return
        hbFuture?.cancel(false)
        hbFuture = null
        scheduler?.shutdownNow()
        scheduler = null
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
        modules.clear()
        sessionKey = null
        connKey = IdcConst.UNASSIGNED_KEY
        setState(State.DISCONNECTED)
    }

    /** Alias for [close] — retained for call-site clarity when discarding the connection permanently. */
    fun shutdown() = close()
}
