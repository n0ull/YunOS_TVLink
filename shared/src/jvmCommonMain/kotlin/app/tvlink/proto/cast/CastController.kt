package app.tvlink.proto.cast

import app.tvlink.proto.idc.jsonEscape
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * YunOS media-projection control channel — HTTP/1.1-style text over a TCP long
 * connection to the TV (default port 13520). See docs/re/04.
 */
class CastController(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        /** 投屏控制通道默认端口（ddh/mDNS 均未提供时 AppViewModel 兜底依次试 DEFAULT_PORT/13521）。 */
        const val DEFAULT_PORT = 13520

        /** 控制通道报文体保留上限（字符）：防对端伪造 content-length 强制大分配。 */
        private const val MAX_BODY_CHARS = 64 * 1024

        /** 超出保留上限部分的丢弃读取块大小。 */
        private const val DISCARD_CHUNK_CHARS = 8 * 1024
    }

    enum class State { DISCONNECTED, CONNECTED }

    enum class PlayState { PREPARED, PLAYING, PAUSED, LOADING, STOPPED, COMPLETED, ERROR, OCCUPIED, UNKNOWN }

    data class PlaybackInfo(
        val name: String = "",
        val duration: Long = 0,
        val position: Long = 0,
        val volume: Int = 0,
        val rate: Int = 0,
        val state: String = "",
    )

    @Volatile
    var state = State.DISCONNECTED
        private set

    /** (state, durationMs, positionMs, volume, rate);事件推送缺音量/倍速时为 -1/0(调用方保持旧值)。 */
    var onEvent: ((PlayState, duration: Long, position: Long, volume: Int, rate: Int) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    private val sessionId: String = UUID.randomUUID().toString()
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val sendLock = Any()

    fun connect(timeoutMs: Int = 10_000): Boolean {
        disconnect()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            socket = s
            out = s.getOutputStream()
            setState(State.CONNECTED)
            startReader()
            true
        } catch (e: Exception) {
            System.err.println("CastController: connect failed: ${e.message}")
            false
        }
    }

    fun setMedia(
        type: String,
        url: String,
        title: String,
        thumbnail: String? = null,
        startPosition: Long = 0,
    ): Boolean {
        val body =
            buildString {
                append("{\"content_url\":\"").append(jsonEscape(url)).append("\",")
                append("\"content_name\":\"").append(jsonEscape(title)).append("\",")
                if (thumbnail != null) append("\"thumbnail_url\":\"").append(jsonEscape(thumbnail)).append("\",")
                append("\"exclusive\":true,\"start_position\":").append(startPosition).append("}")
            }
        return request("POST", "/setmedia", body, extraHeaders = mapOf("yunos-mediatype" to type))
    }

    fun play() = request("POST", "/play").also { if (it) startPolling() }

    fun pause() = request("POST", "/pause")

    fun stop() = request("POST", "/stop").also { if (it) stopPolling() }

    fun seek(ms: Long) = request("POST", "/seek?value=$ms")

    fun volume(v: Int) = request("POST", "/volume?value=$v")

    /** 倍速（docs/re/04 §4 `POST /rate?value=<N>`）。TV 固件只接受整数档位，传小数会挂死连接。 */
    fun rate(r: Int) = request("POST", "/rate?value=$r")

    data class ServerInfo(
        val features: String = "",
        val protocolVers: String = "",
        val serverVers: String = "",
        val serverCode: String = "",
        val displayName: String = "",
    )

    /** GET /server-info（docs/re/04 §4）：固件投屏服务的能力与版本，连接后取一次供诊断/关于页。 */
    fun serverInfo(): ServerInfo? {
        val resp = requestRaw("GET", "/server-info", null) ?: return null
        if (!resp.first.startsWith("200")) return null

        fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(resp.second)?.groupValues?.get(1) ?: ""
        return ServerInfo(
            features = str("features"),
            protocolVers = str("protocol_vers"),
            serverVers = str("server_vers"),
            serverCode = str("server_code"),
            displayName = str("display_name"),
        )
    }

    private fun playbackInfo(): PlaybackInfo? {
        val resp = requestRaw("GET", "/playback-info", null)
        if (resp == null || !resp.first.startsWith("200")) return null
        val j = resp.second

        fun num(k: String) =
            Regex("\"$k\"\\s*:\\s*\"?(-?\\d+)\"?")
                .find(j)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull() ?: 0L

        fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(j)?.groupValues?.get(1) ?: ""
        return PlaybackInfo(
            name = str("name"),
            duration = num("duration"),
            position = num("position"),
            volume = num("volume").toInt(),
            rate =
                Regex("\"rate\"\\s*:\\s*\"?([0-9]+)\"?")
                    .find(j)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0,
            state = str("state"),
        )
    }

    private fun request(
        method: String,
        uri: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Boolean {
        val resp = requestRaw(method, uri, body, extraHeaders)
        return resp != null && resp.first.startsWith("200")
    }

    /** Serializes request/response cycles so the poller and UI actions can't cross answers. */
    private val reqLock = Any()

    /** Returns (statusCode, body) or null on IO error. */
    private fun requestRaw(
        method: String,
        uri: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<String, String>? =
        synchronized(reqLock) {
            val o = out ?: return null
            val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val sb = StringBuilder()
            sb
                .append(method)
                .append(' ')
                .append(uri)
                .append(" HTTP/1.1\r\n")
            sb.append("yunos-device-id: tvlink\r\n")
            sb.append("yunos-session-id: ").append(sessionId).append("\r\n")
            for ((k, v) in extraHeaders) {
                sb
                    .append(k)
                    .append(": ")
                    .append(v)
                    .append("\r\n")
            }
            sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
            // Arm BEFORE writing so the reader thread can never discard our response (H2 fix).
            respQueue.clear()
            waitingResp = true
            val resp =
                try {
                    synchronized(sendLock) {
                        o.write(sb.toString().toByteArray(Charsets.UTF_8))
                        if (bodyBytes.isNotEmpty()) o.write(bodyBytes)
                        o.flush()
                    }
                    respQueue.poll(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    System.err.println("CastController: request failed: ${e.message}")
                    null
                } finally {
                    waitingResp = false
                }
            return@synchronized resp
        }

    private val respQueue = ArrayBlockingQueue<Pair<String, String>>(1)

    @Volatile
    private var waitingResp = false

    private val playStateByName =
        mapOf(
            "prepared" to PlayState.PREPARED,
            "playing" to PlayState.PLAYING,
            "paused" to PlayState.PAUSED,
            "loading" to PlayState.LOADING,
            "stopped" to PlayState.STOPPED,
            "completed" to PlayState.COMPLETED,
            "error" to PlayState.ERROR,
            "occupied" to PlayState.OCCUPIED,
        )

    private fun startReader() {
        Thread(
            {
                val s = socket ?: return@Thread
                try {
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    while (state == State.CONNECTED) {
                        if (!readMessage(reader)) break
                    }
                } catch (e: Exception) {
                    System.err.println("CastController: reader failed: ${e.message}")
                }
                if (state != State.DISCONNECTED) disconnect()
            },
            "cast-reader",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Reads one HTTP-style message off [reader]. Returns false on EOF/超限报文, true to keep reading. */
    private fun readMessage(reader: BufferedReader): Boolean {
        val startLine = reader.readLine() ?: return false
        if (startLine.isBlank()) return true
        val headers = readHeaders(reader)
        val clHeader = headers["content-length"]
        val parsedLen = clHeader?.toLongOrNull()
        // content-length 协议违规（负值/非数字/超 Int 范围如 99999999999）：按 0 处理会把随后的
        // body 字节留在流中误解析为下一条消息（永久失步且通道不关闭）——交付空 body 解开
        // 在途请求后关通道快速失败，与超限同策略。header 缺省按 0（TV 响应均带 CL，仅容错）。
        if (clHeader != null && (parsedLen == null || parsedLen < 0L || parsedLen > Int.MAX_VALUE)) {
            handleMessage(startLine, "")
            return false
        }
        val rawLen = parsedLen?.toInt() ?: 0
        val len = rawLen.coerceAtMost(MAX_BODY_CHARS)
        // null = 对端声明 len 却提前 EOF（短 body）：帧已损坏，交付截断体会污染解析——关通道
        val body = readBody(reader, len, rawLen) ?: return false
        handleMessage(startLine, body)
        // 超限报文视为对端异常：本次响应已交付（流同步），关闭通道——重连由上层 ensureAlive/onResume 补建
        if (rawLen > MAX_BODY_CHARS) return false
        return true
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            line = reader.readLine()
        }
        return headers
    }

    /**
     * 读 [keep] 个字符进 body（上界由调用方 coerceAtMost(MAX_BODY_CHARS) 保证，
     * 下界由调用方 rawLen<0 提前返回保证——负值不会到达这里）；
     * 剩余 total-keep 个字符用复用缓冲丢弃——维持流同步，让本次响应干净交付，
     * 之后由调用方关闭连接（超限即对端异常，不再继续解析）。
     * 返回 null = 短 body（对端声明 total 却提前 EOF）：帧损坏，调用方据此关通道。
     */
    private fun readBody(
        reader: BufferedReader,
        keep: Int,
        total: Int,
    ): String? {
        val bodyChars = CharArray(keep)
        var read = 0
        while (read < keep) {
            val n = reader.read(bodyChars, read, keep - read)
            if (n < 0) return null
            read += n
        }
        // 超出保留上限的部分读取后丢弃；缓冲分配一次复用（超大 total 下逐次新建会产生 GB 级 churn）
        var left = total - keep
        if (left > 0) {
            val discard = CharArray(DISCARD_CHUNK_CHARS)
            while (left > 0) {
                val n = reader.read(discard, 0, minOf(discard.size, left))
                if (n < 0) return null
                left -= n
            }
        }
        return String(bodyChars, 0, read)
    }

    private fun handleMessage(
        startLine: String,
        body: String,
    ) {
        when {
            startLine.startsWith("HTTP/1.1") -> handleResponse(startLine, body)
            startLine.startsWith("POST /event") -> handleEvent(startLine)
        }
    }

    /** Response to a request we sent. */
    private fun handleResponse(
        startLine: String,
        body: String,
    ) {
        val code = startLine.split(' ').getOrNull(1) ?: ""
        if (waitingResp) respQueue.offer(code to body)
    }

    private fun handleEvent(startLine: String) {
        val q = startLine.substringAfter('?', "")
        val params =
            q
                .split('&')
                .mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
                }.toMap()
        val st = playStateByName[params["state"]] ?: PlayState.UNKNOWN
        onEvent?.invoke(
            st,
            params["duration"]?.toLongOrNull() ?: 0,
            params["position"]?.toLongOrNull() ?: 0,
            params["volume"]?.toIntOrNull() ?: -1,
            0, // /event 推送不含倍速
        )
        ackEvent()
    }

    private fun ackEvent() {
        val o = out ?: return
        synchronized(sendLock) {
            try {
                o.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    @Volatile
    private var poller: Thread? = null

    /**
     * The tested TV firmware (server_vers 3.2.0) never pushes POST /event — playback
     * state/duration/position come from polling GET /playback-info (~1/s while playing).
     */
    private fun startPolling() {
        if (poller?.isAlive == true) return
        poller =
            Thread(
                {
                    while (state == State.CONNECTED) {
                        playbackInfo()?.let { info ->
                            onEvent?.invoke(
                                playStateByName[info.state] ?: PlayState.UNKNOWN,
                                info.duration,
                                info.position,
                                info.volume,
                                info.rate,
                            )
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                },
                "cast-poll",
            ).apply {
                isDaemon = true
                start()
            }
    }

    private fun stopPolling() {
        poller?.interrupt()
        poller = null
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun disconnect() {
        stopPolling()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        setState(State.DISCONNECTED)
    }
}
