package app.tvlink.ui

import androidx.compose.runtime.mutableStateListOf
import app.tvlink.device.RpmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** TV 应用管理状态持有者：应用列表流 + 安装进度/操作结果通知。 */
class AppsFeature(
    private val scope: CoroutineScope,
    private val rpm: RpmService,
    private val showNotice: (String) -> Unit,
) {
    val tvApps = mutableStateListOf<RpmService.TvApp>()

    init {
        scope.launch(Dispatchers.Default) {
            rpm.appList.collect { apps ->
                tvApps.clear()
                tvApps.addAll(apps)
            }
        }
        scope.launch(Dispatchers.Default) {
            rpm.installProgress.collect { pr -> showNotice("安装 ${pr.packageName}: ${pr.progress}%") }
        }
        scope.launch(Dispatchers.Default) {
            rpm.opResults.collect { r ->
                showNotice(
                    if (r.errorCode == 0) {
                        "${r.op} ${r.packageName} 成功"
                    } else {
                        "${r.op} ${r.packageName} 失败 (${r.errorCode})"
                    },
                )
                if (r.errorCode == 0) rpm.getAppList()
            }
        }
    }

    fun refresh() = rpm.getAppList()
}
