<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-24 -->

# ui

## Purpose

Compose Multiplatform UI layer — shared across Android and Desktop. Contains the app composable, central ViewModel, all
screens, theme, icons, and platform-abstracted widgets.

## Key Files

| File              | Description                                                                                     |
|-------------------|-------------------------------------------------------------------------------------------------|
| `App.kt`          | Root `@Composable` — TvTheme(遥控 tab 恒深)+ BackHandler + Snackbar;DevicePicker / MainShell 分发,提供 AppViewModel |
| `AppViewModel.kt` | Central state: 连接生命周期/导航/服务代理; 暴露 `connectedIbVer`/`connectedIbSid`(IB 探测诊断信息)  |
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

- `AppViewModel` is the single source of truth — screens observe its state, never own business logic
- Navigation: `Screen.DevicePicker` / `Screen.Main(tab, moreSub)`;tab 切换由 `MainShell` 承载(NavigationBar 窄屏 / NavigationRail ≥600dp 宽屏)
- Platform-specific UI needs `expect`/`actual` in `widgets/Platform.kt`
- All screens are in `jvmCommonMain` — both platforms render identical UI
- **BackHandler**: `App()` 仅在 Main 且(有 moreSub 或非遥控 tab)时拦截返回键调 `vm.navBack()`;遥控 tab 根不拦截,交系统默认(退出/最小化)
- **IB 诊断信息**: `AppViewModel.connectedIbVer`/`connectedIbSid` 来自 3988 探测响应,供 SettingsScreen 展示

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
