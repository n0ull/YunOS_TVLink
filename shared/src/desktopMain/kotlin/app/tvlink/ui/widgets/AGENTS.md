# widgets (desktopMain)

Desktop implementations of UI capabilities declared in `jvmCommonMain/ui/widgets`.

- AWT `FileDialog` selects media; Skia decodes screenshots; screenshots save under `~/Pictures/TVLink`.
- System back, motion sensor, and vibration no-ops are deliberate.
- The desktop `DongleScreen` explains that BLE pairing is Android-only; normal navigation hides that entry on Desktop.
- `VoiceButton` is shared in `jvmCommonMain`, not a desktop actual.
- Persistent key-value storage lives in `app.tvlink.platform`.

Compile or run `:desktopApp:run` after changes. Keep actual declarations aligned with their expect interfaces.
