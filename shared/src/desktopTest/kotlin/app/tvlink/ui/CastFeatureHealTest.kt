package app.tvlink.ui

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 投屏通道死亡自愈回归(用户真机报告:FLAC 投屏失败后,后续音频全部失败、偶尔成功)。
 * 旧行为:TV 杀会话/半开后,channel 判死但 CastFeature 永不重建——awaitChannel 不看 state,
 * 死通道被照常返回,setMedia 每次必败,直到重启/重连 IDC(=「偶尔成功」)。
 * 修复:file() 遇死通道先重建再发;setMedia 失败且通道已死时自愈重试一次。
 * 测试 scope 直接用 Dispatchers.IO（无 DI 框架），故抑制 InjectDispatcher。
 */
@Suppress("InjectDispatcher")
class CastFeatureHealTest {
    private class FakeCastServer {
        val server = ServerSocket(0)
        val connCount = AtomicInteger(0)
        val setMediaCount = AtomicInteger(0)
        val firstSetMediaSeen = CountDownLatch(1)
        val playSeen = CountDownLatch(1)

        @Volatile
        private var currentSock: Socket? = null

        fun start() {
            thread(isDaemon = true) {
                while (true) {
                    val s =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@thread
                        }
                    connCount.incrementAndGet()
                    currentSock = s
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        fun killCurrent() {
            runCatching { currentSock?.close() }
        }

        /** 读一个请求(起始行+头+body 全消耗);返回起始行,EOF 返回 null。 */
        private fun readRequest(reader: BufferedReader): String? {
            var line = reader.readLine() ?: return null
            while (line.isBlank()) line = reader.readLine() ?: return null
            var contentLen = 0
            while (true) {
                val h = reader.readLine()
                if (h.isNullOrEmpty()) break
                if (h.lowercase().startsWith("content-length:")) {
                    contentLen = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            var left = contentLen
            val buf = CharArray(4096)
            while (left > 0) {
                val n = reader.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                left -= n
            }
            return line
        }

        private fun serve(s: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val out = s.getOutputStream()
                while (true) {
                    val start = readRequest(reader) ?: return
                    if (start.startsWith("POST /setmedia") && setMediaCount.incrementAndGet() == 1) {
                        // 已收到完整请求，但关闭连接且绝不返回响应：强制走 setMedia 失败后的重建重试。
                        firstSetMediaSeen.countDown()
                        s.close()
                        return
                    }
                    if (start.startsWith("POST /play")) playSeen.countDown()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }

        fun stop() = server.close()
    }

    @Test
    fun deadChannelSelfHealsOnFileCast() {
        val fake = FakeCastServer()
        fake.start()
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val feat = CastFeature(scope) { notices.add(it) }
        val f = File.createTempFile("cast-heal", ".png").apply { writeBytes(ByteArray(16)) }
        try {
            feat.onHostConnected("127.0.0.1", fake.server.localPort)
            var waited = 0L
            while (
                (feat.ui !is CastFeature.CastUiState.Ready || feat.mediaServerUrl.isEmpty()) &&
                waited < 5_000
            ) {
                Thread.sleep(50)
                waited += 50
            }
            assertTrue(feat.ui is CastFeature.CastUiState.Ready, "channel never became ready")
            assertTrue(feat.mediaServerUrl.isNotEmpty(), "media server never became ready")
            assertEquals(1, fake.connCount.get(), "expected exactly one connection")

            // awaitChannel 必须拿到最初仍判定为 CONNECTED 的通道；首个 setMedia 才杀死它。
            // 因而只有「setMedia 无有效响应 → 重建 → 重试」成功才会触发 play。
            feat.file(f.absolutePath, "t.png", "image")

            assertTrue(
                fake.firstSetMediaSeen.await(5, TimeUnit.SECONDS),
                "first setmedia never reached the original live channel",
            )
            assertTrue(
                fake.playSeen.await(10, TimeUnit.SECONDS),
                "setmedia retry did not reach play: connections=${fake.connCount.get()}, " +
                    "setmedia=${fake.setMediaCount.get()}, ui=${feat.ui}, notices=$notices",
            )
            assertEquals(2, fake.setMediaCount.get(), "setmedia was not retried exactly once")
            assertEquals(2, fake.connCount.get(), "channel was not rebuilt for the setmedia retry")
            assertTrue(notices.none { it.contains("投屏失败") }, "unexpected failure notice: $notices")
            assertTrue(notices.none { it.contains("未就绪") }, "unexpected not-ready notice: $notices")
        } finally {
            f.delete()
            feat.destroy()
            scope.cancel()
            fake.stop()
        }
    }

    @Test
    fun disconnectedTargetDoesNotHealOnFileCast() {
        val fake = FakeCastServer()
        fake.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rejected = CountDownLatch(1)
        val feat =
            CastFeature(scope) { notice ->
                if (notice.contains("未就绪")) rejected.countDown()
            }
        val file = File.createTempFile("cast-revoked", ".png").apply { writeBytes(ByteArray(16)) }
        try {
            feat.onHostConnected("127.0.0.1", fake.server.localPort)
            var waited = 0L
            while (feat.ui !is CastFeature.CastUiState.Ready && waited < 5_000) {
                Thread.sleep(50)
                waited += 50
            }
            assertTrue(feat.ui is CastFeature.CastUiState.Ready)
            assertEquals(1, fake.connCount.get())

            fake.killCurrent()
            feat.onDisconnected()
            feat.file(file.absolutePath, "revoked.png", "image")
            assertTrue(rejected.await(5, TimeUnit.SECONDS), "revoked cast did not finish as not-ready")

            assertEquals(1, fake.connCount.get(), "revoked target healed a Cast channel")
            assertTrue(feat.ui is CastFeature.CastUiState.Unavailable)
        } finally {
            file.delete()
            feat.destroy()
            scope.cancel()
            fake.stop()
        }
    }
}
