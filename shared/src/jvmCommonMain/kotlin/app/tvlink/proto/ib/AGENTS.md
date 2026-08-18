<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# ib

## Purpose

IB (InputBoost) fast input channel — low-latency TCP 3988 connection for remote-control key events and motion sensor
data. Preferred over IDC for input due to lower overhead. Falls back to IDC OpCmd_Key when unavailable.

## Key Files

| File           | Description                                                                          |
|----------------|--------------------------------------------------------------------------------------|
| `IbChannel.kt` | TCP 3988 session: connect/hello handshake, keepalive, key/motion send, reader thread; `readFrame` 不校验 checksum——2026-08-18 真机实测 TV 下行所有帧恒 reserve=0/checksum=0(hello/cur_app/moduleinfo/keepalive 应答均如此),任何接收侧校验必杀通道(f076f90 校验因此移除),帧界防护由 magic+size 范围承担;发送侧仍按 (size+reserve)^helloId 计算,真机已验证接受;`connect()` 起始复位 helloId=0;握手期 soTimeout=timeoutMs,hello 完成后复位 0(对端不应答快速失败,稳态空闲不误伤) |

### commonMain counterpart

| File        | Location                              | Description                                                           |
|-------------|---------------------------------------|-----------------------------------------------------------------------|
| `IbData.kt` | `shared/src/commonMain/.../proto/ib/` | `IbConst` wire constants (port 3988, magic 0x11228899, frame types) + `RcKey` key-code table shared across source sets |

## For AI Agents

### Working In This Directory

- **Frame format**: 20-byte header (magic=0x11228899, size, type, reserve, checksum) + text body；文档公式 checksum=(size+reserve)^helloId 仅适用于**发送侧**——真机下行恒 reserve=0/checksum=0(2026-08-18 实测),接收侧禁止校验
- `helloId` 来自 hello 应答的 sid,仅用于发送帧 checksum;`connect()` 起始复位为 0
- Key codes in `RcKey` (commonMain) — see `docs/re/02-remote-control.md` for full list
- `tcpNoDelay = true` is critical for input latency
- Reference: `docs/re/02-remote-control.md`

### Testing Requirements

- Frame checksum logic should be unit-tested (deterministic given helloId) — 仅发送侧;接收侧校验已被真机证伪,勿再加回
- `IbChannelTest.kt`: 握手不应答快速失败(soTimeout) + hello 正常路径(sid/ver 解析、稳态空闲不掉线) + 真机零 checksum 约定(connect READY + cur_app 推送不杀通道)

### Common Patterns

- `sendBody` queues frames onto a single-thread executor (`ib-send`, FIFO preserved); handshake/keepalive write synchronously on their own threads
- `synchronized(sendLock)` guards the actual socket write
- Keepalive thread sends periodic PING frames
- `@Volatile` state + `onStateChanged` callback
- `disconnect()` shuts down the send executor along with reader/keepalive threads

## Dependencies

### Internal

- `app.tvlink.proto.ib.IbData` (commonMain — RcKey constants)

### External

- java.net.Socket, java.nio.ByteBuffer

<!-- MANUAL: -->
