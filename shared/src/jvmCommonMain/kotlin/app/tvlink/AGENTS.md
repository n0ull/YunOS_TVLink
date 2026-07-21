<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# app.tvlink (jvmCommonMain)

## Purpose

JVM-shared source set containing all protocol implementations, device services, and the full Compose Multiplatform UI.
Both Android and Desktop targets compile this code. Uses plain `java.net` sockets — no platform-specific APIs.

## Subdirectories

| Directory | Purpose                                                                                                                 |
|-----------|-------------------------------------------------------------------------------------------------------------------------|
| `device/` | High-level device services: discovery, connection facade, remote control, RPM, screenshot, ASR (see `device/AGENTS.md`) |
| `proto/`  | Low-level protocol implementations: IDC, IB, Cast, mDNS (see `proto/AGENTS.md`)                                         |
| `ui/`     | Compose Multiplatform UI: App composable, ViewModel, screens, theme, widgets (see `ui/AGENTS.md`)                       |

## For AI Agents

### Working In This Directory

- This is the **core module** — most development happens here
- All code must be JVM-compatible but NOT Android-specific (no `android.*` imports)
- Protocol code uses blocking sockets + reader threads; UI uses Compose state
- When adding protocol features, cross-reference `docs/re/` for byte formats

### Testing Requirements

- Protocol changes → run `./gradlew :shared:desktopTest`
- UI changes → verify with `./gradlew :desktopApp:run`

## Dependencies

### Internal

- `commonMain` (IbData types via source-set hierarchy)

### External

- java.net (Socket, ServerSocket, DatagramSocket)
- Compose Multiplatform (runtime, foundation, material3)
- kotlinx-coroutines, kotlinx-serialization
- androidx.lifecycle ViewModel

<!-- MANUAL: -->
