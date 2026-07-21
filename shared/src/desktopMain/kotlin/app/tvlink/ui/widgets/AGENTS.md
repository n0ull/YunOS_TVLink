<!-- Parent: ../../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# widgets (desktopMain)

## Purpose

Desktop (JVM) `actual` implementations of the `expect` widgets declared in `jvmCommonMain/ui/widgets/`. Uses AWT for
file dialogs and Skia for image decoding; Android-only features (system back button, motion sensor, BLE dongle) are
deliberate no-ops or "unsupported" dialogs.

## Key Files

| File                          | Description                                                                                    |
|-------------------------------|------------------------------------------------------------------------------------------------|
| `Platform.desktop.kt`         | `actual platformName = "desktop"`、`BackHandler`/`MotionSensor`(有意空实现)、`VoiceButton`(文本对话框)、`DongleScreen`("仅 Android 支持"提示框) |
| `PlatformImage.desktop.kt`    | `actual ByteArrayImage`(Skia `Image.makeFromEncoded` → Compose ImageBitmap)与 `saveShot`(写入 `~/Pictures/TVLink/tv-shot-*.jpg`) |
| `PlatformPicker.desktop.kt`   | `actual pickMediaFile` — `java.awt.FileDialog`,按 type(image/video/audio)做扩展名过滤,返回绝对路径 |

## For AI Agents

### Working In This Directory

- 每个 `actual` 必须与 `jvmCommonMain/ui/widgets/` 的 `expect` 签名完全一致;改 expect 要同步这里
- Android-only 功能保持 no-op/提示框,勿引入桌面不可用的依赖
- 文件选择用 `java.awt.FileDialog`(原生);图片解码用 Skia(`org.jetbrains.skia.Image`),勿用 AWT BufferedImage
- 截图保存到 `~/Pictures/TVLink`,路径返回给用户可见文案
- 可组合 actual 函数保留 `@Suppress("FunctionNaming")`(Compose PascalCase 约定)

### Testing Requirements

- 编译验证:`./gradlew :desktopApp:run` 会构建此 source set

## Dependencies

### Internal

- `app.tvlink.ui.widgets`(jvmCommonMain)— 对应的 `expect` 声明

### External

- java.awt (FileDialog, FilenameFilter)
- org.jetbrains.skia (Image) + Compose UI (toComposeImageBitmap)

<!-- MANUAL: -->
