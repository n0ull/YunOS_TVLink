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
| `build.gradle.kts` | Root build config: plugin versions + ktlint 12.1.2 / detekt 1.23.8 |
| `gradle.properties` | JVM args, Kotlin style, `applyDefaultHierarchyTemplate=false` |
| `.editorconfig` | ktlint_official code style (line width 120, import layout) |
| `detekt.yml` | detekt static analysis rules (complexity, naming, coroutines, exceptions) |
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

- **Language**: Kotlin 2.1.20, Compose Multiplatform 1.8.0, targeting JVM 11+
- **Build**: `./gradlew` (wrapper 8.11.1); JDK 17+ required, Android SDK via `local.properties`
- **Architecture**: shared KMP module with `jvmCommonMain` (java.net sockets), `commonMain` (Compose UI), platform `actual` implementations
- **Code style**: ktlint_official enforced via `.editorconfig`; run `./gradlew ktlintFormat` before committing
- **Protocol reference**: always cross-check `docs/re/` when touching protocol code
- **No cloud APIs**: this project is LAN-only; cloud features are out of scope

### Build & Test Commands

```bash
./gradlew :androidApp:assembleDebug   # Android APK
./gradlew :desktopApp:run             # Run desktop app
./gradlew :shared:desktopTest         # Protocol unit tests
./gradlew check                       # ktlint + detekt (all modules)
./gradlew ktlintFormat                # Auto-format to ktlint_official style
```

### Common Patterns

- Protocol classes use blocking `java.net.Socket` + reader threads (not coroutines for IO)
- UI state via Compose `mutableStateOf` / `StateFlow` in ViewModels; updates use `Dispatchers.Default` (thread-safe snapshot state)
- VConn callbacks: `CopyOnWriteArrayList` multicast in DeviceManager (services add/remove listeners)
- Lifecycle: `AppViewModel.onCleared()` → `DeviceManager.destroy()` → `IdcConnection.close()` chain
- Frame formats: big-endian, length-prefixed; see `docs/re/` for byte layouts
- JSON parsing: `parseJsonObject()` returns `FlatJson` facade over kotlinx.serialization `JsonObject`

## Dependencies

### External

- Kotlin Multiplatform 2.1.20 + Compose Multiplatform 1.8.0 (JetBrains)
- kotlinx-coroutines 1.10.2
- kotlinx-serialization-json 1.8.1
- androidx.lifecycle ViewModel 2.8.7
- AndroidX Activity Compose 1.10.1 (Android target)
- ktlint 12.1.2 + detekt 1.23.8 (code quality, build-time only)

<!-- MANUAL: -->
