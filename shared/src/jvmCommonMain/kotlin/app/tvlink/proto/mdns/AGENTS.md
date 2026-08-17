<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-08-18 -->

# mdns

## Purpose

mDNS/DNS-SD implementation for discovering YunOS TVs on the local network. Queries the
`_alitv_remote_control._tcp.local` service type and parses response records to extract device IP, name, and metadata.

## Key Files

| File      | Description                                                              |
|-----------|--------------------------------------------------------------------------|
| `Mdns.kt` | UDP multicast DNS query builder, response parser (A/PTR/SRV/TXT records) |

## For AI Agents

### Working In This Directory

- Multicast group: 224.0.0.251:5353 (standard mDNS)
- Service type: `_alitv_remote_control._tcp.local`
- **只在 PTR 命中本服务时建条目**（2026-08 H2 修复）：SRV/A/TXT 仅折叠进已建条目——打印机/Chromecast 等外来主机应答不得入列（原实现任何记录都 `getOrPut` 建条目 → 幽灵设备）
- Response parsing must handle partial/multi-packet responses gracefully
- Used by `Discovery.kt` as one of two discovery channels (mDNS + subnet sweep)
- Reference: `docs/re/01-device-discovery.md`

### Testing Requirements

- Test file: `shared/src/desktopTest/kotlin/app/tvlink/proto/MdnsTest.kt`
- Test DNS packet parsing with captured byte arrays

### Common Patterns

- `MulticastSocket` bound to 5353 with 500ms `soTimeout` poll loop; `localLanAddress()` prefers `wlan*` interfaces (Android)
- Manual DNS wire-format parsing incl. name compression (no external DNS library)

## Dependencies

### Internal

- Consumed by `app.tvlink.device.Discovery`

### External

- java.net.DatagramSocket, java.net.MulticastSocket

<!-- MANUAL: -->
