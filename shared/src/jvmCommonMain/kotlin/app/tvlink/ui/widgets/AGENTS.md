<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# widgets

## Purpose

Platform-abstracted UI widgets using Kotlin `expect`/`actual`. Declarations live here in `jvmCommonMain`;
implementations in `androidMain` and `desktopMain`.

## Key Files

| File                | Description                                                                   |
|---------------------|-------------------------------------------------------------------------------|
| `Platform.kt`       | `expect` declarations: file picker, image save, motion sensor, voice input, **BackHandler**(跨平台系统返回键) |
| `PlatformImage.kt`  | `expect` composable for platform-native image display (bitmap handling)       |
| `PlatformPicker.kt` | `expect` composable for file/media selection (SAF on Android, AWT on Desktop) |

## For AI Agents

### Working In This Directory

- **Adding a new platform widget**: declare `expect` here, implement `actual` in BOTH `androidMain` and `desktopMain`
- Keep `expect` signatures minimal — platform details stay in `actual` implementations
- Android actuals: `shared/src/androidMain/.../ui/widgets/`
- Desktop actuals: `shared/src/desktopMain/.../ui/widgets/`
- **BackHandler**: `expect fun BackHandler(enabled, onBack)` — Android actual 接 `androidx.activity.compose.BackHandler`,桌面空实现; 由 `App()` 统一调用拦截系统返回键

### Common Patterns

- `@Composable expect fun PlatformPicker(onPicked: (File?) -> Unit)`
- Android: Activity Result API / SAF intents
- Desktop: `java.awt.FileDialog` / `javax.swing.JFileChooser`

<!-- MANUAL: -->
