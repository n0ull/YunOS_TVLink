<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-21 -->

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
| `proto/CastControllerTest.kt`  | 回环假 TV:play() 后轮询 playback-info 驱动 onEvent(state/duration/position)     |
| `proto/MdnsTest.kt`            | mDNS response packet parsing from captured byte arrays                          |
| `proto/MediaHttpServerTest.kt` | Embedded HTTP server Range requests, Content-Length, file serving               |

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
