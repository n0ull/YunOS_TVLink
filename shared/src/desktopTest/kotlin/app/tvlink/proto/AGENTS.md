<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

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
| `CastControllerTest.kt`        | 回环假 TV:`play()` 后轮询 `GET /playback-info` 驱动 onEvent(state/duration/position) |
| `MdnsTest.kt`                  | mDNS 响应包解析(PTR/TXT)与查询包格式校验(捕获字节数组为夹具)                 |
| `MediaHttpServerTest.kt`       | 内嵌 HTTP 服务器:完整文件服务 + Range 请求(206 分段)、Content-Length           |

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
