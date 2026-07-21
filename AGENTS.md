<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

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
| `.editorconfig` | ktlint_official code style (line width 120, import layout); `[**/build/**]` 段排除生成代码 |
| `detekt.yml` | detekt static analysis rules (complexity, naming, coroutines, exceptions) |
| `local.properties` | Android SDK path (machine-specific, gitignored) |
| `.gitignore` | Ignores build/, .gradle/, .idea/, .omc/, local.properties, 反编译参考物(jadx_out/, apktool_out/, *.apk), media/ |
| `README.md` | Feature matrix, build instructions, protocol summary (Chinese) |
| `TODO.md` | 真机验证档案 + 待办清单:P0/3988 探测已归档;P1 首项为 RPM 修复计划 R1–R4(2026-07-21 反编译复核定位模块名/唤醒/解析三缺陷,证据见 `docs/re/05` §3) |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `shared/` | Protocol stack + shared Compose UI — KMP library (see `shared/AGENTS.md`) |
| `androidApp/` | Android application shell (see `androidApp/AGENTS.md`) |
| `desktopApp/` | Compose Desktop application shell (see `desktopApp/AGENTS.md`) |
| `docs/` | Reverse-engineering reports and protocol specs (see `docs/AGENTS.md`) |
| `.github/` | GitHub Actions CI — Build & Test 工作流 (see `.github/AGENTS.md`) |
| `tools/` | 外部参考验证工具(Python,不参与 Gradle 构建;see `tools/AGENTS.md`) |
| `gradle/` | Gradle wrapper JAR and properties |
| `apktool_out/` | Decompiled APK resources (reference only, not compiled) |
| `jadx_out/` | Decompiled APK Java source (reference only, not compiled) |
| `media/` | 本地真机测试媒体(gitignored,勿提交) |

## For AI Agents

### Working In This Directory

- **Language**: Kotlin 2.1.20, Compose Multiplatform 1.8.0, targeting JVM 11+
- **Build**: `./gradlew` (wrapper 8.11.1); JDK 17+ required, Android SDK via `local.properties`
- **Architecture**: shared KMP module with `jvmCommonMain` (java.net sockets), `commonMain` (Compose UI), platform `actual` implementations
- **Code style**: ktlint_official enforced via `.editorconfig`; run `./gradlew ktlintFormat` before committing
- **Pre-commit gate**: `.git/hooks/pre-commit` runs `./gradlew ktlintFormat` + `./gradlew check` before every commit (machine-local, not in VCS; bypass with `git commit --no-verify`)
- **Protocol reference**: always cross-check `docs/re/` when touching protocol code
- **No cloud APIs**: this project is LAN-only; cloud features are out of scope

### Build & Test Commands

```bash
./gradlew :androidApp:assembleDebug   # Android APK
./gradlew :desktopApp:run             # Run desktop app
./gradlew :shared:desktopTest         # Protocol unit tests
./gradlew check                       # 统一门禁：单元测试 + ktlint + detekt + Android lint（CI 入口）
./gradlew ktlintFormat                # Auto-format to ktlint_official style
```

### Common Patterns

- Protocol classes use blocking `java.net.Socket` + reader threads (not coroutines for IO)
- **Socket writes never run on the caller thread** (Android kills with NetworkOnMainThreadException) — `IdcConnection.send`/`IbChannel.sendBody` queue onto per-connection single-thread executors (`idc-send`/`ib-send`, FIFO preserved)
- UI state via Compose `mutableStateOf` / `StateFlow` in ViewModels; updates use `Dispatchers.Default` (thread-safe snapshot state)
- VConn callbacks: `CopyOnWriteArrayList` multicast in DeviceManager (services add/remove listeners)
- **VConn 自动打开**: service 通过 `onModuleAvailability` 感知 module 上线后主动 `openVConn()`,打破 moduleId 死锁
- **BackHandler**: `expect/actual` 跨平台抽象,Android 接 `androidx.activity.compose.BackHandler`,桌面空实现
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
