package app.tvlink.device

import app.tvlink.proto.idc.IdcConst
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 语音指令协议钉桩(依据反编译 AsrDef / ASR.sendText / AsrPacket_out_asrStreaming,
 * 模块名经 2026-07-22 真机广播证实):裸 `com.yunos.tv.asr` 查不到模块会静默丢弃。
 */
class AsrTextServiceTest {
    @Test
    fun moduleNameIncludesEtaoCategory() {
        // AsrDef.ASR_MODULE_NAME;真机登录广播即此串
        assertEquals("com.yunos.tv.asr:etao", AsrTextService.MODULE_NAME)
    }

    @Test
    fun streamingJsonMatchesDecompiledKeyboardFake() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"asr_streaming",""" +
                """"pk_content":{"result_code":0,"device":"TVLink","question":"打开优酷","finish":"true"}}}""",
            AsrTextService.streamingJson("打开优酷"),
        )
    }

    @Test
    fun streamingJsonEscapesQuotes() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"asr_streaming",""" +
                """"pk_content":{"result_code":0,"device":"TVLink","question":"say \"hi\"","finish":"true"}}}""",
            AsrTextService.streamingJson("say \"hi\""),
        )
    }

    @Test
    fun simpleJsonMatchesDecompiledRecordType() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"record_start","pk_content":{}}}""",
            AsrTextService.simpleJson("record_start"),
        )
    }

    @Test
    fun sendTextOpensModuleAndEmitsFullRecordSession() {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val received = ArrayBlockingQueue<Frame>(8)
        val serverThread =
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        readFrame(input) // LoginReq
                        socket.getOutputStream().apply {
                            write(
                                frame(
                                    IdcConst.ID_LOGIN_RESP,
                                    ByteBuffer
                                        .allocate(12)
                                        .putInt(0)
                                        .putInt(4242)
                                        .putInt(0)
                                        .array(),
                                ),
                            )
                            write(
                                frame(
                                    IdcConst.ID_MODULE_AVAILABILITY,
                                    lpString("""{"m_name":"${AsrTextService.MODULE_NAME}","m_id":7,"isOnline":true}"""),
                                ),
                            )
                            flush()
                        }
                        while (true) received.put(readFrame(input))
                    }
                }
            }
        val selection = DeviceSelection(AsrConnectionHistory(), idcPort = server.localPort)
        val service = AsrTextService(selection)
        try {
            selection.select(DeviceTarget(ip = "127.0.0.1"), ConnectionMode.FULL)
            val deadline = System.currentTimeMillis() + 5_000
            while (selection.modules.value.none { it.id == 7 } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }
            assertTrue(selection.modules.value.any { it.id == 7 }, "ASR module was not announced")

            service.sendText("打开优酷")

            val syn = assertNotNull(received.poll(5, TimeUnit.SECONDS), "VConn SYN was not sent")
            assertEquals(IdcConst.ID_VCONN_SYN, syn.id)
            assertEquals("""{"mid":7}""", ByteBuffer.wrap(syn.body).readLPString())

            val start = assertNotNull(received.poll(5, TimeUnit.SECONDS)).vConnPayload()
            val streaming = assertNotNull(received.poll(5, TimeUnit.SECONDS)).vConnPayload()
            val stop = assertNotNull(received.poll(5, TimeUnit.SECONDS)).vConnPayload()
            assertTrue(start.contains("\"pk_type\":\"record_start\""), "record_start missing: $start")
            assertTrue(streaming.contains("\"pk_type\":\"asr_streaming\""), "streaming packet missing: $streaming")
            assertTrue(streaming.contains("\"question\":\"打开优酷\""), "recognized text missing: $streaming")
            assertTrue(stop.contains("\"pk_type\":\"record_stop\""), "record_stop missing: $stop")
        } finally {
            service.destroy()
            selection.destroy()
            server.close()
            serverThread.join(2_000)
        }
    }

    private data class Frame(
        val id: Int,
        val body: ByteArray,
    )

    private fun readFrame(input: DataInputStream): Frame {
        val header = ByteArray(IdcConst.HEADER_LEN)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header)
        assertEquals(IdcConst.MAGIC, buffer.int)
        buffer.int
        val id = buffer.int
        val total = buffer.int
        val body = ByteArray(total - IdcConst.HEADER_LEN)
        input.readFully(body)
        return Frame(id, body)
    }

    private fun Frame.vConnPayload(): String {
        assertEquals(IdcConst.ID_VCONN_DATA, id)
        val buffer = ByteBuffer.wrap(body)
        assertEquals("""{"mid":7}""", buffer.readLPString())
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return String(payload, Charsets.UTF_8)
    }

    private companion object {
        fun frame(
            id: Int,
            body: ByteArray,
        ): ByteArray =
            ByteBuffer.allocate(IdcConst.HEADER_LEN + body.size).run {
                putInt(IdcConst.MAGIC)
                putInt(IdcConst.UNASSIGNED_KEY)
                putInt(id)
                putInt(IdcConst.HEADER_LEN + body.size)
                put(body)
                array()
            }

        fun lpString(value: String): ByteArray {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return ByteBuffer
                .allocate(4 + bytes.size)
                .putInt(bytes.size)
                .put(bytes)
                .array()
        }

        fun ByteBuffer.readLPString(): String {
            val bytes = ByteArray(int)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}

private class AsrConnectionHistory : ConnectionHistory {
    override fun load(): SavedSelection? = null

    override fun save(selection: SavedSelection) = Unit
}
