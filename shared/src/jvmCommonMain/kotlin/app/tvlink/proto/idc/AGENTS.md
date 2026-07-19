<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# idc

## Purpose

IDC (Intelligent Device Control) protocol — the primary TCP control channel to YunOS TVs
on port 13510. Handles connection, login handshake, module enumeration, and command dispatch.
All higher-level services (remote control fallback, RPM, screenshot, ASR) ride on IDC frames.

## Key Files

| File | Description |
|------|-------------|
| `IdcConnection.kt` | TCP session management: connect, login, reader thread, send/receive, keepalive |
| `IdcPackets.kt` | Packet data classes and serialization: LoginReq, OpCmdKey, ImeTextChange, etc. |
| `IdcCrypto.kt` | connKey derivation and optional encryption (this client uses `encryption_algorithm_ver=0` plaintext) |

## For AI Agents

### Working In This Directory

- **Frame format**: 16-byte header (magic=130311, key, packetId, totalLen) + body; all big-endian
- Login flow: DETECT→HELLO→LOGIN; after login, `connKey` becomes the frame key field
- Strings/byte arrays: 4-byte length prefix + content
- `IdcConnection` callbacks fire on the reader thread — callers must dispatch to UI thread
- Reference: `docs/re/01-device-discovery.md`, `docs/re/02-remote-control.md`

### Testing Requirements

- Test file: `shared/src/desktopTest/kotlin/app/tvlink/proto/IdcProtocolTest.kt`
- Test frame encode/decode round-trips, especially header field ordering

### Common Patterns

- `DataInputStream` / `OutputStream` with manual big-endian read/write
- `AtomicInteger` for packet ID sequencing
- `ScheduledFuture` for keepalive pings

## Dependencies

### Internal

- `app.tvlink.proto.ib` (IbData shared types via commonMain)

### External

- java.net.Socket, java.io.DataInputStream/OutputStream

<!-- MANUAL: -->
