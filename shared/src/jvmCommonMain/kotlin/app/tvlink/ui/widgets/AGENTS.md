# widgets

Shared widgets and platform UI seams.

- `VoiceButton` is shared text-input UI with one implementation in `jvmCommonMain`.
- `Platform.kt`, `PlatformImage.kt`, and `PlatformPicker.kt` declare only capabilities whose Android/Desktop behavior differs.
- Actual UI implementations live in the matching platform source set. Persistent storage and Android application context live in `app.tvlink.platform`, not here.
- Desktop no-op implementations for system back, vibration, and motion sensors are deliberate platform degradation.
- Keep platform details inside actual implementations and preserve Composable naming suppressions where required.

When changing an `expect`, compile both platform targets and update every actual.
