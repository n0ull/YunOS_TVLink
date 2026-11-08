# TVLink

[![Build & Test](https://github.com/n0ull/YunOS_TVLink/actions/workflows/build.yml/badge.svg)](https://github.com/n0ull/YunOS_TVLink/actions/workflows/build.yml)

TVLink 是 YunOS 电视/盒子的局域网控制客户端，支持 Android 与 Windows 桌面。项目依据 `com.yunos.tvhelper` v5.2.2 的逆向分析编写原创实现，不包含原 App 的代码、素材或商标。

## 功能与验证

| 功能 | Android | Desktop | 真机状态 |
|---|:---:|:---:|---|
| mDNS + /24 子网发现（IDC 13511、IB 3988） | 支持 | 支持 | 已通过 |
| 按键、触控板、手柄、方向盘 | 支持 | 支持 | 已通过 |
| 体感遥控 | 支持 | — | 已通过 |
| 远程文字输入 | 支持 | 支持 | 测试固件无此能力 |
| 本地媒体投屏与播放控制 | 支持 | 支持 | 已通过 |
| 电视截屏、显示与手动保存 | 支持 | 支持 | 已通过 |
| 应用列表、打开、卸载、URL 推装 | 支持 | 支持 | 测试固件无 appstore 模块 |
| 文本语音指令（电视端 NLU） | 支持 | 支持 | 已通过 |
| MagicCast BLE 配网 | 支持 | — | 未验证 |

“支持”表示代码路径已实现，不代表所有固件均提供对应能力。设备型号、测试记录和阻塞原因见 [`TODO.md`](TODO.md)。

## 架构

本节描述当前实现。双方共同确认的目标架构与演进约束见 [`docs/architecture.md`](docs/architecture.md)；目标设计不计入当前实现或真机验证状态。

两个应用壳只负责平台启动与生命周期，均进入共享的 `App()`：

```text
androidApp / desktopApp
        ↓
App → AppViewModel → Feature / device service → protocol
```

- `AppViewModel` 负责导航、模块装配、跨功能连接生命周期和全局通知。
- `Discovery` 直接拥有扫描状态和候选设备；`DeviceSelection` 拥有当前目标、IDC 会话、连接状态、重连以及模块/VConn 回调分发。
- `RcController` 拥有 IB 会话；`CastFeature` 拥有投屏控制会话和 HTTP 媒体服务。
- `RpmService`、`DongleSettingService`、`AsrTextService` 各自管理业务模块的 VConn 打开策略。
- `ShotFeature`、`SysPropFeature`、`RemoteFeature` 持有对应 UI 状态；应用列表直接由 `RpmService.appList` 提供。
- 协议编解码分别归 `proto/idc`、`proto/ib`、`proto/cast`、`proto/mdns`，不共用虚构的通用 Socket 基类。

源码集与职责：

| 源集/目录 | 职责 |
|---|---|
| `shared/src/jvmCommonMain` | 协议、设备协调、共享 Compose UI、平台能力 `expect` |
| `shared/src/androidMain` | Android `actual`、BLE、传感器与系统能力 |
| `shared/src/desktopMain` | Desktop `actual`、AWT/Skia 与系统存储 |
| `shared/src/*/kotlin/app/tvlink/platform` | 跨平台持久化及 Android application context |
| `shared/src/desktopTest` | JVM 协议与状态行为测试 |

只有真实平台差异使用 `expect/actual`。双端一致的 UI（包括文本语音指令）直接放在 `jvmCommonMain`。

## 构建与运行

要求：JDK 17、Android SDK 36。Android SDK 路径写入本机 `local.properties` 的 `sdk.dir`。

Windows 使用 `gradlew.bat`；Unix 使用 `./gradlew`。以下示例使用 Unix 形式：

```bash
./gradlew :androidApp:assembleDebug   # Android APK
./gradlew :desktopApp:run             # 运行桌面端
./gradlew :desktopApp:distribNoJre    # Windows 便携包，不含 JRE
./gradlew :shared:desktopTest         # JVM 行为测试
./gradlew check                       # 测试 + ktlint + detekt + Android lint
./gradlew ktlintFormat                # 格式化
```

CI 与本地均使用 `check` 作为质量门禁；工作流见 [`.github/workflows/build.yml`](.github/workflows/build.yml)。

## 使用

1. 让手机/电脑与电视处于同一局域网。
2. 无可用历史连接时进入设备页，点击搜索；也可手动输入 IP。启动时不会自动扫描。
3. 同时发现 IDC 与 IB 的设备使用完整功能；仅 IB 可达的设备可遥控，但不能使用 IDC/投屏能力。
4. 投屏时选择本地媒体，电视通过内置 HTTP 服务回拉。
5. 截屏先显示在页面中，点击“保存”后才写入本地。

## 文档

- [`docs/re/`](docs/re/)：协议与逆向分析基准；修改协议行为前必须核对。
- [`docs/adr/`](docs/adr/)：少量持续有效的架构约束。
- [`TODO.md`](TODO.md)：活跃待办、固件限制与历史真机验证索引。
- [`docs/field-test-2026-07-15.md`](docs/field-test-2026-07-15.md)：历史测试记录。

## 范围与许可

仅实现局域网内可闭环的能力；账号、支付、云端搜索与推送不在范围内。

本项目用于学习协议与私人设备互操作，与原 App 厂商无关联。私人学习用途，未开源许可；未经授权不得用于商业分发。
