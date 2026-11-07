# ui

Shared Compose UI for Android and Desktop.

## Ownership

- `App`: composition root, lifecycle observer, BackHandler, Snackbar, and top-level screen dispatch.
- `AppViewModel`: navigation, service/Feature assembly, connection callbacks, packet routing, and global notices. It exposes one `connectedDevice` snapshot instead of copying each device field.
- `CastFeature`: Cast channel, media server, playback state, and reconnect/healing.
- `ShotFeature` / `SysPropFeature`: request UI state, responses, and timeout behavior.
- `RemoteFeature`: IME state, button availability feedback, current-app notices, and ASR text entry.
- `RpmService`: owns the app list. `AppsScreen` collects `appList` directly; `AppViewModel` collects progress/results globally so notices and success refreshes survive navigation.
- `MainShell`: responsive navigation and screen routing only.

Screens receive `AppViewModel`; they do not construct services. They may call `RcController`, `RpmService`, or `DongleSettingService` directly when a Feature would only forward the call. Do not add pass-through methods to make every screen look structurally identical.

Only actual platform differences use `expect/actual`. `VoiceButton` is a normal shared Composable.

## Verification

Run Feature tests through `:shared:desktopTest`. Exercise the actual app surface after changing navigation or composition behavior.
