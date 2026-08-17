<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-18 | Updated: 2026-08-18 -->

# ui (desktopTest)

## Purpose

JVM unit tests for `app.tvlink.ui` Feature 状态持有者（CastFeature/ShotFeature/SysPropFeature/RemoteFeature）—
不渲染 Compose UI，直构 Feature + 真服务件（无连接 `DeviceManager`）或回环假服务验证状态与通知行为。

## Key Files

| File                            | Description                                                                  |
|---------------------------------|------------------------------------------------------------------------------|
| `CastFeatureDisconnectTest.kt`  | onDisconnected 与在途 connect 竞态：终态无幽灵通道/媒体服务/UI（假投屏服务 accept-only） |
| `UiSilentFailureTest.kt`        | 断线时截屏/连拍/属性查询/按键均 showNotice 提示，不静默吞掉                      |

## For AI Agents

### Working In This Directory

- Feature 持有者构造只需 `CoroutineScope` + 服务件 + `showNotice` lambda；无连接 `DeviceManager()` 即「全通道不可用」夹具
- 竞态测试断言**终态**（settle 后），不断言瞬态——异步清理/建连落地顺序不做假设
- 假服务端用守护线程 + `CountDownLatch`；同 `proto/` 测试目录约定

## Dependencies

### Internal

- `app.tvlink.ui`(jvmCommonMain) — 被测 Feature 持有者与 `CastUiState`/`ShotUiState` 等密封状态
- `app.tvlink.device` — 真服务件（`ScreenshotService`/`SysPropService`/`RcController`/`AsrTextService`）

<!-- MANUAL: -->
