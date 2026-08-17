package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tvlink.device.ScreenshotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 截屏功能状态持有者：服务应答流 → ShotUiState，兜底超时复位。 */
class ShotFeature(
    private val scope: CoroutineScope,
    private val service: ScreenshotService,
    private val showNotice: (String) -> Unit,
) {
    /** 截屏 UI 状态（密封层级）：Capturing 携带上一帧，截取期间旧图保持显示。 */
    sealed interface ShotUiState {
        data object Idle : ShotUiState

        data class Capturing(
            val previous: ByteArray?,
        ) : ShotUiState

        data class Success(
            val jpeg: ByteArray,
        ) : ShotUiState
    }

    var state by mutableStateOf<ShotUiState>(ShotUiState.Idle)
        private set

    init {
        scope.launch(Dispatchers.Default) {
            service.screenshots.collect { jpeg -> state = ShotUiState.Success(jpeg) }
        }
    }

    fun capture() {
        // 断线时曾静默吞掉（无任何提示）
        if (!service.capture()) {
            showNotice("未连接电视，无法截屏")
            return
        }
        state = ShotUiState.Capturing(currentShot())
        resetCapturingAfter(SINGLE_TIMEOUT_MS)
    }

    fun captureBurst() {
        if (!service.captureBurst()) {
            showNotice("未连接电视，无法截屏")
            return
        }
        state = ShotUiState.Capturing(currentShot())
        // 连拍 5 帧 × 300ms 间隔 + 每帧 TV 响应，20s 兜底足够覆盖
        resetCapturingAfter(BURST_TIMEOUT_MS)
    }

    private fun currentShot(): ByteArray? = (state as? ShotUiState.Success)?.jpeg

    /** 兜底超时后仍在 Capturing（TV 未应答/连拍应答不足）则回退上一帧/Idle，防止按钮永久禁用。 */
    private fun resetCapturingAfter(timeoutMs: Long) {
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(timeoutMs)
            val s = state as? ShotUiState.Capturing ?: return@launch
            state = s.previous?.let { ShotUiState.Success(it) } ?: ShotUiState.Idle
        }
    }

    private companion object {
        const val SINGLE_TIMEOUT_MS = 10_000L
        const val BURST_TIMEOUT_MS = 20_000L
    }
}
