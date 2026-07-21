<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-21 -->

# idc

## Purpose

IDC (Intelligent Device Control) protocol — the primary TCP control channel to YunOS TVs on port 13510. Handles
connection, login handshake, module enumeration, and command dispatch. All higher-level services (remote control
fallback, RPM, screenshot, ASR) ride on IDC frames.

## Key Files

| File               | Description                                                                                                                                           |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `IdcConnection.kt` | TCP session management: connect, login, reader thread, heartbeat; `send()` queues onto single-thread executor (`idc-send`, FIFO preserved); 提供 `onModuleChanged`/`openVConn` 管理 VConn 生命周期 |
| `IdcPackets.kt`    | Packet data classes, serialization, and JSON utilities (`parseJsonObject`/`FlatJson` via kotlinx.serialization, `jsonEscape`)                         |
| `IdcCrypto.kt`     | connKey derivation and optional encryption — DEAD CODE (ver=0 plaintext always; 真机已验证无加密可截屏); do not modify without ver≠0 TV                 |

## For AI Agents

### Working In This Directory

- **Frame format**: 16-byte header (magic=130311, key, packetId, totalLen) + body; all big-endian
- Login flow: LoginReq(10000)→LoginResp(10100); after login, `connKey` becomes the frame key field (every packet must carry it)
- Strings/byte arrays: 4-byte length prefix + content
- **Cmd packet framing**: CmdReqBase family (ScreenShot/SysProp/PackageInfo/PathInfo) body = `LPString({"cmdReqID":N})` + `LPString({params})`; `Cmd_LaunchSth`(20400) is the exception — single LPString, sent raw without req/resp pairing
- `IdcConnection` callbacks fire on the reader thread — callers must dispatch to UI thread
- **VConn 生命周期**: `openVConn(moduleId)` 发 `VConnSyn` 打开虚拟连接 → `sendVConnData()` 收发数据 → `closeVConn()` 关闭; `ModuleAvailability` 包更新 `modules` map 并触发 `onModuleChanged(moduleId, name, online)` 回调
- **Module 在线感知**: 上层 service(RpmService)通过 `DeviceManager.onModuleAvailability` 感知 module 上线后主动 `openVConn()`,打破"需 moduleId 才能发 VConnSyn、需发 VConnSyn 模块才在线"的死锁
- Reference: `docs/re/01-device-discovery.md`, `docs/re/02-remote-control.md`

### Testing Requirements

- Test files: `IdcProtocolTest.kt` (frame round-trips), `IdcConnectionLeakTest.kt` (no thread leaks), `IdcConnectionTest.kt` (async send via loopback, Cmd framing)
- Test frame encode/decode round-trips, especially header field ordering

### Common Patterns

- `DataInputStream` / `OutputStream` with manual big-endian read/write
- `AtomicInteger` for packet ID sequencing
- Scheduler lazily created in `startHeartbeat()`; `close()` calls `shutdownNow()` — no leaked threads
- JSON parsing: `parseJsonObject(s)` → `FlatJson` facade over `JsonObject` (kotlinx.serialization, isLenient=true)
- Test files: `IdcProtocolTest.kt` (frame round-trips), `IdcConnectionLeakTest.kt` (no thread leaks)

## Dependencies

### Internal

- `app.tvlink.proto.ib` (IbData shared types via commonMain)

### External

- java.net.Socket, java.io.DataInputStream/OutputStream

<!-- MANUAL: -->
