<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# app.tvlink (androidMain)

## Purpose

Android-specific `actual` implementations and Android-only features. Depends on
`jvmCommonMain` via source-set hierarchy. Contains BLE dongle provisioning, system voice recognition, motion sensors,
SAF file picker, and Android image handling.

## Key Files

| File                                   | Description                                                                              |
|----------------------------------------|------------------------------------------------------------------------------------------|
| `dongle/DongleBlePairer.kt`            | MagicCast BLE provisioning — GATT scan, connect, write WiFi credentials (18-byte chunks) |
| `ui/widgets/Platform.android.kt`       | `actual` platform utilities (Android context access)                                     |
| `ui/widgets/PlatformImage.android.kt`  | `actual` image display using Android Bitmap/ImageBitmap                                  |
| `ui/widgets/PlatformPicker.android.kt` | `actual` file picker using SAF (Storage Access Framework)                                |
| `ui/widgets/MotionSensor.android.kt`   | `actual` gyroscope/accelerometer via SensorManager for motion remote                     |
| `ui/widgets/VoiceButton.android.kt`    | `actual` voice input via system SpeechRecognizer                                         |
| `ui/widgets/DongleScreen.android.kt`   | BLE pairing UI screen (Android-only feature)                                             |

## For AI Agents

### Working In This Directory

- All files here may use `android.*` APIs freely
- BLE code requires `@SuppressLint("MissingPermission")` — permissions declared in androidApp manifest
- MagicCast BLE protocol: see `docs/re/03-dongle-ble-asr.md` for GATT UUIDs and write format
- `actual` implementations must match `expect` signatures in `jvmCommonMain/ui/widgets/`

### Testing Requirements

- Android-specific code is harder to unit test — verify via `./gradlew :androidApp:assembleDebug` compilation

<!-- MANUAL: -->
