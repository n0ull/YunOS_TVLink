<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

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
