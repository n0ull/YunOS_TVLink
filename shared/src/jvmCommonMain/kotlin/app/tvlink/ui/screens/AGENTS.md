<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# screens

## Purpose

One `@Composable` function per application screen. Each screen observes `AppViewModel`
state and renders UI — no business logic lives here.

## Key Files

| File                    | Description                                                          |
|-------------------------|----------------------------------------------------------------------|
| `DevicePickerScreen.kt` | Device discovery list — scan, display found TVs, connect             |
| `HomeScreen.kt`         | Post-connection hub — device info, feature navigation grid           |
| `RemoteScreen.kt`       | Remote control — 五模式切换:按键/触控板/手柄/方向盘/体感,经 RcController(IB 优先,IDC 兜底) |
| `CastScreen.kt`         | Local media projection — file pick, play/pause/seek, playback status |
| `ScreenshotScreen.kt`   | TV screenshot capture and display/save                               |
| `AppsScreen.kt`         | TV app management — list, launch, uninstall, push-install by URL     |
| `SettingsScreen.kt`     | App settings (scan options, about)                                   |

## For AI Agents

### Working In This Directory

- Screens receive `AppViewModel` as parameter — never construct services directly
- State observation: `val state by viewModel.xxx.collectAsState()`
- User actions: call `viewModel.someAction()` — ViewModel handles threading
- Add new screens: create file here, add to `AppViewModel.Screen` sealed interface, wire in `App.kt`

### Common Patterns

- `Scaffold` + `TopAppBar` per screen
- `LaunchedEffect` for one-shot operations (start scan on screen enter)
- `LazyColumn`/`LazyVerticalGrid` for lists

<!-- MANUAL: -->
