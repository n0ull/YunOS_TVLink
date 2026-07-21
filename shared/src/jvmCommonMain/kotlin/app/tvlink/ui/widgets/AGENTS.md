<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# widgets

## Purpose

Platform-abstracted UI widgets using Kotlin `expect`/`actual`. Declarations live here in `jvmCommonMain`;
implementations in `androidMain` and `desktopMain`.

## Key Files

| File                | Description                                                                   |
|---------------------|-------------------------------------------------------------------------------|
| `Platform.kt`       | `expect` declarations: `platformName`、`MotionSensor`(体感)、`VoiceButton`(语音)、`DongleScreen`(配网页)、**BackHandler**(跨平台系统返回键) |
| `PlatformImage.kt`  | `expect` `ByteArrayImage`(字节数组→图片)与 `saveShot`(保存截图,返回结果文案)   |
| `PlatformPicker.kt` | `expect fun pickMediaFile(type, onResult)` — 媒体文件选择(SAF on Android, AWT on Desktop),回调文件系统路径 |

## For AI Agents

### Working In This Directory

- **Adding a new platform widget**: declare `expect` here, implement `actual` in BOTH `androidMain` and `desktopMain`
- Keep `expect` signatures minimal — platform details stay in `actual` implementations
- Android actuals: `shared/src/androidMain/.../ui/widgets/`
- Desktop actuals: `shared/src/desktopMain/.../ui/widgets/`
- **BackHandler**: `expect fun BackHandler(enabled, onBack)` — Android actual 接 `androidx.activity.compose.BackHandler`,桌面空实现; 由 `App()` 统一调用拦截系统返回键

### Common Patterns

- `@Composable expect fun pickMediaFile(type: String, onResult: (String?) -> Unit)`
- Android: Activity Result API / SAF intents
- Desktop: `java.awt.FileDialog` / `javax.swing.JFileChooser`

<!-- MANUAL: -->
