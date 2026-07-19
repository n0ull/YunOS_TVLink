<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# shared

## Purpose

Kotlin Multiplatform library containing the entire protocol stack and shared Compose UI.
Both `androidApp` and `desktopApp` depend on this module. Source sets are layered:
`commonMain` → `jvmCommonMain` → `androidMain` / `desktopMain`.

## Key Files

| File | Description |
|------|-------------|
| `build.gradle.kts` | KMP config: androidTarget + jvm("desktop"), source-set hierarchy, dependencies |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `src/commonMain/` | Platform-agnostic Kotlin (IB data types) |
| `src/jvmCommonMain/` | JVM-shared code: protocol impls, device services, Compose UI (both targets) |
| `src/androidMain/` | Android `actual` implementations (BLE, sensors, SAF, voice) |
| `src/desktopMain/` | Desktop `actual` implementations (AWT dialogs, file save) |
| `src/desktopTest/` | Unit tests (IDC framing, mDNS parsing, HTTP server) |

## For AI Agents

### Working In This Directory

- Source-set hierarchy: `jvmCommonMain` depends on `commonMain`; both platform targets depend on `jvmCommonMain`
- All socket/IO code uses plain `java.net` — lives in `jvmCommonMain`, NOT `commonMain`
- Compose UI code also lives in `jvmCommonMain` (uses JVM-only APIs in places)
- `expect`/`actual` pattern: declarations in `jvmCommonMain/ui/widgets/Platform.kt`, implementations in platform source sets

### Testing Requirements

- Run `./gradlew :shared:desktopTest` after protocol changes
- Tests live in `src/desktopTest/` (JVM target only)

### Common Patterns

- Protocol classes: blocking socket + dedicated reader thread + callback interface
- UI: Compose `@Composable` screens, `AppViewModel` as central state holder
- `expect fun` for platform-divergent widgets (file picker, image display, motion sensor, voice)

## Dependencies

### Internal

- None (this is the base module)

### External

- Compose Multiplatform (runtime, foundation, material3, ui, resources)
- kotlinx-coroutines-core 1.10.2
- kotlinx-serialization-json 1.8.1
- androidx.lifecycle ViewModel 2.8.7
- androidx.activity-compose 1.10.1 (androidMain only)

<!-- MANUAL: -->
