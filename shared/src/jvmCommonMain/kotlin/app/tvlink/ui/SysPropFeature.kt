package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tvlink.device.SysPropService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 系统属性查询状态持有者：服务应答流 → SysPropUiState，超时未应答复位 Idle。 */
class SysPropFeature(
    private val scope: CoroutineScope,
    private val service: SysPropService,
) {
    /** 系统属性查询 UI 状态（密封层级）：Idle/Loading/Result。 */
    sealed interface SysPropUiState {
        data object Idle : SysPropUiState

        data object Loading : SysPropUiState

        data class Result(
            val key: String,
            val value: String,
        ) : SysPropUiState
    }

    var state by mutableStateOf<SysPropUiState>(SysPropUiState.Idle)
        private set

    init {
        scope.launch(Dispatchers.Default) {
            service.values.collect { v -> state = SysPropUiState.Result(v.key, v.value) }
        }
    }

    fun query(key: String) {
        val k = key.trim()
        if (k.isEmpty() || !service.getProp(k)) return
        state = SysPropUiState.Loading
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(QUERY_TIMEOUT_MS)
            // TV 未应答兜底：仅在仍是本次 Loading 时复位，避免清掉已到结果
            if (state is SysPropUiState.Loading) state = SysPropUiState.Idle
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_MS = 10_000L
    }
}
