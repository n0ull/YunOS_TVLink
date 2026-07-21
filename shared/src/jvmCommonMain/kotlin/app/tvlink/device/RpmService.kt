package app.tvlink.device

import app.tvlink.proto.idc.jsonEscape
import app.tvlink.proto.idc.parseJsonObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * RPM — remote package management over IDC VConn (module wire name "com.yunos.idc.appstore";
 * `com.yunos.tv.appstore` is only the Java package of the decompiled message classes — the
 * MODULE_NAME constant below still carries the wrong name, fix tracked in TODO.md P1 R1).
 * Frame: int32 packetId + int32 requestId + JSON body. See docs/re/05 §3.
 */
class RpmService(
    private val deviceManager: DeviceManager,
) {
    data class TvApp(
        val packageName: String = "",
        val appName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val iconUrl: String = "",
        val status: String = "",
    )

    data class InstallProgress(
        val packageName: String,
        val progress: Int,
        val status: String,
    )

    var onAppList: ((List<TvApp>) -> Unit)? = null
    var onSystemInfo: ((Map<String, String>) -> Unit)? = null
    var onInstallProgress: ((InstallProgress) -> Unit)? = null
    var onOpResult: ((op: String, packageName: String, errorCode: Int) -> Unit)? = null

    private val requestId = AtomicInteger(1)
    private var moduleId: Int? = null
    private var vconnOpen = false

    /**
     * 挂起的请求:VConn 打开后补发。仅缓存最近一次(列表刷新优先级最高)。
     * null 表示无挂起请求。
     */
    private var pendingRequest: Pair<Int, String>? = null

    companion object {
        const val MODULE_NAME = "com.yunos.tv.appstore"
        const val ID_GETAPPINFO_REQ = 2
        const val ID_GETAPPINFO_RESP = 3
        const val ID_GETLIST_REQ = 4
        const val ID_GETLIST_RESP = 5
        const val ID_INSTALL_REQ = 7
        const val ID_INSTALL_RESP = 8
        const val ID_INSTALL_STATUS = 9
        const val ID_INSTALL_CANCEL = 10
        const val ID_UNINSTALL_REQ = 11
        const val ID_UNINSTALL_RESP = 12
        const val ID_OPENAPP_REQ = 14
        const val ID_OPENAPP_RESP = 15
        const val ID_UPDATE_REQ = 20
        const val ID_UPDATE_RESP = 21
        const val ID_CONTINUE_DOWNLOAD = 24
        const val ID_CANCEL_GETLIST = 26
        const val ID_GET_SYSTEMINFO = 40
        const val ID_SYSTEMINFO = 41
    }

    private val vConnListener: (Int, ByteArray) -> Unit = { _, payload -> handle(payload) }

    /**
     * 注册 VConn 数据监听 + module 在线状态回调。module 上线时主动 openVConn 并补发挂起请求。
     * 幂等——每次连接都可安全调用。
     */
    fun attach() {
        deviceManager.removeVConnListener(vConnListener)
        deviceManager.addVConnListener(vConnListener)
        deviceManager.onModuleAvailability = { name, mid, online ->
            if (name == MODULE_NAME) {
                if (online) {
                    moduleId = mid
                    if (!vconnOpen) {
                        vconnOpen = true
                        deviceManager.connection?.openVConn(mid)
                        pendingRequest?.let { (packetId, json) ->
                            pendingRequest = null
                            sendData(packetId, json)
                        }
                    }
                } else {
                    moduleId = null
                    vconnOpen = false
                }
            }
        }
    }

    /** 注销 VConn 数据监听与 module 回调,清理状态。 */
    fun detach() {
        deviceManager.removeVConnListener(vConnListener)
        deviceManager.onModuleAvailability = null
        moduleId = null
        vconnOpen = false
        pendingRequest = null
    }

    fun getAppList(pageSize: Int = 100) {
        if (!send(ID_GETLIST_REQ, """{"pageSize":$pageSize}""")) {
            // module 未就绪(VConn 未打开)——缓存请求,待 VConn 打开后补发。
            pendingRequest = ID_GETLIST_REQ to """{"pageSize":$pageSize}"""
        }
    }

    fun getSystemInfo() = send(ID_GET_SYSTEMINFO, "{}")

    fun getAppInfo(packageName: String) = send(ID_GETAPPINFO_REQ, """{"packageName":"${jsonEscape(packageName)}"}""")

    fun openApp(packageName: String) = send(ID_OPENAPP_REQ, """{"packageName":"${jsonEscape(packageName)}"}""")

    fun uninstall(packageName: String) = send(ID_UNINSTALL_REQ, """{"packageName":"${jsonEscape(packageName)}"}""")

    fun installByUrl(
        packageName: String,
        apkUrl: String,
        appName: String = "",
        iconUrl: String = "",
        apkSize: String = "",
        versionNeeded: Int = 0,
    ) = send(
        ID_INSTALL_REQ,
        """{"packageName":"${
            jsonEscape(
                packageName,
            )
        }","apkUrl":"${
            jsonEscape(
                apkUrl,
            )
        }","iconUrl":"${
            jsonEscape(
                iconUrl,
            )
        }","appName":"${
            jsonEscape(
                appName,
            )
        }","apkSize":"${jsonEscape(apkSize)}","versionNeeded":$versionNeeded}""",
    )

    fun cancelInstall(packageName: String) = send(ID_INSTALL_CANCEL, """{"packageName":"${jsonEscape(packageName)}"}""")

    /**
     * 发送请求。module 就绪(VConn 已打开)时立即发送返回 true;
     * 未就绪时缓存请求并返回 false(待 VConn 打开后补发)。
     */
    private fun send(
        packetId: Int,
        json: String,
    ): Boolean {
        val mid = moduleId ?: return false
        sendData(packetId, json)
        return true
    }

    private fun sendData(
        packetId: Int,
        json: String,
    ) {
        val mid = moduleId ?: return
        val body = json.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + body.size)
        buf.putInt(packetId)
        buf.putInt(requestId.getAndIncrement())
        buf.put(body)
        deviceManager.connection?.sendVConnData(mid, buf.array())
    }

    private fun handle(payload: ByteArray) {
        if (payload.size < 8) return
        val buf = ByteBuffer.wrap(payload)
        val id = buf.int
        buf.int // requestId
        val json = String(payload, 8, payload.size - 8, Charsets.UTF_8)
        val j = parseJsonObject(json)
        when (id) {
            ID_GETLIST_RESP -> {
                val apps = parseAppArray(j.str("apps"))
                onAppList?.invoke(apps)
            }

            ID_SYSTEMINFO -> onSystemInfo?.invoke(j.toMap())
            ID_INSTALL_STATUS ->
                onInstallProgress?.invoke(
                    InstallProgress(j.str("packageName"), j.int("progress"), j.str("appStatus")),
                )

            ID_INSTALL_RESP -> onOpResult?.invoke("install", j.str("packageName"), j.int("errorCode"))
            ID_UNINSTALL_RESP -> onOpResult?.invoke("uninstall", j.str("packageName"), j.int("errorCode"))
            ID_OPENAPP_RESP -> onOpResult?.invoke("open", j.str("packageName"), j.int("errorCode"))
        }
    }

    private fun parseAppArray(arr: String): List<TvApp> {
        // apps is a JSON array of objects; extract each object block and flat-parse it
        val out = mutableListOf<TvApp>()
        val body = arr.removePrefix("[").removeSuffix("]")
        var depth = 0
        var start = -1
        for (i in body.indices) {
            when (body[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val o = parseJsonObject(body.substring(start, i + 1))
                        out.add(
                            TvApp(
                                packageName = o.str("packageName"),
                                appName = o.str("appName"),
                                versionName = o.str("versionName"),
                                versionCode = o.str("versionCode").toLongOrNull() ?: 0,
                                iconUrl = o.str("iconUrl"),
                                status = o.str("appStatus"),
                            ),
                        )
                    }
                }
            }
        }
        return out
    }
}
