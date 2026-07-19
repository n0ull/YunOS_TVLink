package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that IdcConnection does not leak heartbeat scheduler threads
 * after close/detect — regression test for H1.
 */
class IdcConnectionLeakTest {
    @Test
    fun `detect on unreachable host leaves no idc-hb threads`() {
        // connect to a port that will refuse immediately
        val conn = IdcConnection("127.0.0.1", 1)
        conn.detect(timeoutMs = 500)

        // give daemon threads a moment to terminate
        Thread.sleep(100)
        val leaked = Thread.getAllStackTraces().keys.count { it.name == "idc-hb" }
        assertTrue(leaked == 0, "Expected 0 idc-hb threads after detect(), found $leaked")
    }

    @Test
    fun `failed connect leaves no idc-hb threads`() {
        repeat(5) {
            val conn = IdcConnection("127.0.0.1", 1)
            conn.connect(
                app.tvlink.proto.idc
                    .LoginReq(devName = "test"),
                timeoutMs = 500,
            )
        }
        Thread.sleep(100)
        val leaked = Thread.getAllStackTraces().keys.count { it.name == "idc-hb" }
        assertTrue(leaked == 0, "Expected 0 idc-hb threads after 5 failed connects, found $leaked")
    }
}
