package app.tvlink.device

import app.tvlink.platform.localLanAddress
import app.tvlink.proto.mdns.Mdns
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试:e7940d2 把子网扫描池的 shutdown() 误改为 shutdownNow()。
 * submitProbes 只是把 254 个探测任务「入队」(pool.execute 非阻塞),
 * finally 随即执行 shutdownNow() —— 未启动的排队任务被直接丢弃,
 * /24 主动扫描实际只覆盖前 ~24 个地址,其余网段的电视永远扫不到。
 */
class DiscoverySubnetScanTest {
    @Test
    @Suppress("FunctionNaming") // 反引号测试命名
    fun `subnet scan probes every host in the slash24`() {
        val self = localLanAddress()?.hostAddress ?: error("test host has no LAN address")
        val prefix = self.substringBeforeLast('.')
        val expected = (1..254).map { "$prefix.$it" }.filter { it != self }.toSet()

        val probed = ConcurrentHashMap.newKeySet<String>()
        val d = Discovery()
        d.hostProber = { ip, _ -> probed.add(ip) } // 无网络假探测,秒回
        d.start(scanSubnet = true)

        val deadline = System.currentTimeMillis() + 15_000
        while (d.state.value.scanning && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertFalse(d.state.value.scanning, "scan did not finish within 15s")
        assertEquals(expected, probed)
    }

    @Test
    @Suppress("FunctionNaming") // 反引号测试命名
    fun `stop cancels a running subnet scan promptly`() {
        val d = Discovery()
        d.hostProber = { _, _ -> Thread.sleep(10_000) } // 可被中断的慢探测
        d.start(scanSubnet = true)
        Thread.sleep(300) // 让池线程进入探测

        d.stop()

        val deadline = System.currentTimeMillis() + 3000
        var isAlive = true
        while (System.currentTimeMillis() < deadline) {
            isAlive = Thread.getAllStackTraces().keys.any { it.name == "disc-scan" && it.isAlive }
            if (!isAlive) break
            Thread.sleep(50)
        }
        assertFalse(isAlive, "disc-scan thread still alive 3s after stop()")
    }

    @Test
    fun oldScanCompletionDoesNotFinishRestartedScan() {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val finishSecond = CountDownLatch(1)
        val firstJoined = CountDownLatch(1)
        val d = Discovery()
        d.mdnsProber = { _, _ -> }
        d.joinFinished = { epoch -> if (epoch == 1) firstJoined.countDown() }
        d.hostProber = { _, epoch ->
            when (epoch) {
                1 -> {
                    firstStarted.countDown()
                    awaitIgnoringInterrupts(finishFirst)
                }
                2 -> {
                    secondStarted.countDown()
                    awaitIgnoringInterrupts(finishSecond)
                }
            }
        }

        try {
            d.start(scanSubnet = true)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            assertTrue(d.state.value.scanning)

            d.start(scanSubnet = true)
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))

            finishFirst.countDown()
            assertTrue(firstJoined.await(5, TimeUnit.SECONDS))
            assertTrue(d.state.value.scanning, "old scan completion finished the restarted scan")

            finishSecond.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            while (d.state.value.scanning && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertFalse(d.state.value.scanning, "current scan did not finish")
        } finally {
            finishFirst.countDown()
            finishSecond.countDown()
            d.stop()
        }
    }

    @Test
    fun lateResultFromPreviousScanIsNotPublished() {
        var staleReport: ((Mdns.MdnsDevice) -> Unit)? = null
        val d = Discovery()
        d.mdnsProber = { epoch, report ->
            if (epoch == 1) staleReport = report
        }

        try {
            d.start(scanSubnet = false)
            val deadline = System.currentTimeMillis() + 5_000
            while (d.state.value.scanning && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertFalse(d.state.value.scanning)

            d.start(scanSubnet = false)
            val report = staleReport ?: error("first scan did not provide a result callback")
            report(Mdns.MdnsDevice(ip = "192.0.2.1", name = "stale"))

            assertTrue(
                d.state.value.devices
                    .isEmpty(),
                "previous scan published a stale candidate",
            )
        } finally {
            d.stop()
        }
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS), "probe was not released")
                return
            } catch (_: InterruptedException) {
                // stop() interrupts superseded workers; the test controls their actual completion.
            }
        }
    }
}
