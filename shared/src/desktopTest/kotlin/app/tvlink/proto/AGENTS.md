<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-08-18 -->

# proto (desktopTest)

## Purpose

JVM unit tests for the `app.tvlink.proto` protocol layer: IDC frame wire format, loopback socket behavior (fake TV on
127.0.0.1), thread-leak guards, Cast playback polling, mDNS packet parsing, and the embedded media HTTP server. No
real TV or network required.

## Key Files

| File                           | Description                                                                     |
|--------------------------------|---------------------------------------------------------------------------------|
| `IdcProtocolTest.kt`           | IDC 帧编解码往返 + 头部字段校验(loginReq/heartBeat/loginResp/vconnData/AES 派生) |
| `IdcConnectionTest.kt`         | 回环假 TV:异步发送(FIFO/connKey 有序/close 后不崩)+ Cmd 截图帧格式             |
| `IdcConnectionLeakTest.kt`     | `IdcConnection` detect/连接失败后无 `idc-hb` 心跳线程泄漏                        |
| `IdcFrameSkipTest.kt`          | 单帧畸形(LPString 越界)跳过不拆连:后续正常帧照达、会话存活                    |
| `IdcStreamDesyncTest.kt`       | 另一侧护栏:magic 失步仍拆连(连接保持开放,验证非 EOF 触发)                   |
| `IbChannelTest.kt`             | IB 假服务(固定 3988):握手不应答 soTimeout 快速失败 + hello 路径(sid/ver 解析、稳态空闲不掉线) |
| `CastControllerTest.kt`        | 回环假 TV:`play()` 后轮询 `GET /playback-info` 驱动 onEvent + content-length 违规(超限/短 body/负值/超 Int)快速失败 |
| `MdnsTest.kt`                  | mDNS 响应包解析(PTR/TXT)、查询包格式校验、外来服务应答不入列                  |
| `MediaHttpServerTest.kt`       | 内嵌 HTTP 服务器:Range(206)/416、来源 IP 正反过滤、反复拉取全供片、Content-Length |
| `RpmFixTest.kt`                | RPM 修复回归 R1–R4:模块名常量 / LaunchSth 唤醒帧 / ModuleAvailability JSON m_name 分支 / apps 单对象兼容 |
| `DeviceManagerConnectTest.kt`  | 重叠 connect 单飞 + 显式断开不复活(假 TV 绑固定 13510;tearDown 清理 prefs 节点) |

## For AI Agents

### Working In This Directory

- 运行:`./gradlew :shared:desktopTest`
- 用 `kotlin.test`(`@Test`、`assertEquals`、`assertContentEquals`、`assertTrue`)
- Loopback 假 TV:`ServerSocket(0)` on 127.0.0.1,守护线程 + `CountDownLatch` 做异步断言,勿发真实网络请求
- 协议夹具用 `docs/re/` 捕获的字节数组(`byteArrayOf(...)`)
- detekt 对测试同样生效(复杂度/loop break 限制),假服务端逻辑拆小函数

### Common Patterns

- AAA(Arrange-Act-Assert)结构
- 往返:`encode(obj) → bytes → decode(bytes) → assertEquals(obj, result)`
- 泄漏检测:操作后 `Thread.getAllStackTraces()` 计数命名线程 == 0

## Dependencies

### Internal

- `app.tvlink.proto.*`(jvmCommonMain)— 被测对象(IDC/IB/Cast/mDNS/MediaHttpServer)

### External

- kotlin.test, java.net (ServerSocket/Socket), java.util.concurrent (CountDownLatch)

<!-- MANUAL: -->
