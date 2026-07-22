<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# device

## Purpose

High-level service layer that wraps the low-level protocol classes into cohesive, UI-consumable facades. Manages device
lifecycle from discovery through connected sessions.

## Key Files

| File                   | Description                                                                                                        |
|------------------------|--------------------------------------------------------------------------------------------------------------------|
| `DeviceManager.kt`     | Facade over Discovery + active IDC session; exposes `StateFlow<ConnState>` + `onModuleAvailability` 回调(Module 在线状态变化) |
| `Discovery.kt`         | 双通道设备发现: mDNS 组播 + /24 子网双探测(IDC 13511 + IB 3988 并行); 解析 IB hello 响应提取 `ibVer`/`ibSid`        |
| `RcController.kt`      | Routes key events — IB preferred (needIb313 keys additionally require server ver≥313), IDC OpCmd_Key fallback(真机已验证有效) |
| `RpmService.kt`        | Remote package management (list/install/uninstall apps); 自动 openVConn(module 在线时) + 挂起请求补发              |
| `ScreenshotService.kt` | TV screenshot capture: IDC Cmd 20900→21000, Cmd 帧格式已修正(真机已验证出图)                                       |
| `AsrTextService.kt`    | Voice/text command forwarding via `com.yunos.tv.asr` VConn module — sends finished `asr_streaming` packets; NLU runs on the TV |

## For AI Agents

### Working In This Directory

- Each service takes `DeviceManager` as its connection source
- VConn callbacks: `CopyOnWriteArrayList` multicast — services `addVConnListener`/`removeVConnListener` (RpmService uses
  attach/detach)
- **VConn 自动打开**: `RpmService.attach()` 注册 `DeviceManager.onModuleAvailability` 回调 → `onAppStoreModule()` 在 module(`MODULE_NAME` = "com.yunos.idc.appstore",线上名,见 `IdcConstant.java:6`)上线时经 `openVConnAndFlushPending()` 主动 `openVConn()` 并补发挂起请求; `getAppList()` 在 module 未就绪时缓存请求待 VConn 打开后补发
- **模块唤醒**: module 离线时首个 RPM 请求经 `wakeModuleIfNeeded()` 发一次 `CmdLaunchSth`(launch_type=1/service, action="yunos.appstore.startprocessservice";`RpmObserver.java:74-77` 原流程),module 下线/detach 复位; `ModuleAvailability.m_name` 兼容 `{"name","category"}` 字符串化 JSON(`IDC.java:360-368`); `parseAppArray` 兼容 `apps` 单对象(`IdcPacket_GetListResponse.java:62-65`,回归见 `RpmFixTest`)
- `DeviceManager.ConnState` drives the entire app's connection lifecycle
- `DeviceManager.destroy()` cancels scope + releases connection; called from `AppViewModel.onCleared()`
- `RcController.destroy()` = detach + scope cancel
- `Discovery.FoundDevice` 含 `ibVer`/`ibSid`(IB 3988 探测产出,解析 hello 响应 body); `report()` 按 IP 合并,双通时 IDC 信息更丰富
- **IB 双通道探测**: `probeHost` 对每个 host 并行探测 IDC(13511) + IB(3988),避免串行叠加超时; IB 探测用轻量 raw socket 发 hello 帧并校验 response magic + type
- `ScreenshotService.capture()` returns `Boolean` (false if no connection); caller guards `shotBusy`
- `RcController` implements the IB-first-then-IDC-fallback policy from `docs/re/02`

### Common Patterns

- `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per service
- `MutableStateFlow` for observable state consumed by Compose `collectAsState()`
- Thread-safe collections (`ConcurrentHashMap`, `AtomicInteger`) for callback-driven data

## Dependencies

### Internal

- `app.tvlink.proto.idc` — IDC connection and packet types
- `app.tvlink.proto.ib` — IB channel and key codes (RcController, Discovery IB probe)
- `app.tvlink.proto.mdns` — mDNS query/response (Discovery)

### External

- kotlinx-coroutines (StateFlow, Dispatchers.IO)

<!-- MANUAL: -->
