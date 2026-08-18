package app.tvlink.device

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
        val self = Mdns.localLanAddress()?.hostAddress ?: error("test host has no LAN address")
        val prefix = self.substringBeforeLast('.')
        val expected = (1..254).map { "$prefix.$it" }.filter { it != self }.toSet()

        val probed = ConcurrentHashMap.newKeySet<String>()
        val done = CountDownLatch(1)
        val d = Discovery()
        d.hostProber = { ip, _ -> probed.add(ip) } // 无网络假探测,秒回
        d.onFinished = { done.countDown() }
        d.start(scanSubnet = true)

        assertTrue(done.await(15, TimeUnit.SECONDS), "scan did not finish within 15s")
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
}
