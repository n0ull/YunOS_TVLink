<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# screens

## Purpose

One `@Composable` function per application screen. Each screen observes `AppViewModel`
state and renders UI — no business logic lives here.

## Key Files

| File                    | Description                                                                                  |
|-------------------------|----------------------------------------------------------------------------------------------|
| `DevicePickerScreen.kt` | Device discovery list — 展示 IB 探测字段(`IB v3.29`、`sid=xxx`),IB-only 设备标"电视 (IB 通道)" |
| `RemoteScreen.kt`       | Remote control — 五模式切换:按键/触控板/手柄/方向盘/体感,经 RcController(IB 优先,IDC 兜底);按键页=经典遥控分区(电源/魔键贴顶,dpad+导航+音量作为整体居中,间距固定不随屏高漂移);手柄页=Xbox 手柄映射(肩键交错 LB/RB 外 LT/RT 内,左列 L 摇杆+十字键、右列 ABXY 菱形+R 摇杆,SELECT/START 为 `PillButton` 胶囊键;LS/RS 协议无键码不布局);触控板无跟随圆点;`Stick` 行程=(盘−帽)/2 防帽沿越界;所有 `RcButton`/`PillButton` 点击经 `keyVibrate()`(Vibrator 直振,EFFECT_CLICK 29+,不受系统「触摸反馈」开关抑制;曾用 LocalHapticFeedback/VirtualKey 被该开关吞掉);语音按钮为底部居中单行(原 BottomAppBar 空栏已删) |
| `CastScreen.kt`         | Local media projection — file pick, play/pause/seek, playback status                         |
| `MoreScreen.kt`         | "更多" tab — ListItem 菜单:截屏/应用/(Android)魔投配网/设置,进入 MoreSub 子屏                  |
| `ScreenshotScreen.kt`   | TV screenshot capture and display/save                                                       |
| `AppsScreen.kt`         | TV app management — 刷新触发 VConn 自动打开 + 挂起请求补发,列表/打开/卸载/推装                  |
| `SettingsScreen.kt`     | 连接信息(含 IB 版本 + IB sid)/系统属性查询(SysProp)/关于                                       |

## For AI Agents

### Working In This Directory

- Screens receive `AppViewModel` as parameter — never construct services directly
- 屏级状态/动作走对应 Feature(`vm.cast.*`/`vm.shot.*`/`vm.props.*`/`vm.remote.*`/`vm.apps.*`);连接/导航走 `vm.*`
- User actions: call feature methods — Feature/ViewModel handles threading
- Add new screens: create file here;主 tab 接线在 `MainShell.kt`,More 子屏挂在 `MoreScreen.kt` + `AppViewModel` 的 MainTab/MoreSub

### Common Patterns

- `Scaffold` + `TopAppBar` per screen
- `LaunchedEffect` for one-shot operations (start scan on screen enter)
- `LazyColumn`/`LazyVerticalGrid` for lists

<!-- MANUAL: -->
