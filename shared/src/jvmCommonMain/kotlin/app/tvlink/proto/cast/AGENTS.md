<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# cast

## Purpose

Local media projection (screen-cast) — the TV pulls media from an embedded HTTP server on the phone/PC. Control messages
use an HTTP/1.1-style text protocol over a TCP long connection (default port 13520) with `yunos-session-id` header.

## Key Files

| File                 | Description                                                                                                            |
|----------------------|------------------------------------------------------------------------------------------------------------------------|
| `CastController.kt`  | TCP control channel: setmedia/play/pause/seek/volume commands; play() 后 1s 轮询 `GET /playback-info` 驱动播放状态回调 |
| `MediaHttpServer.kt` | Embedded HTTP server (port 8192+): serves local files with Range support for TV pull-back                              |

## For AI Agents

### Working In This Directory

- **Control protocol**: text-based, HTTP/1.1-style; `POST /setmedia`, `/play`, `/pause`, `/seek`, `/volume`
- Session identified by `yunos-session-id` header (UUID per session); requests also carry `yunos-device-id`
- **Tested firmware (server_vers 3.2.0) pushes no `POST /event`** — playback state/duration/position come from polling `/playback-info` (carries its own `state` field; unknown duration reported as `-1`)
- `requestRaw` is serialized (`reqLock`) so the poller and UI actions can't cross responses
- `MediaHttpServer` must support `Range` headers — TV uses partial fetches for large files
- Reference: `docs/re/04-local-projection-screenshot.md`

### Testing Requirements

- Test files: `MediaHttpServerTest.kt` (Range requests), `CastControllerTest.kt` (polling loopback)
- Test HTTP Range responses, Content-Length correctness

### Common Patterns

- `BufferedReader` line-based parsing for the text control protocol
- `ArrayBlockingQueue` for response synchronization (armed before write — reader thread can't discard the response)
- `MediaHttpServer` is hand-rolled: raw `ServerSocket` + 4-thread pool, `Connection: close` per request (TV is the only client)

## Dependencies

### Internal

- None (self-contained protocol)

### External

- java.net.Socket / ServerSocket, java.io.FileInputStream

<!-- MANUAL: -->
