<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# proto

## Purpose

Low-level protocol implementations — byte-level frame encoding/decoding, socket management, and wire-format constants.
Each sub-package handles one protocol channel. All implementations are blocking-socket based with dedicated reader
threads.

## Subdirectories

| Directory | Purpose                                                                                                        |
|-----------|----------------------------------------------------------------------------------------------------------------|
| `idc/`    | IDC control protocol — TCP 13510, 16B big-endian header, login, module queries, commands (see `idc/AGENTS.md`) |
| `ib/`     | IB InputBoost fast channel — TCP 3988, 20B header, key/motion events (see `ib/AGENTS.md`)                      |
| `cast/`   | Media projection control — HTTP/1.1-style text over TCP 13520 + embedded HTTP server (see `cast/AGENTS.md`)    |
| `mdns/`   | mDNS/DNS-SD query and response parsing for `_alitv_remote_control._tcp.local` (see `mdns/AGENTS.md`)           |

## For AI Agents

### Working In This Directory

- **Always reference `docs/re/` before modifying frame formats or command codes**
- All integers are big-endian unless documented otherwise
- String/byte-array encoding: 4-byte length prefix + UTF-8 bytes
- Magic numbers: IDC=130311, IB=0x11228899 — do not change
- Reader threads invoke callbacks directly; never block the reader

### Testing Requirements

- Every protocol change must have a corresponding test in `shared/src/desktopTest/`
- Test frame serialization round-trips (encode → decode → assert equality)

### Common Patterns

- `Socket` + `DataInputStream`/`OutputStream` with explicit big-endian writes
- Reader loop: `while (running) { readFrame(); dispatch(); }`
- `@Volatile` state flags, `synchronized`/lock objects for send paths
- Send paths queue onto per-connection single-thread executors (`idc-send`/`ib-send`) — caller threads (incl. Android UI) never touch socket IO

<!-- MANUAL: -->
