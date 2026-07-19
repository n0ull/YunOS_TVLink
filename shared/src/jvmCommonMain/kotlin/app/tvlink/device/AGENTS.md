<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# device

## Purpose

High-level service layer that wraps the low-level protocol classes into cohesive, UI-consumable facades. Manages device
lifecycle from discovery through connected sessions.

## Key Files

| File                   | Description                                                                          |
|------------------------|--------------------------------------------------------------------------------------|
| `DeviceManager.kt`     | Facade over Discovery + active IDC session; exposes `StateFlow<ConnState>` for UI    |
| `Discovery.kt`         | Dual-channel device discovery: mDNS multicast + /24 subnet TCP 13511 sweep           |
| `RcController.kt`      | Routes remote-control key events — IB fast channel preferred, IDC OpCmd_Key fallback |
| `RpmService.kt`        | Remote package management (list/install/uninstall apps) via IDC VConn JSON frames    |
| `ScreenshotService.kt` | TV screenshot capture: IDC Cmd 20900→21000, JPEG data reassembly                     |
| `AsrTextService.kt`    | Voice/text command forwarding via `asr_streaming` IDC messages                       |

## For AI Agents

### Working In This Directory

- Each service takes `DeviceManager` as its connection source
- VConn callbacks: `CopyOnWriteArrayList` multicast — services `addVConnListener`/`removeVConnListener` (RpmService uses
  attach/detach)
- `DeviceManager.ConnState` drives the entire app's connection lifecycle
- `DeviceManager.destroy()` cancels scope + releases connection; called from `AppViewModel.onCleared()`
- `RcController.destroy()` = detach + scope cancel
- `Discovery.FoundDevice` is immutable (`val` fields); `report()` merges via `ConcurrentHashMap.compute` + `copy()`
- `ScreenshotService.capture()` returns `Boolean` (false if no connection); caller guards `shotBusy`
- `RcController` implements the IB-first-then-IDC-fallback policy from `docs/re/02`

### Common Patterns

- `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per service
- `MutableStateFlow` for observable state consumed by Compose `collectAsState()`
- Thread-safe collections (`ConcurrentHashMap`, `AtomicInteger`) for callback-driven data

## Dependencies

### Internal

- `app.tvlink.proto.idc` — IDC connection and packet types
- `app.tvlink.proto.ib` — IB channel and key codes
- `app.tvlink.proto.cast` — Cast controller and media server
- `app.tvlink.proto.mdns` — mDNS query/response

### External

- kotlinx-coroutines (StateFlow, Dispatchers.IO)

<!-- MANUAL: -->
