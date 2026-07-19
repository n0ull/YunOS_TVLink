<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# ib

## Purpose

IB (InputBoost) fast input channel — low-latency TCP 3988 connection for remote-control key events and motion sensor
data. Preferred over IDC for input due to lower overhead. Falls back to IDC OpCmd_Key when unavailable.

## Key Files

| File           | Description                                                                          |
|----------------|--------------------------------------------------------------------------------------|
| `IbChannel.kt` | TCP 3988 session: connect/hello handshake, keepalive, key/motion send, reader thread |

### commonMain counterpart

| File        | Location                              | Description                                                           |
|-------------|---------------------------------------|-----------------------------------------------------------------------|
| `IbData.kt` | `shared/src/commonMain/.../proto/ib/` | Key code constants (`RcKey`) and data types shared across source sets |

## For AI Agents

### Working In This Directory

- **Frame format**: 20-byte header (magic=0x11223399, size, type, reserve, checksum= (size+reserve)^helloId) + text body
- Checksum depends on `helloId` received during handshake — store it after connect
- Key codes in `RcKey` (commonMain) — see `docs/re/02-remote-control.md` for full list
- `tcpNoDelay = true` is critical for input latency
- Reference: `docs/re/02-remote-control.md`

### Testing Requirements

- Frame checksum logic should be unit-tested (deterministic given helloId)

### Common Patterns

- `synchronized(sendLock)` for write path
- Keepalive thread sends periodic PING frames
- `@Volatile` state + `onStateChanged` callback

## Dependencies

### Internal

- `app.tvlink.proto.ib.IbData` (commonMain — RcKey constants)

### External

- java.net.Socket, java.nio.ByteBuffer

<!-- MANUAL: -->
