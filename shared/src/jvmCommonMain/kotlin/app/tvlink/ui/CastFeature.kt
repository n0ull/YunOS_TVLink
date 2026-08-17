package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tvlink.proto.cast.CastController
import app.tvlink.proto.cast.MediaHttpServer
import app.tvlink.proto.mdns.Mdns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 投屏功能状态持有者：控制通道（CastController）+ 内嵌媒体服务器 + 播放快照。
 * 生命周期由 AppViewModel 托管（scope 即其 viewModelScope，destroy 经 onCleared 链调用）。
 */
class CastFeature(
    private val scope: CoroutineScope,
    private val showNotice: (String) -> Unit,
) {
    companion object {
        /** 投屏控制端口兜底候选（ddh/mDNS 均未提供时按序尝试）。
         *  原 App 默认 13520；本 TV 固件实际监听 13521（ddh 实证）。
         *  不同固件端口可能不同，兜底时两个都试，避免写死单一端口。 */
        private val CAST_FALLBACK_PORTS = intArrayOf(CastController.DEFAULT_PORT, 13521)

        private const val AWAIT_CHANNEL_TIMEOUT_MS = 15_000L
        private const val AWAIT_POLL_MS = 200L
    }

    /** 投屏播放快照（最近一次轮询/事件 + 本地乐观更新）。 */
    data class CastStatus(
        val title: String = "",
        val playState: CastController.PlayState = CastController.PlayState.UNKNOWN,
        val duration: Long = 0L,
        val position: Long = 0L,
        val volume: Int = 0,
        val rate: Int = 1,
    )

    /** 投屏 UI 状态（密封层级）：Unavailable=控制通道未建立；Ready=通道就绪，status 为最新快照。 */
    sealed interface CastUiState {
        data object Unavailable : CastUiState

        data class Ready(
            val status: CastStatus = CastStatus(),
        ) : CastUiState
    }

    var ui by mutableStateOf<CastUiState>(CastUiState.Unavailable)
        private set
    var mediaServerUrl by mutableStateOf("")
        private set
    var serverInfo by mutableStateOf<CastController.ServerInfo?>(null)
        private set

    private val mediaServer = MediaHttpServer()

    @Volatile
    private var controller: CastController? = null

    /** 投屏建连单飞：onHostConnected/ensureAlive 可能并发触发，重叠调用会互断对方
     *  已建好的通道（各自先 disconnect 再建），Mutex 串行化避免。 */
    private val connectMutex = Mutex()

    /** 有在途建连标志：file() 据此等待建连完成，而非立即判“投屏通道未就绪”。 */
    @Volatile
    private var connecting = false

    /** 生命周期世代号：connect/onDisconnected 发起即递增。在途 connect 装回前校验世代未变
     *  （期间发生显式断开则放弃装回，防幽灵通道复活）；onDisconnected 的异步清理同样校验——
     *  之后已有新建连意图时由新 connect 接管清理，避免陈旧清理误杀新会话。 */
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    /** 控制通道存活（供 AppViewModel.onResume 判断是否需补建）。 */
    val channelAlive: Boolean
        get() = controller?.state == CastController.State.CONNECTED

    /** IDC 连接建立后调用：断旧通道再建新（防 TV 侧双会话错乱，2026-07-25 真机实证）。 */
    fun onHostConnected(
        ip: String,
        port: Int,
    ) = connect(ip, port)

    /** 回前台兜底：IDC 在线但投屏通道死亡时补建（其他机制看不见这条 TCP）。 */
    fun ensureAlive(
        ip: String,
        port: Int,
    ) {
        if (!channelAlive) connect(ip, port)
    }

    /** IDC 断开（落 IDLE）：释放通道与媒体服务器，状态归零。
     *  清理收进 connectMutex（H4）：旧实现不进锁，在途 connect 会在清理完成后
     *  又把 controller 装回并重启媒体服务——幽灵投屏通道 + HTTP 服务残留。 */
    fun onDisconnected() {
        val g = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            connectMutex.withLock {
                if (generation.get() != g) return@withLock // 之后已有新建连意图，清理由其接管
                mediaServerUrl = ""
                serverInfo = null
                ui = CastUiState.Unavailable
                controller?.disconnect()
                controller = null
                mediaServer.stop()
            }
        }
    }

    fun destroy() {
        generation.incrementAndGet() // 在途 connect 不得在本对象销毁后装回通道
        controller?.disconnect()
        mediaServer.stop()
    }

    /**
     * 投屏控制通道建立。先断旧通道再建新——2026-07-25 真机实证:重复 onConnected
     * 不断旧通道会残留双控制会话,TV 侧会话归属错乱致播放/暂停/退出/音量全部失效。
     *
     * port=0 时（ddh/mDNS 均未提供）依次尝试 CAST_FALLBACK_PORTS；
     * 原 App 默认 13520，本 TV 固件实际监听 13521（ddh 实证），不同固件可能不同。
     */
    private fun connect(
        ip: String,
        port: Int,
    ) {
        // 请求时取号（非协程内）：协程调度延迟会让后发的 onDisconnected 先递增，
        // 请求顺序即世代顺序才能保证「建连期间断过连」判定不错漏
        val gen = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            var built: CastController? = null
            // 单飞锁：与并发 connect 串行，防止互断已建好的通道；断旧建新整体在锁内
            connectMutex.withLock {
                connecting = true
                try {
                    controller?.disconnect()
                    controller = null
                    val candidates = if (port != 0) intArrayOf(port) else CAST_FALLBACK_PORTS
                    var cc: CastController? = null
                    for (p in candidates) {
                        val trial = CastController(ip, p)
                        if (trial.connect()) {
                            cc = trial
                            break
                        }
                    }
                    if (cc != null && generation.get() == gen) {
                        wireEvents(cc)
                        controller = cc
                        built = cc
                        // 重建保留旧快照：重连时 TV 往往仍在播放，标题/进度不清零（轮询随后校准）
                        ui = CastUiState.Ready((ui as? CastUiState.Ready)?.status ?: CastStatus())
                        startMediaServer(ip)
                    } else {
                        // 建连失败，或建连期间发生显式断开/更新建连（世代已变）——放弃装回
                        cc?.disconnect()
                        ui = CastUiState.Unavailable
                    }
                } finally {
                    connecting = false
                }
            }
            // serverInfo 是同步 HTTP 请求（秒级）——移出锁外，并发建连尝试不被其阻塞。
            // 期间 built 可能已被并发 connect/onDisconnected 断开并替换：仅在它仍是当前通道时采纳结果，
            // 否则 serverInfo() 返回 null 会清掉新通道的服务信息（回归防护）。
            if (built != null) {
                val info = built.serverInfo()
                if (controller === built) serverInfo = info
            }
        }
    }

    private fun wireEvents(cc: CastController) {
        cc.onEvent = { st, dur, pos, vol, rate ->
            scope.launch(Dispatchers.Default) {
                updateStatus { s ->
                    s.copy(
                        playState = st,
                        duration = if (dur > 0) dur else s.duration,
                        position = pos,
                        volume = if (vol >= 0) vol else s.volume,
                        rate = if (rate > 0) rate else s.rate,
                    )
                }
            }
        }
    }

    private fun startMediaServer(tvIp: String) {
        val localIp = Mdns.localLanAddress()?.hostAddress
        // 仅服务已连 TV 的来源 IP：不可信网段下同网段主机无法拉走投屏文件
        if (localIp != null && mediaServer.start(localIp, allowedClientIp = tvIp)) {
            mediaServerUrl = mediaServer.baseUrl
        }
    }

    /** 等待在途建连完成并返回通道；无在途建连且通道为空时立即返回 null（不空等）。 */
    private suspend fun awaitChannel(): CastController? {
        // 上限覆盖兜底端口依次尝试（13520 超时 → 13521）的最坏耗时
        var waited = 0L
        while (waited <= AWAIT_CHANNEL_TIMEOUT_MS) {
            controller?.let { return it }
            if (!connecting) return null
            kotlinx.coroutines.delay(AWAIT_POLL_MS)
            waited += AWAIT_POLL_MS
        }
        return null
    }

    /** 仅在 Ready 时更新投屏快照（Unavailable 无通道，丢弃事件）。 */
    private fun updateStatus(block: (CastStatus) -> CastStatus) {
        val r = ui as? CastUiState.Ready ?: return
        ui = r.copy(status = block(r.status))
    }

    fun file(
        path: String,
        title: String,
        type: String,
    ) {
        val file = File(path)
        if (!file.exists() || mediaServerUrl.isEmpty()) {
            showNotice("媒体服务未就绪")
            return
        }
        // UUID 媒体 ID：毫秒时间戳可枚举（同网段可爆破拉走私人文件），UUID 不可猜测
        val id =
            when (type) {
                "video" -> "video-${UUID.randomUUID()}"
                "audio" -> "audio-${UUID.randomUUID()}"
                else -> "image-${UUID.randomUUID()}"
            }
        mediaServer.register(id, file)
        val url = mediaServer.urlFor(id)
        scope.launch(Dispatchers.IO) {
            val cc =
                // 建连在途时等其完成（点击投屏恰逢自动建连的竞态），而非立即判失败
                awaitChannel() ?: run {
                    showNotice("投屏通道未就绪，请稍后重试")
                    return@launch
                }
            updateStatus { it.copy(title = title) }
            // 音乐封面（原 App 传 thumbnail_url，docs/re/04 §3）：Android 经 MediaStore 取封面
            // 拷入缓存后按注册制供片（不走原 App 的绝对路径回退），失败则无封面投屏
            val thumbnail =
                if (type == "audio") {
                    app.tvlink.ui.widgets.albumArtFile(path)?.let { cover ->
                        val coverId = "cover-${UUID.randomUUID()}"
                        mediaServer.register(coverId, cover, "image/jpeg")
                        mediaServer.urlFor(coverId)
                    }
                } else {
                    null
                }
            val ok = cc.setMedia(type, url, title, thumbnail)
            if (ok) cc.play() else showNotice("投屏失败")
        }
    }

    fun seek(ms: Long) {
        scope.launch(Dispatchers.IO) { controller?.seek(ms) }
    }

    // 播放控制必须经 IO 线程:CastController 是阻塞 socket,Android 主线程直调
    // 会抛 NetworkOnMainThreadException(message 为 null,曾误判为协议失败)。

    fun play() {
        scope.launch(Dispatchers.IO) { controller?.play() }
    }

    fun pause() {
        scope.launch(Dispatchers.IO) { controller?.pause() }
    }

    fun stop() {
        scope.launch(Dispatchers.IO) { controller?.stop() }
    }

    /** 调 TV 音量并乐观更新本地值(轮询随后校准)。 */
    fun volumeTo(v: Int) {
        updateStatus { it.copy(volume = v) }
        scope.launch(Dispatchers.IO) { controller?.volume(v) }
    }

    /** 播放倍速（POST /rate）：乐观更新本地值，轮询随后校准（与音量同模式）。 */
    fun rateTo(r: Int) {
        updateStatus { it.copy(rate = r) }
        scope.launch(Dispatchers.IO) { controller?.rate(r) }
    }
}
