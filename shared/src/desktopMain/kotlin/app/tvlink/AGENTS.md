<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# app.tvlink (desktopMain)

## Purpose

Desktop (JVM) `actual` implementations. Provides AWT-based file dialogs, image handling, and stub implementations for
Android-only features (motion sensor, voice, BLE dongle).

## Key Files

| File                                   | Description                                                                       |
|----------------------------------------|-----------------------------------------------------------------------------------|
| `ui/widgets/Platform.desktop.kt`       | `actual` platform utilities (desktop file paths)                                  |
| `ui/widgets/PlatformImage.desktop.kt`  | `actual` image display using `java.awt.image.BufferedImage` → Compose ImageBitmap |
| `ui/widgets/PlatformPicker.desktop.kt` | `actual` file picker using `java.awt.FileDialog`                                  |

## For AI Agents

### Working In This Directory

- Use `java.awt.*` / `javax.swing.*` for native desktop integration
- Android-only features (motion, voice, BLE) should be no-op stubs or hidden in desktop UI
- `actual` implementations must match `expect` signatures in `jvmCommonMain/ui/widgets/`

### Testing Requirements

- Compile check: `./gradlew :desktopApp:run` builds this source set

<!-- MANUAL: -->
