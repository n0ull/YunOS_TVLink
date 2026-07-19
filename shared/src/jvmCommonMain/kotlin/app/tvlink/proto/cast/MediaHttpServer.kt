package app.tvlink.proto.cast

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP server the TV pulls media from (NanoHTTPD-style).
 * Binds from 8192 upward; serves only registered ids; supports Range / 206.
 * See docs/re/04.
 */
class MediaHttpServer {

    data class Entry(val file: File, val mime: String)

    private val registry = ConcurrentHashMap<String, Entry>()
    @Volatile var port = 0; private set
    @Volatile var baseUrl: String = ""; private set

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** Register [file] under [id] (e.g. "video-item-12"); returns the URL path segment. */
    fun register(id: String, file: File, mime: String = mimeOf(file.name)): String {
        registry[id] = Entry(file, mime)
        return id
    }

    fun unregister(id: String) = registry.remove(id)
    fun clear() = registry.clear()

    fun urlFor(id: String) = "$baseUrl/$id"

    fun start(localIp: String, startPort: Int = 8192): Boolean {
        stop()
        var p = startPort
        var ss: ServerSocket? = null
        while (p < startPort + 100) {
            try {
                ss = ServerSocket(p)
                break
            } catch (e: Exception) {
                p++
            }
        }
        if (ss == null) return false
        serverSocket = ss
        port = p
        baseUrl = "http://$localIp:$p"
        running = true
        Thread({ acceptLoop() }, "media-http").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        registry.clear()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        var backoffMs = 0L
        while (running) {
            try {
                val client = ss.accept()
                backoffMs = 0
                Thread({ serve(client) }, "media-http-io").apply { isDaemon = true; start() }
            } catch (e: Exception) {
                if (running) {
                    backoffMs = if (backoffMs == 0L) 10 else minOf(backoffMs * 2, 1000)
                    Thread.sleep(backoffMs)
                }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 10_000
            val inp = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = inp.readLine() ?: return close(client)
            val parts = requestLine.split(' ')
            if (parts.size < 2) return close(client)
            val path = parts[1].removePrefix("/")
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
            if (path.contains("..")) return close(client)
            val entry = registry[path] ?: return close(client)
            if (!entry.file.exists()) return close(client)

            val total = entry.file.length()
            // Suffix range "bytes=-N": last N bytes of the file.
            if (hasRange && rangeStart < 0 && rangeEnd >= 0) {
                rangeStart = maxOf(0, total - rangeEnd)
                rangeEnd = -1
            }
            val from = if (hasRange) maxOf(rangeStart, 0) else 0
            val to = if (rangeEnd >= 0 && rangeEnd < total) rangeEnd else total - 1
            val partial = hasRange
            val out: OutputStream = client.getOutputStream()
            val status = if (partial) "206 Partial Content" else "200 OK"
            val headers = StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(entry.mime).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(to - from + 1).append("\r\n")
            if (partial) headers.append("Content-Range: bytes ").append(from).append('-').append(to).append('/').append(total).append("\r\n")
            headers.append("Connection: close\r\n\r\n")
            out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            FileInputStream(entry.file).use { fis ->
                fis.channel.position(from)
                val buf = ByteArray(64 * 1024)
                var remaining = to - from + 1
                while (remaining > 0) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
            close(client)
        } catch (e: Exception) {
            close(client)
        }
    }

    private fun close(c: Socket) {
        try { c.close() } catch (_: Exception) {}
    }

    companion object {
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }
}
