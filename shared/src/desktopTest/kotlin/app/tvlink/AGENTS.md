<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# app.tvlink (desktopTest)

## Purpose

Unit tests for the protocol layer, running on the JVM (desktop) target. Tests frame
serialization, mDNS packet parsing, and HTTP server behavior without requiring a real TV.

## Key Files

| File | Description |
|------|-------------|
| `proto/IdcProtocolTest.kt` | IDC frame encode/decode round-trips, header field validation |
| `proto/MdnsTest.kt` | mDNS response packet parsing from captured byte arrays |
| `proto/MediaHttpServerTest.kt` | Embedded HTTP server Range requests, Content-Length, file serving |

## For AI Agents

### Working In This Directory

- Run tests: `./gradlew :shared:desktopTest`
- Use `kotlin.test` (`@Test`, `assertEquals`, `assertContentEquals`)
- Protocol tests should use captured byte arrays from `docs/re/` as fixtures
- No network calls in tests — test serialization/parsing logic in isolation

### Common Patterns

- AAA (Arrange-Act-Assert) structure
- Round-trip: `encode(obj) → bytes → decode(bytes) → assertEquals(obj, result)`
- Captured packets as `byteArrayOf(...)` constants

<!-- MANUAL: -->
