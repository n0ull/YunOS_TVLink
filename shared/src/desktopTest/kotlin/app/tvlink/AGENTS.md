<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# app.tvlink (desktopTest)

## Purpose

Unit tests for the protocol layer, running on the JVM (desktop) target. Tests frame serialization, mDNS packet parsing,
loopback socket behavior, and HTTP server behavior without requiring a real TV.

## Key Files

| File                           | Description                                                                     |
|--------------------------------|---------------------------------------------------------------------------------|
| `proto/IdcProtocolTest.kt`     | IDC frame encode/decode round-trips, header field validation                    |
| `proto/IdcConnectionLeakTest.kt` | IdcConnection close/disconnect 后无线程泄漏                                     |
| `proto/IdcConnectionTest.kt`   | 回环假 TV:异步发送(FIFO/connKey/close 后不崩)+ Cmd 截图帧格式                  |
| `proto/IdcFrameSkipTest.kt`    | 单帧畸形跳过不拆连:后续正常帧照达、会话存活                                    |
| `proto/IbChannelTest.kt`       | IB 握手 soTimeout 快速失败 + hello 正常路径(固定端口假服务)                    |
| `proto/CastControllerTest.kt`  | 回环假 TV:play() 后轮询 playback-info 驱动 onEvent + content-length 违规快速失败 |
| `proto/MdnsTest.kt`            | mDNS response packet parsing + 外来服务应答不入列                               |
| `proto/MediaHttpServerTest.kt` | Embedded HTTP server Range/416、来源 IP 过滤、供片后注销                        |
| `proto/RpmFixTest.kt`          | RPM 修复回归 R1–R4:模块名常量 / LaunchSth 唤醒帧 / ModuleAvailability JSON m_name 分支 / apps 单对象兼容 |
| `proto/DeviceManagerConnectTest.kt` | 重叠 connect 单飞:落败方会话关闭、胜方接管(假 TV 绑固定 13510)              |
| `device/AsrTextServiceTest.kt` | ASR 文本服务:VConn 首包自动 SYN + `asr_streaming` 分包发送 + NLU 结果回调        |
| `device/DongleSettingServiceTest.kt` | 魔投配网设置:BLE 配对流程模拟 + WiFi SSID/密码写入 GATT 特征验证              |
| `ui/CastFeatureDisconnectTest.kt` | onDisconnected 与在途 connect 竞态:断开后不复活幽灵通道/媒体服务/UI          |
| `ui/UiSilentFailureTest.kt`    | 断线时截屏/连拍/属性查询/按键均 showNotice 提示(真服务件+无连接 DeviceManager) |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `proto/` | 协议层测试(IDC/IB/Cast/mDNS/HTTP 服务器/建连单飞) (see `proto/AGENTS.md`) |
| `device/` | 服务层测试(ASR/魔投配网) (see `device/AGENTS.md`) |
| `ui/`    | UI Feature 状态持有者测试(投屏断开竞态/静默失败提示) (see `ui/AGENTS.md`) |

## For AI Agents

### Working In This Directory

- Run tests: `./gradlew :shared:desktopTest`
- Use `kotlin.test` (`@Test`, `assertEquals`, `assertContentEquals`)
- Protocol tests should use captured byte arrays from `docs/re/` as fixtures
- Loopback tests: `ServerSocket(0)` on 127.0.0.1 as fake TV — no real network calls
- detekt 对测试同样生效(复杂度/loop break 限制),假服务端拆小函数写

### Common Patterns

- AAA (Arrange-Act-Assert) structure
- Round-trip: `encode(obj) → bytes → decode(bytes) → assertEquals(obj, result)`
- Captured packets as `byteArrayOf(...)` constants
- Fake server on daemon thread + `CountDownLatch`/`ArrayBlockingQueue` for async assertions

<!-- MANUAL: -->
