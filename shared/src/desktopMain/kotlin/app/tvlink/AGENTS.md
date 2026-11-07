<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# app.tvlink (desktopMain)

## Purpose

Desktop (JVM) `actual` implementations. Provides AWT-based file dialogs, image handling, and stub implementations for
Android-only features (motion sensor and BLE dongle).

## Key Files

| File                                   | Description                                                                       |
|----------------------------------------|-----------------------------------------------------------------------------------|
| `ui/widgets/Platform.desktop.kt`       | `actual platformName`、`BackHandler`/`MotionSensor`(空实现)、`DongleScreen`(不支持提示) |
| `platform/KeyValueStore.kt`            | `java.util.prefs` 持久化实现 |
| `ui/widgets/PlatformImage.desktop.kt`  | `actual ByteArrayImage`(Skia `Image` → Compose ImageBitmap)与 `saveShot`(保存到 `~/Pictures/TVLink`) |
| `ui/widgets/PlatformPicker.desktop.kt` | `actual` file picker using `java.awt.FileDialog`                                  |

## For AI Agents

### Working In This Directory

- Use `java.awt.*` / `javax.swing.*` for native desktop integration
- Android-only motion and BLE capabilities stay no-op, hidden, or explanatory in desktop UI
- `actual` implementations must match `expect` signatures in `jvmCommonMain/ui/widgets/`

### Testing Requirements

- Compile check: `./gradlew :desktopApp:run` builds this source set

<!-- MANUAL: -->
