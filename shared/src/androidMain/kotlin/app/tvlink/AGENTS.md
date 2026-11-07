# app.tvlink (androidMain)

Android adapters and Android-only behavior.

- `platform/AndroidPlatform.kt`: application context initialized by `MainActivity`.
- `platform/KeyValueStore.kt`: SharedPreferences history storage.
- `dongle/DongleBlePairer.kt`: MagicCast BLE provisioning; protocol details are in `docs/re/03-dongle-ble-asr.md`.
- `ui/widgets/`: BackHandler, vibration, sensors, SAF/cache media selection, MediaStore image saves, and BLE pairing UI.
- Text voice commands use the shared `VoiceButton` text-dialog implementation.

Respect minSdk 21 with version guards for newer Android APIs. Keep manifest permissions and lint suppressions aligned with actual platform calls. Actual declarations must match the relevant expect in `jvmCommonMain`.

Verify with `:androidApp:assembleDebug`; use Android runtime verification for permissions, BLE, sensors, SAF, and MediaStore behavior.
