# widgets (androidMain)

Android implementations of UI capabilities declared in `jvmCommonMain/ui/widgets`.

- `Platform.android.kt`: platform name, AndroidX BackHandler, and vibration.
- `MotionSensor.android.kt`: SensorManager motion input with the existing protocol scaling/throttling.
- `PlatformImage.android.kt`: BitmapFactory decoding and MediaStore screenshot saves.
- `PlatformPicker.android.kt`: SAF selection, cache copies, and album-art lookup. The HTTP server requires real files.
- `DongleScreen.android.kt`: BLE pairing UI backed by `DongleBlePairer`.

`VoiceButton` has no Android actual because both platforms share the text-dialog implementation. `AndroidPlatform` and `KeyValueStore` live in `app.tvlink.platform`.

Initialize `AndroidPlatform` in `MainActivity` before using platform adapters. Keep required permissions consistent with the manifests. Build `:androidApp:assembleDebug`; use a device/emulator for behavior that unit tests cannot exercise.
