<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# widgets

## Purpose

Platform-abstracted UI widgets using Kotlin `expect`/`actual`. Declarations live here in `jvmCommonMain`;
implementations in `androidMain` and `desktopMain`.

## Key Files

| File                | Description                                                                   |
|---------------------|-------------------------------------------------------------------------------|
| `Platform.kt`       | `expect` declarations: file picker, image save, motion sensor, voice input    |
| `PlatformImage.kt`  | `expect` composable for platform-native image display (bitmap handling)       |
| `PlatformPicker.kt` | `expect` composable for file/media selection (SAF on Android, AWT on Desktop) |

## For AI Agents

### Working In This Directory

- **Adding a new platform widget**: declare `expect` here, implement `actual` in BOTH `androidMain` and `desktopMain`
- Keep `expect` signatures minimal — platform details stay in `actual` implementations
- Android actuals: `shared/src/androidMain/.../ui/widgets/`
- Desktop actuals: `shared/src/desktopMain/.../ui/widgets/`

### Common Patterns

- `@Composable expect fun PlatformPicker(onPicked: (File?) -> Unit)`
- Android: Activity Result API / SAF intents
- Desktop: `java.awt.FileDialog` / `javax.swing.JFileChooser`

<!-- MANUAL: -->
