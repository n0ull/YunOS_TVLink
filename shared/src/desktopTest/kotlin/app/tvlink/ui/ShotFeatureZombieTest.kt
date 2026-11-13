package app.tvlink.ui

import app.tvlink.device.ConnectionHistory
import app.tvlink.device.ConnectionMode
import app.tvlink.device.DeviceSelection
import app.tvlink.device.DeviceTarget
import app.tvlink.device.IdcProgress
import app.tvlink.device.SavedSelection
import app.tvlink.device.ScreenshotService
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.lpBytesSize
import app.tvlink.proto.idc.lpStringSize
import app.tvlink.proto.idc.putLPBytes
import app.tvlink.proto.idc.putLPString
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 截屏僵尸连接自愈回归(用户真机:截屏偶发失败,重启应用后正常)。
 * 链路:WiFi 抖动 → TCP 半开(心跳最坏 ~60s 判死) → 截图请求静默消失 →
 * 用户在判死窗口内反复失败只能重启。修复:应答超时主动 ping 探活,
 * 无 ACK 立即断开走自动重连,全程 ~15-20s 自愈。
 * 测试 scope 直接用 Dispatchers（无 DI 框架），故抑制 InjectDispatcher。
 */
@Suppress("InjectDispatcher")
class ShotFeatureZombieTest {
    private val server = ServerSocket(IdcConst.TCP_PORT) // DeviceSelection 写死 13510
    private val alive = AtomicBoolean(true)
    private val socks = CopyOnWriteArrayList<Socket>()
    private val screenshotRequests = AtomicInteger(0)
    private val firstScreenshotReq = CountDownLatch(1)
    private val secondScreenshotReq = CountDownLatch(1)
    private val pingSeen = CountDownLatch(1)
    private val expectedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xD9.toByte())

    @AfterTest
    fun tearDown() {
        server.close()
        socks.forEach { runCatching { it.close() } }
    }

    /** 假 TV:每个连接回 LoginResp + 心跳回声;[alive]=false 时完全静默(半开僵尸)。 */
    private fun serve() {
        thread(isDaemon = true) {
            while (true) {
                val s =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        return@thread
                    }
                socks.add(s)
                thread(isDaemon = true) { serveConn(s) }
            }
        }
    }

    private fun serveConn(s: Socket) {
        runCatching {
            val inp = DataInputStream(s.getInputStream())
            while (true) {
                val header = ByteArray(IdcConst.HEADER_LEN)
                inp.readFully(header)
                val hb = ByteBuffer.wrap(header)
                hb.int // magic
                hb.int // key
                val packetId = hb.int
                val total = hb.int
                val body = ByteArray(total - IdcConst.HEADER_LEN)
                if (body.isNotEmpty()) inp.readFully(body)
                if (packetId == IdcConst.ID_CMD_SCREENSHOT_REQ) recordScreenshotRequest()
                if (!alive.get()) {
                    if (packetId == IdcConst.ID_HEARTBEAT) pingSeen.countDown()
                    continue // 静默丢弃,模拟半开僵尸；请求计数仍在静默前完成
                }
                when (packetId) {
                    IdcConst.ID_LOGIN_REQ -> writeLoginResp(s)
                    IdcConst.ID_HEARTBEAT -> writeHeartbeatResp(s, header, body, total)
                    IdcConst.ID_CMD_SCREENSHOT_RESP -> Unit
                    IdcConst.ID_CMD_SCREENSHOT_REQ -> writeScreenshotResp(s)
                }
                s.getOutputStream().flush()
            }
        }
    }

    private fun recordScreenshotRequest() {
        when (screenshotRequests.incrementAndGet()) {
            1 -> firstScreenshotReq.countDown()
            2 -> secondScreenshotReq.countDown()
        }
    }

    private fun writeLoginResp(s: Socket) {
        val resp = ByteBuffer.allocate(IdcConst.HEADER_LEN + 12)
        resp.putInt(IdcConst.MAGIC)
        resp.putInt(IdcConst.UNASSIGNED_KEY)
        resp.putInt(IdcConst.ID_LOGIN_RESP)
        resp.putInt(IdcConst.HEADER_LEN + 12)
        resp.putInt(0)
        resp.putInt(4242)
        resp.putInt(0)
        s.getOutputStream().write(resp.array())
    }

    private fun writeHeartbeatResp(
        s: Socket,
        header: ByteArray,
        body: ByteArray,
        total: Int,
    ) {
        val resp = ByteBuffer.allocate(total)
        resp.put(header)
        resp.put(body)
        s.getOutputStream().write(resp.array())
    }

    private fun writeScreenshotResp(s: Socket) {
        val cmd = """{"cmdReqID":1}"""
        val dummy = """{"dummy":0}"""
        val bodySize = lpStringSize(cmd) + lpStringSize(dummy) + lpBytesSize(expectedJpeg)
        val body = ByteBuffer.allocate(bodySize)
        body.putLPString(cmd)
        body.putLPString(dummy)
        body.putLPBytes(expectedJpeg)
        val resp = ByteBuffer.allocate(IdcConst.HEADER_LEN + bodySize)
        resp.putInt(IdcConst.MAGIC)
        resp.putInt(IdcConst.UNASSIGNED_KEY)
        resp.putInt(IdcConst.ID_CMD_SCREENSHOT_RESP)
        resp.putInt(IdcConst.HEADER_LEN + bodySize)
        resp.put(body.array())
        s.getOutputStream().write(resp.array())
    }

    @Test
    fun screenshotTimeoutKillsZombieAndReconnects() {
        serve()
        val notices = CopyOnWriteArrayList<String>()
        val delivered = ArrayBlockingQueue<ByteArray>(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dm = DeviceSelection(InMemoryConnectionHistory())
        val service = ScreenshotService(dm)
        val shot = ShotFeature(scope, service) { notices.add(it) }
        scope.launch { dm.packets.collect { p -> service.handlePacket(p.binding, p.value) } }
        scope.launch { service.screenshots.collect { delivered.offer(it) } }
        try {
            dm.select(DeviceTarget(ip = "127.0.0.1"), ConnectionMode.FULL)
            var waited = 0L
            while (dm.state.value.idcProgress != IdcProgress.CONNECTED && waited < 10_000) {
                Thread.sleep(100)
                waited += 100
            }
            assertTrue(dm.state.value.idcProgress == IdcProgress.CONNECTED, "connect failed")

            alive.set(false) // 僵尸化:对端完整读取并识别请求，但保持静默
            shot.capture()
            assertTrue(
                firstScreenshotReq.await(2, TimeUnit.SECONDS),
                "first ScreenShotReq never reached the fake TV (send path missing)",
            )
            assertTrue(shot.state is ShotFeature.ShotUiState.Capturing)

            // 10s 截图超时后必须真的发出 ping；静默假 TV 不应答，随后判死断开。
            assertTrue(pingSeen.await(15, TimeUnit.SECONDS), "screenshot timeout did not send a ping")
            var hasDisconnect = false
            waited = 0L
            while (waited < 20_000) {
                if (dm.connection == null) {
                    hasDisconnect = true
                    break
                }
                Thread.sleep(200)
                waited += 200
            }
            assertTrue(hasDisconnect, "zombie connection was never dropped (ping path missing)")

            // 网络恢复 → 自动重连(5s 首重试 + 建连)应恢复 CONNECTED
            alive.set(true)
            waited = 0L
            var isRecovered = false
            while (waited < 15_000) {
                if (dm.state.value.idcProgress == IdcProgress.CONNECTED) {
                    isRecovered = true
                    break
                }
                Thread.sleep(200)
                waited += 200
            }
            assertTrue(isRecovered, "no auto-reconnect after zombie kill")
            assertTrue(notices.any { it.contains("重连") }, "user not informed about reconnect: $notices")

            shot.capture()
            assertTrue(secondScreenshotReq.await(2, TimeUnit.SECONDS), "second ScreenShotReq was not sent")
            val jpeg = assertNotNull(delivered.poll(5, TimeUnit.SECONDS), "screenshot flow emitted no image")
            assertContentEquals(expectedJpeg, jpeg, "screenshot flow delivered different bytes")

            waited = 0L
            while (shot.state !is ShotFeature.ShotUiState.Success && waited < 2_000) {
                Thread.sleep(25)
                waited += 25
            }
            val success = assertIs<ShotFeature.ShotUiState.Success>(shot.state)
            assertContentEquals(expectedJpeg, success.jpeg, "ShotFeature did not expose the returned image")
        } finally {
            service.destroy()
            dm.destroy()
            scope.cancel()
        }
    }
}

private class InMemoryConnectionHistory : ConnectionHistory {
    private var saved: SavedSelection? = null

    override fun load(): SavedSelection? = saved

    override fun save(selection: SavedSelection) {
        saved = selection
    }
}
