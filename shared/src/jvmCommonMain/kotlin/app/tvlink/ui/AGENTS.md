<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# ui

## Purpose

Compose Multiplatform UI layer — shared across Android and Desktop. Contains the app composable, central ViewModel, all
screens, theme, icons, and platform-abstracted widgets.

## Key Files

| File              | Description                                                                                     |
|-------------------|-------------------------------------------------------------------------------------------------|
| `App.kt`          | Root `@Composable` — TvTheme(遥控 tab 恒深)+ BackHandler + Snackbar;DevicePicker / MainShell 分发,提供 AppViewModel |
| `AppViewModel.kt` | App 级协调者: 导航/连接生命周期/服务注册/Feature 装配; 暴露 `connectedIbVer`/`connectedIbSid`(IB 探测诊断信息);重连耗尽终态 FAILED 时 snackbar 提示(延迟复核,重试窗口内瞬态不报)  |
| `CastFeature.kt`  | 投屏功能状态持有者: 控制通道单飞建连(connectMutex + 世代号:锁顶与装回前双校验防断开竞态复活/误杀新通道;失败分支接管媒体服务器清理) + 媒体服务器(仅服务已连 TV IP,下次投屏清旧条目) + `CastUiState` 密封状态;**通道死亡自愈**(TV 杀会话/半开):`awaitChannel` 只认 CONNECTED,`file()` 经 `healChannel()` 按 `lastTarget` 重建一次;setMedia 失败且通道已死时重建重试(重建重启媒体服务须重注册条目);`connecting` 在 connect() 入口同步置位防 awaitChannel 竞态误判 |
| `ShotFeature.kt`  | 截屏功能状态持有者: `ShotUiState` 密封状态 + 兜底超时复位;断线时 showNotice 提示不静默;兜底超时经 `service.dropConnectionIfUnresponsive()` 探活,判死即断开走自动重连并提示「正在自动重连」(真机截屏偶发失败回归) |
| `SysPropFeature.kt` | 属性查询状态持有者: `SysPropUiState` 密封状态;断线时 showNotice 提示不静默 |
| `RemoteFeature.kt` | 遥控状态持有者: IME 输入态 + 按键/语音转发;双通道不可用时按键提示「未连接电视」不静默丢弃 |
| `AppsFeature.kt`  | TV 应用管理状态持有者: 应用列表流 + RPM 结果通知 |
| `MainShell.kt`    | 连接后主壳 — 遥控/投屏/更多三 tab;窄屏 NavigationBar,≥600dp 宽屏 NavigationRail                 |

## Subdirectories

| Directory  | Purpose                                                                     |
|------------|-----------------------------------------------------------------------------|
| `icons/`   | 内嵌 Material Symbols(Rounded) 图标 (see `icons/AGENTS.md`)                  |
| `screens/` | One `@Composable` per app screen (see `screens/AGENTS.md`)                  |
| `theme/`   | Material3 theme configuration (see `theme/AGENTS.md`)                       |
| `widgets/` | Platform-abstracted widgets via `expect`/`actual` (see `widgets/AGENTS.md`) |

## For AI Agents

### Working In This Directory

- `AppViewModel` 为 App 级协调者;每屏功能状态/动作在该屏的 Feature 状态持有者(`vm.cast`/`vm.shot`/`vm.props`/`vm.remote`/`vm.apps`),随 AppViewModel 构造与 onCleared — screens observe state, never own business logic
- Navigation: `Screen.DevicePicker` / `Screen.Main(tab, moreSub)`;tab 切换由 `MainShell` 承载(NavigationBar 窄屏 / NavigationRail ≥600dp 宽屏)
- Platform-specific UI needs `expect`/`actual` in `widgets/Platform.kt`
- All screens are in `jvmCommonMain` — both platforms render identical UI
- **BackHandler**: `App()` 仅在 Main 且(有 moreSub 或非遥控 tab)时拦截返回键调 `vm.navBack()`;遥控 tab 根不拦截,交系统默认(退出/最小化)
- **IB 诊断信息**: `AppViewModel.connectedIbVer`/`connectedIbSid` 来自 3988 探测响应,供 SettingsScreen 展示
- **断线反馈不静默**: Feature 持有者经 `showNotice` 上抛 snackbar(Cast/Shot/SysProp/Remote/Apps 均带);服务返回 false(无连接)时必须提示,不允许静默 return

### Common Patterns

- `viewModelScope.launch(Dispatchers.IO)` for protocol calls from UI events
- `collectAsState()` on `StateFlow` from device services
- Service data arrives as `StateFlow`/`SharedFlow` → `AppViewModel.collectInVm` copies into `mutableStateOf` → automatic recomposition (no service→VM callbacks)

## Dependencies

### Internal

- `app.tvlink.device` — all service facades
- `app.tvlink.proto.ib` — RcKey constants for remote screen

### External

- Compose Material3, Foundation, Runtime
- androidx.lifecycle ViewModel + viewModel-compose

<!-- MANUAL: -->
