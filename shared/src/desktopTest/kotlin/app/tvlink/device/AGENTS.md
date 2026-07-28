<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-28 | Updated: 2026-07-28 -->

# device (desktopTest)

## Purpose

JVM unit tests for the high-level `app.tvlink.device` service facades: ASR text streaming and dongle settings.
No real TV or network required — loopback/mocked where applicable.

## Key Files

| File                       | Description                                                                                     |
|----------------------------|-------------------------------------------------------------------------------------------------|
| `AsrTextServiceTest.kt`    | ASR 文本服务:VConn 首包自动 SYN + `asr_streaming` 分包发送 + NLU 结果回调                        |
| `DongleSettingServiceTest.kt` | 魔投配网设置:BLE 配对流程模拟 + WiFi SSID/密码写入 GATT 特征验证                              |

## For AI Agents

### Working In This Directory

- 运行:`./gradlew :shared:desktopTest`
- 用 `kotlin.test`(`@Test`、`assertEquals`、`assertContentEquals`、`assertTrue`)
- 服务层测试依赖 `DeviceManager` mock/stub — 不发真实网络请求
- detekt 对测试同样生效(复杂度/loop break 限制),假服务端逻辑拆小函数

### Common Patterns

- AAA(Arrange-Act-Assert)结构
- `CoroutineScope` + `runTest` 驱动协程服务生命周期
- 回调断言:`CountDownLatch`/`CompletableDeferred` 等异步结果

## Dependencies

### Internal

- `app.tvlink.device`(jvmCommonMain)— 被测服务(AsrTextService, DongleSettingService)
- `app.tvlink.proto.idc` / `app.tvlink.proto.ib` — 底层连接 mock

### External

- kotlin.test, kotlinx-coroutines-test

<!-- MANUAL: -->
