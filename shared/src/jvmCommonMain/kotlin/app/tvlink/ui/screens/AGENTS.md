<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-24 -->

# screens

## Purpose

One `@Composable` function per application screen. Each screen observes `AppViewModel`
state and renders UI — no business logic lives here.

## Key Files

| File                    | Description                                                                                  |
|-------------------------|----------------------------------------------------------------------------------------------|
| `DevicePickerScreen.kt` | Device discovery list — 展示 IB 探测字段(`IB v3.29`、`sid=xxx`),IB-only 设备标"电视 (IB 通道)" |
| `RemoteScreen.kt`       | Remote control — 五模式切换:按键/触控板/手柄/方向盘/体感,经 RcController(IB 优先,IDC 兜底)      |
| `CastScreen.kt`         | Local media projection — file pick, play/pause/seek, playback status                         |
| `MoreScreen.kt`         | "更多" tab — ListItem 菜单:截屏/应用/(Android)魔投配网/设置,进入 MoreSub 子屏                  |
| `ScreenshotScreen.kt`   | TV screenshot capture and display/save                                                       |
| `AppsScreen.kt`         | TV app management — 刷新触发 VConn 自动打开 + 挂起请求补发,列表/打开/卸载/推装                  |
| `SettingsScreen.kt`     | 连接信息(含 IB 版本 + IB sid)/系统属性查询(SysProp)/关于                                       |

## For AI Agents

### Working In This Directory

- Screens receive `AppViewModel` as parameter — never construct services directly
- State observation: `val state by viewModel.xxx.collectAsState()`
- User actions: call `viewModel.someAction()` — ViewModel handles threading
- Add new screens: create file here;主 tab 接线在 `MainShell.kt`,More 子屏挂在 `MoreScreen.kt` + `AppViewModel` 的 MainTab/MoreSub

### Common Patterns

- `Scaffold` + `TopAppBar` per screen
- `LaunchedEffect` for one-shot operations (start scan on screen enter)
- `LazyColumn`/`LazyVerticalGrid` for lists

<!-- MANUAL: -->
