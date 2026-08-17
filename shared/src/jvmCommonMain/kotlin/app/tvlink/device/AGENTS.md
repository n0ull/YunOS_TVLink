<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# device

## Purpose

High-level service layer that wraps the low-level protocol classes into cohesive, UI-consumable facades. Manages device
lifecycle from discovery through connected sessions.

## Key Files

| File                   | Description                                                                                                        |
|------------------------|--------------------------------------------------------------------------------------------------------------------|
| `DeviceManager.kt`     | Facade over Discovery + active IDC session; exposes `StateFlow<ConnState>` + `onModuleAvailability` 回调(Module 在线状态变化) |
| `Discovery.kt`         | 双通道设备发现: mDNS 组播 + /24 子网双探测(IDC 13511 + IB 3988 并行); 解析 IB hello 响应提取 `ibVer`/`ibSid`; `report()` 用 `?: return` 安全早退(不用 `!!` 断言); `probeHost` 去掉内层 Thread 包装，直接在池内顺序探测 |
| `ScreenshotService.kt` | TV screenshot capture: IDC Cmd 20900→21000, Cmd 帧格式已修正(真机已验证出图); 调用侧(`AppViewModel`)经 `ShotUiState.Capturing` + 兜底超时复位，防止截取状态永久 stuck |
| `RcController.kt`      | Routes key events — IB preferred (needIb313 keys additionally require server ver≥313), IDC OpCmd_Key fallback(真机已验证有效) |
| `RpmService.kt`        | Remote package management (list/install/uninstall apps); 自动 openVConn(module 在线时) + 挂起请求补发              |
| `AsrTextService.kt`    | Voice/text command forwarding via `com.yunos.tv.asr:etao` VConn module — 首包前自动 VConn SYN; sends finished `asr_streaming` packets; NLU runs on the TV |
| `DongleSettingService.kt` | 魔投配网设置服务:驱动 `dongle/DongleBlePairer`,读取当前 SSID、触发配网流程; UI 在 `DongleScreen` |
| `SysPropService.kt`    | 系统属性查询:经 IDC Cmd 读取 TV 端 `SysProp`,SettingsScreen 展示 |

## For AI Agents

### Working In This Directory

- Each service takes `DeviceManager` as its connection source
- VConn callbacks: `CopyOnWriteArrayList` multicast — services `addVConnListener`/`removeVConnListener` (RpmService uses
  attach/detach)
- **VConn 自动打开**: `RpmService.attach()` 注册 `DeviceManager.onModuleAvailability` 回调 → `onAppStoreModule()` 在 module(`MODULE_NAME` = "com.yunos.idc.appstore",线上名,见 `IdcConstant.java:6`)上线时经 `openVConnAndFlushPending()` 主动 `openVConn()` 并补发挂起请求; `getAppList()` 在 module 未就绪时缓存请求待 VConn 打开后补发
- **模块唤醒**: module 离线时首个 RPM 请求经 `wakeModuleIfNeeded()` 发一次 `CmdLaunchSth`(launch_type=1/service, action="yunos.appstore.startprocessservice";`RpmObserver.java:74-77` 原流程),module 下线/detach 复位; `ModuleAvailability.m_name` 兼容 `{"name","category"}` 字符串化 JSON(`IDC.java:360-368`); `parseAppArray` 兼容 `apps` 单对象(`IdcPacket_GetListResponse.java:62-65`,回归见 `RpmFixTest`)
- `DeviceManager.ConnState` drives the entire app's connection lifecycle
- **建连单飞**：`connect()`/`connectIbOnly()` 经 `connectMutex` 串行化（重叠 connect 曾泄漏落败方整条 IDC 会话）;替换会话时先 `connection = null` 再 `shutdown()` 旧会话——旧会话 DISCONNECTED 回调经 `connection === conn` 判据落空，不误判异常断开排队伪重连
- `DeviceManager.destroy()` cancels scope + releases connection; called from `AppViewModel.onCleared()`
- `RcController.destroy()` = detach + scope cancel
- `Discovery.FoundDevice` 含 `ibVer`/`ibSid`(IB 3988 探测产出,解析 hello 响应 body); `report()` 按 IP 合并,双通时 IDC 信息更丰富
- **IB 双通道探测**: 24 线程池跨 host 并行; 单 host 内 `probeHost` 顺序执行 `probeIdc()` → `probeIb()`(均在池线程内, 无内层 Thread 包装); IB 探测用轻量 raw socket 发 hello 帧并校验 response magic + type
- `ScreenshotService.capture()`/`captureBurst()` return `Boolean` (false if no connection); caller guards via `ShotUiState.Capturing` + showNotice 提示
- `RcController` implements the IB-first-then-IDC-fallback policy from `docs/re/02`

### Common Patterns

- `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per service
- `MutableStateFlow`(状态)/`MutableSharedFlow`(事件, tryEmit) — services expose flows, not callbacks; consumed by `AppViewModel.collectInVm`
- Thread-safe collections (`ConcurrentHashMap`, `AtomicInteger`) for callback-driven data

## Dependencies

### Internal

- `app.tvlink.proto.idc` — IDC connection and packet types
- `app.tvlink.proto.ib` — IB channel and key codes (RcController, Discovery IB probe)
- `app.tvlink.proto.mdns` — mDNS query/response (Discovery)

### External

- kotlinx-coroutines (StateFlow, Dispatchers.IO)

<!-- MANUAL: -->
