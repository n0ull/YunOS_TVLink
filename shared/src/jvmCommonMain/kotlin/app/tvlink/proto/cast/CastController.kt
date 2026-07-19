package app.tvlink.proto.cast

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
    private val port: Int = 13520,
) {
    enum class State { DISCONNECTED, CONNECTED }

    enum class PlayState { PREPARED, PLAYING, PAUSED, LOADING, STOPPED, COMPLETED, ERROR, OCCUPIED, UNKNOWN }

    data class PlaybackInfo(
        val name: String = "",
        val duration: Long = 0,
        val position: Long = 0,
        val volume: Int = 0,
        val rate: Float = 1f,
    )

    @Volatile
    var state = State.DISCONNECTED
        private set
    var onEvent: ((PlayState, duration: Long, position: Long) -> Unit)? = null
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
                append("{\"content_url\":\"").append(url.jsonEsc()).append("\",")
                append("\"content_name\":\"").append(title.jsonEsc()).append("\",")
                if (thumbnail != null) append("\"thumbnail_url\":\"").append(thumbnail.jsonEsc()).append("\",")
                append("\"exclusive\":true,\"start_position\":").append(startPosition).append("}")
            }
        return request("POST", "/setmedia", body, extraHeaders = mapOf("yunos-mediatype" to type))
    }

    fun play() = request("POST", "/play")

    fun pause() = request("POST", "/pause")

    fun stop() = request("POST", "/stop")

    fun seek(ms: Long) = request("POST", "/seek?value=$ms")

    fun volume(v: Int) = request("POST", "/volume?value=$v")

    fun rate(r: Float) = request("POST", "/rate?value=$r")

    fun zoom(
        scale: Float,
        cx: Float,
        cy: Float,
    ) = request("POST", "/zoom?scale=$scale&cx=$cx&cy=$cy")

    fun preload(url: String) = request("POST", "/preload", "{\"content_url\":\"${url.jsonEsc()}\"}")

    fun playbackInfo(): PlaybackInfo? {
        val resp = requestRaw("GET", "/playback-info", null)
        if (resp == null || !resp.first.startsWith("200")) return null
        val j = resp.second

        fun num(k: String) =
            Regex("\"$k\"\\s*:\\s*\"?(\\d+)\"?")
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
                Regex("\"rate\"\\s*:\\s*\"?([0-9.]+)\"?")
                    .find(j)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull() ?: 1f,
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

    /** Returns (statusCode, body) or null on IO error. */
    private fun requestRaw(
        method: String,
        uri: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<String, String>? {
        val o = out ?: return null
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val sb = StringBuilder()
        sb
            .append(method)
            .append(' ')
            .append(uri)
            .append(" HTTP/1.1\r\n")
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
        return resp
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

    /** Reads one HTTP-style message off [reader]. Returns false on EOF, true to keep reading. */
    private fun readMessage(reader: BufferedReader): Boolean {
        val startLine = reader.readLine() ?: return false
        if (startLine.isBlank()) return true
        val headers = readHeaders(reader)
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        handleMessage(startLine, readBody(reader, len))
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

    private fun readBody(
        reader: BufferedReader,
        len: Int,
    ): String {
        val bodyChars = CharArray(len)
        var read = 0
        while (read < len) {
            val n = reader.read(bodyChars, read, len - read)
            if (n < 0) break
            read += n
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

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        setState(State.DISCONNECTED)
    }

    private fun String.jsonEsc() = replace("\\", "\\\\").replace("\"", "\\\"")
}
