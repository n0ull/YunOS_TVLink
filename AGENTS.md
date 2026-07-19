<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# TVLink

## Purpose

Kotlin Multiplatform (Android + Desktop) LAN control client for YunOS TV/boxes.
Re-implements the local-network protocol stack of `com.yunos.tvhelper` v5.2.2 from
reverse-engineering analysis — original code, no assets or trademarks from the original app.

## Key Files

| File | Description |
|------|-------------|
| `settings.gradle.kts` | Root project "TVLink"; includes `:shared`, `:androidApp`, `:desktopApp` |
| `build.gradle.kts` | Root build config (plugin versions) |
| `gradle.properties` | JVM args, Compose/Kotlin flags |
| `local.properties` | Android SDK path (machine-specific, gitignored) |
| `.gitignore` | Ignores build/, .gradle/, local.properties |
| `README.md` | Feature matrix, build instructions, protocol summary (Chinese) |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `shared/` | Protocol stack + shared Compose UI — KMP library (see `shared/AGENTS.md`) |
| `androidApp/` | Android application shell (see `androidApp/AGENTS.md`) |
| `desktopApp/` | Compose Desktop application shell (see `desktopApp/AGENTS.md`) |
| `docs/` | Reverse-engineering reports and protocol specs (see `docs/AGENTS.md`) |
| `gradle/` | Gradle wrapper JAR and properties |
| `apktool_out/` | Decompiled APK resources (reference only, not compiled) |
| `jadx_out/` | Decompiled APK Java source (reference only, not compiled) |

## For AI Agents

### Working In This Directory

- **Language**: Kotlin 2.x, Compose Multiplatform, targeting JVM 11+
- **Build**: `./gradlew` (wrapper); JDK 17+ required, Android SDK via `local.properties`
- **Architecture**: shared KMP module with `jvmCommonMain` (java.net sockets), `commonMain` (Compose UI), platform `actual` implementations
- **Protocol reference**: always cross-check `docs/re/` when touching protocol code
- **No cloud APIs**: this project is LAN-only; cloud features are out of scope

### Build & Test Commands

```bash
./gradlew :androidApp:assembleDebug   # Android APK
./gradlew :desktopApp:run             # Run desktop app
./gradlew :shared:desktopTest         # Protocol unit tests
```

### Common Patterns

- Protocol classes use blocking `java.net.Socket` + reader threads (not coroutines for IO)
- UI state via Compose `mutableStateOf` / `StateFlow` in ViewModels
- Callbacks (`onXxx: ((T) -> Unit)?`) for async protocol events; UI hops via `viewModelScope.launch`
- Frame formats: big-endian, length-prefixed; see `docs/re/` for byte layouts

## Dependencies

### External

- Kotlin Multiplatform + Compose Multiplatform (JetBrains)
- kotlinx-coroutines 1.10.2
- kotlinx-serialization-json 1.8.1
- androidx.lifecycle ViewModel 2.8.7
- AndroidX Activity Compose 1.10.1 (Android target)

<!-- MANUAL: -->
