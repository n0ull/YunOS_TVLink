<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# ui

## Purpose

Compose Multiplatform UI layer — shared across Android and Desktop. Contains the app composable, central ViewModel, all
screens, theme, and platform-abstracted widgets.

## Key Files

| File              | Description                                                                       |
|-------------------|-----------------------------------------------------------------------------------|
| `App.kt`          | Root `@Composable` — navigation host, provides AppViewModel                       |
| `AppViewModel.kt` | Central state: connection lifecycle, navigation, delegates to all device services |

## Subdirectories

| Directory  | Purpose                                                                     |
|------------|-----------------------------------------------------------------------------|
| `screens/` | One `@Composable` per app screen (see `screens/AGENTS.md`)                  |
| `theme/`   | Material3 theme configuration (see `theme/AGENTS.md`)                       |
| `widgets/` | Platform-abstracted widgets via `expect`/`actual` (see `widgets/AGENTS.md`) |

## For AI Agents

### Working In This Directory

- `AppViewModel` is the single source of truth — screens observe its state, never own business logic
- Navigation: `sealed interface Screen` + `var screen by mutableStateOf(...)`
- Platform-specific UI needs `expect`/`actual` in `widgets/Platform.kt`
- All screens are in `jvmCommonMain` — both platforms render identical UI

### Common Patterns

- `viewModelScope.launch(Dispatchers.IO)` for protocol calls from UI events
- `collectAsState()` on `StateFlow` from device services
- Callbacks from services → `mutableStateOf` update → automatic recomposition

## Dependencies

### Internal

- `app.tvlink.device` — all service facades
- `app.tvlink.proto.ib` — RcKey constants for remote screen

### External

- Compose Material3, Foundation, Runtime
- androidx.lifecycle ViewModel + viewModel-compose

<!-- MANUAL: -->
