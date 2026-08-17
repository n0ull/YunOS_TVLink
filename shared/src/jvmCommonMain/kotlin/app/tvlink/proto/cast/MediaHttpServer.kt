package app.tvlink.proto.cast

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Embedded HTTP server the TV pulls media from (NanoHTTPD-style).
 * Binds from 8192 upward; serves only registered ids; supports Range / 206.
 * See docs/re/04.
 */
class MediaHttpServer {
    data class Entry(
        val file: File,
        val mime: String,
    )

    private val registry = ConcurrentHashMap<String, Entry>()

    @Volatile
    var port = 0
        private set

    @Volatile
    var baseUrl: String = ""
        private set

    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** 仅服务此来源 IP（已连 TV）；null 不过滤。不可信网段下防同网段主机拉走投屏文件。 */
    @Volatile
    private var allowedClientIp: String? = null

    @Volatile
    private var running = false

    /** Register [file] under [id] (e.g. "video-item-12"); returns the URL path segment. */
    fun register(
        id: String,
        file: File,
        mime: String = mimeOf(file.name),
    ): String {
        registry[id] = Entry(file, mime)
        return id
    }

    fun unregister(id: String) = registry.remove(id)

    fun clear() = registry.clear()

    fun urlFor(id: String) = "$baseUrl/$id"

    fun start(
        localIp: String,
        startPort: Int = 8192,
        allowedClientIp: String? = null,
    ): Boolean {
        stop()
        var p = startPort
        var ss: ServerSocket? = null
        while (p < startPort + 100) {
            try {
                ss = ServerSocket(p)
                break
            } catch (e: Exception) {
                System.err.println("MediaHttpServer: bind port $p failed: ${e.message}")
                p++
            }
        }
        if (ss == null) return false
        serverSocket = ss
        port = p
        baseUrl = "http://$localIp:$p"
        this.allowedClientIp = allowedClientIp
        running = true
        // ponytail: TV is the only client and uses Connection: close; 4 threads ample
        pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "media-http-io").apply { isDaemon = true } }
        Thread({ acceptLoop() }, "media-http").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        registry.clear()
        allowedClientIp = null
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        var backoffMs = 0L
        while (running) {
            try {
                val client = ss.accept()
                backoffMs = 0
                pool?.execute { serve(client) }
            } catch (e: Exception) {
                System.err.println("MediaHttpServer: accept failed: ${e.message}")
                backoffMs = sleepBackoff(backoffMs)
            }
        }
    }

    /** Exponential accept-failure backoff (10ms doubling to 1s); no-op once stopped. */
    private fun sleepBackoff(backoffMs: Long): Long {
        if (!running) return backoffMs
        val next = if (backoffMs == 0L) 10L else minOf(backoffMs * 2, 1000)
        Thread.sleep(next)
        return next
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            val allowed = allowedClientIp
            if (allowed != null && client.inetAddress.hostAddress != allowed) return close(client)
            val inp = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = inp.readLine() ?: return close(client)
            val parts = requestLine.split(' ')
            if (parts.size < 2) return close(client)
            val path = parts[1].removePrefix("/")
            val range = readRange(inp)
            if (path.contains("..")) return close(client)
            val entry = registry[path] ?: return close(client)
            if (!entry.file.exists()) return close(client)
            val total = entry.file.length()
            writeResponse(client, entry, total, range.present, resolveRange(range, total))
            close(client)
            // 完整 200 供片后注销：条目不常驻，收窄投屏文件暴露窗口（私人文件）。
            // 206 分段保留——TV 拉流靠多次 Range，注销会断播；失败重试走 Range 亦不受影响。
            if (!range.present) registry.remove(path, entry)
        } catch (e: Exception) {
            System.err.println("MediaHttpServer: serve failed: ${e.message}")
            close(client)
        }
    }

    /** Parsed `Range: bytes=a-b` header; absent/invalid yields [present] = false. */
    private class HttpRange(
        val present: Boolean,
        val start: Long,
        val end: Long,
    )

    private fun readRange(inp: BufferedReader): HttpRange {
        var rangeStart = -1L
        var rangeEnd = -1L
        var hasRange = false
        var line = inp.readLine()
        while (line != null && line.isNotEmpty()) {
            if (line.startsWith("Range:", ignoreCase = true)) {
                val m = Regex("bytes=(\\d*)-(\\d*)").find(line)
                if (m != null) {
                    hasRange = true
                    rangeStart = m.groupValues[1].toLongOrNull() ?: -1
                    rangeEnd = m.groupValues[2].toLongOrNull() ?: -1
                }
            }
            line = inp.readLine()
        }
        return HttpRange(hasRange, rangeStart, rangeEnd)
    }

    /** Resolve [range] against [total] bytes into an inclusive from..to span. */
    private fun resolveRange(
        range: HttpRange,
        total: Long,
    ): LongRange {
        // Suffix range "bytes=-N": last N bytes of the file.
        val suffix = range.present && range.start < 0 && range.end >= 0
        val rangeStart = if (suffix) maxOf(0, total - range.end) else range.start
        val rangeEnd = if (suffix) -1L else range.end
        val from = if (range.present) maxOf(rangeStart, 0) else 0
        val to = if (rangeEnd >= 0 && rangeEnd < total) rangeEnd else total - 1
        return from..to
    }

    private fun writeResponse(
        client: Socket,
        entry: Entry,
        total: Long,
        partial: Boolean,
        span: LongRange,
    ) {
        val from = span.first
        val to = span.last
        val out: OutputStream = client.getOutputStream()
        val status = if (partial) "206 Partial Content" else "200 OK"
        val headers =
            StringBuilder()
                .append("HTTP/1.1 ")
                .append(status)
                .append("\r\n")
                .append("Content-Type: ")
                .append(entry.mime)
                .append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ")
                .append(to - from + 1)
                .append("\r\n")
        if (partial) {
            headers
                .append(
                    "Content-Range: bytes ",
                ).append(from)
                .append('-')
                .append(to)
                .append('/')
                .append(total)
                .append("\r\n")
        }
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
        pumpFile(out, entry.file, from, to)
        out.flush()
    }

    /**
     * 把 [file] 的 from..to 字节写入 [out]。优先 FileChannel.transferTo(sendfile 零拷贝,
     * 大文件吞吐显著优于流拷贝)；内核/平台不支持时从断点回退到 256KB 缓冲拷贝。
     */
    private fun pumpFile(
        out: OutputStream,
        file: File,
        from: Long,
        to: Long,
    ) {
        FileInputStream(file).use { fis ->
            var pos = from
            var remaining = to - from + 1
            var zeroCopyFailed = false
            try {
                val dest =
                    java.nio.channels.Channels
                        .newChannel(out)
                while (remaining > 0) {
                    val n = fis.channel.transferTo(pos, remaining, dest)
                    if (n <= 0L) throw java.io.IOException("transferTo returned $n")
                    pos += n
                    remaining -= n
                }
            } catch (e: Exception) {
                System.err.println("MediaHttpServer: zero-copy failed, fallback to buffer copy: ${e.message}")
                zeroCopyFailed = true
            }
            if (zeroCopyFailed) {
                fis.channel.position(pos)
                val buf = ByteArray(COPY_BUFFER_SIZE)
                while (remaining > 0) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private fun close(c: Socket) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024

        private val mimeTypes =
            mapOf(
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "png" to "image/png",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "mp4" to "video/mp4",
                "mkv" to "video/x-matroska",
                "avi" to "video/x-msvideo",
                "mov" to "video/quicktime",
                "mp3" to "audio/mpeg",
                "flac" to "audio/flac",
                "wav" to "audio/wav",
                "m4a" to "audio/mp4",
                "aac" to "audio/aac",
            )

        fun mimeOf(name: String): String =
            mimeTypes[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }
}
