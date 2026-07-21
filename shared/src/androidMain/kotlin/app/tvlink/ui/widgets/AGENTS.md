<!-- Parent: ../../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# widgets (androidMain)

## Purpose

Android `actual` implementations of the `expect` widgets declared in `jvmCommonMain/ui/widgets/`. Bridges Compose to
Android platform services: application context, system back button, SAF file picker, bitmap decoding, motion sensors,
system speech recognition, and the BLE dongle pairing screen.

## Key Files

| File                          | Description                                                                                     |
|-------------------------------|-------------------------------------------------------------------------------------------------|
| `AndroidPlatform.android.kt`  | `object AndroidPlatform` — 持有 applicationContext 的平台服务入口(由 MainActivity.init 注入)       |
| `Platform.android.kt`         | `actual platformName = "android"` + `BackHandler`(委托 `androidx.activity.compose.BackHandler`)  |
| `PlatformImage.android.kt`    | `actual ByteArrayImage`(BitmapFactory 解码 → ImageBitmap)与 `saveShot`(保存截图)                 |
| `PlatformPicker.android.kt`   | `actual pickMediaFile` — SAF `GetContent`,选中内容拷贝到 app cache 供内嵌 HTTP 服务器以普通文件提供 |
| `MotionSensor.android.kt`     | `actual MotionSensor` — SensorManager 加速度/陀螺仪,约 20Hz 节流,按协议缩放(docs/re/02 §4.4)    |
| `VoiceButton.android.kt`      | `actual VoiceButton` — 有权限时用系统 SpeechRecognizer,否则退回文本输入对话框(TV 端做 NLU)       |
| `DongleScreen.android.kt`     | `actual DongleScreen` — BLE 配网 UI:权限申请、设备列表、SSID/密码输入,驱动 `dongle/DongleBlePairer` |

## For AI Agents

### Working In This Directory

- 每个 `actual` 必须与 `jvmCommonMain/ui/widgets/` 的 `expect` 签名完全一致;改 expect 要同步这里
- 平台上下文统一走 `AndroidPlatform.appContext`,勿在 widget 里自建 Application 引用
- SAF 选中的 Uri 必须拷贝到 cache 目录返回真实文件路径(MediaHttpServer 只能服务文件)
- BLE/传感器/语音权限声明在 androidApp manifest;相关代码用 `@SuppressLint("MissingPermission")`
- 可组合 actual 函数保留 `@Suppress("FunctionNaming")`(Compose PascalCase 约定)

### Testing Requirements

- Android actual 难以单元测试 — 用 `./gradlew :androidApp:assembleDebug` 验证编译,真机/模拟器验证行为

## Dependencies

### Internal

- `app.tvlink.ui.widgets`(jvmCommonMain)— 对应的 `expect` 声明
- `app.tvlink.dongle.DongleBlePairer` / `currentSsid` — DongleScreen 的配网后端

### External

- androidx.activity.compose (BackHandler, Activity Result API / SAF)
- android.hardware (SensorManager), android.speech (SpeechRecognizer)
- android.graphics (BitmapFactory), android.net (Uri)

<!-- MANUAL: -->
