package app.tvlink.device

import app.tvlink.proto.ib.IbConst
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RcControllerTest {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    @Volatile
    private var client: Socket? = null

    private val receivedFrames = LinkedBlockingQueue<IbFrame>()

    private val serverThread =
        thread(isDaemon = true) {
            runCatching {
                val socket = server.accept()
                client = socket
                val input = DataInputStream(socket.getInputStream())
                receivedFrames.put(readFrame(input))
                writeHelloResponse(socket)
                receivedFrames.put(readFrame(input)) // MODULEINFO
                receivedFrames.put(readFrame(input)) // initial CHANGETYPE
                writeCurrentApp(socket)
                receivedFrames.put(readFrame(input)) // CHANGETYPE selected by the test
                input.read() // wait for client close
            }
        }

    @AfterTest
    fun tearDown() {
        runCatching { client?.close() }
        server.close()
        serverThread.join(2_000)
    }

    @Test
    fun ibOnlySelectionStartsRemoteWithoutIdc() {
        val selection = DeviceSelection(InMemoryConnectionHistory())
        val remote = RcController(selection, ibPort = server.localPort)
        try {
            selection.select(DeviceTarget(ip = "127.0.0.1"), ConnectionMode.IB_ONLY)

            val deadline = System.currentTimeMillis() + 5_000
            while (!remote.state.value.ibReady && System.currentTimeMillis() < deadline) Thread.sleep(25)

            assertTrue(remote.state.value.ibReady, "IB-only target did not establish IB")
            assertEquals(ConnectionMode.IB_ONLY, selection.state.value.mode)
            assertEquals(
                "127.0.0.1",
                selection.state.value.target
                    ?.ip,
            )
            assertEquals(IdcProgress.IDLE, selection.state.value.idcProgress)
            assertEquals(null, selection.connection)
            assertEquals(selection.state.value.selectionId, remote.state.value.selectionId)
            assertEquals(RemoteMode.KEYPAD, remote.state.value.mode)

            assertEquals(IbConst.REQ_HELLO, nextFrame().type)
            assertEquals(IbConst.REQ_MODULEINFO, nextFrame().type)
            val initialChangeType = nextFrame()
            assertEquals(IbConst.REQ_CHANGETYPE, initialChangeType.type)
            assertEquals("[${IbConst.CHANGETYPE_DEFAULT}]", initialChangeType.body.toString(Charsets.UTF_8))

            remote.setMode(RemoteMode.JOYSTICK)
            assertEquals(RemoteMode.JOYSTICK, remote.state.value.mode)
            val joystickChangeType = nextFrame()
            assertEquals(IbConst.REQ_CHANGETYPE, joystickChangeType.type)
            assertEquals("[${IbConst.CHANGETYPE_JOYSTICK}]", joystickChangeType.body.toString(Charsets.UTF_8))

            val currentAppDeadline = System.currentTimeMillis() + 5_000
            while (remote.state.value.currentApp == null &&
                System.currentTimeMillis() < currentAppDeadline
            ) {
                Thread.sleep(25)
            }
            assertEquals("com.test.app", remote.state.value.currentApp)
        } finally {
            remote.destroy()
            selection.destroy()
        }
    }

    private fun nextFrame(): IbFrame =
        receivedFrames.poll(5, TimeUnit.SECONDS) ?: error("fake TV did not receive the expected IB frame")

    private fun readFrame(input: DataInputStream): IbFrame {
        val header = ByteArray(20)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header)
        check(buffer.int == IbConst.MAGIC)
        val size = buffer.int
        val type = buffer.int
        buffer.int
        buffer.int
        val body = ByteArray(size)
        input.readFully(body)
        return IbFrame(type, body)
    }

    private data class IbFrame(
        val type: Int,
        val body: ByteArray,
    )

    private fun writeHelloResponse(socket: Socket) {
        val body = """{"sid":123,"ver":"3.29"}""".toByteArray(Charsets.UTF_8)
        val reserve = 7
        val frame = ByteBuffer.allocate(20 + body.size)
        frame.putInt(IbConst.MAGIC)
        frame.putInt(body.size)
        frame.putInt(IbConst.RSP_MASK or IbConst.REQ_HELLO)
        frame.putInt(reserve)
        frame.putInt(body.size + reserve)
        frame.put(body)
        socket.getOutputStream().write(frame.array())
        socket.getOutputStream().flush()
    }

    private fun writeCurrentApp(socket: Socket) {
        val body = """{"cur_app":"com.test.app"}""".toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(20 + body.size)
        frame.putInt(IbConst.MAGIC)
        frame.putInt(body.size)
        frame.putInt(IbConst.PROTO_CURRENTAPP)
        frame.putInt(0)
        frame.putInt(0)
        frame.put(body)
        socket.getOutputStream().write(frame.array())
        socket.getOutputStream().flush()
    }
}

private class InMemoryConnectionHistory : ConnectionHistory {
    private var saved: SavedSelection? = null

    override fun load(): SavedSelection? = saved

    override fun save(selection: SavedSelection) {
        saved = selection
    }
}
