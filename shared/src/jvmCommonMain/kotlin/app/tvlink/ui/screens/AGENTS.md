# screens

Compose screens observe `AppViewModel` state and keep only local interaction state such as dialog visibility, text fields, and gesture drags.

- Navigation goes through `AppViewModel`; `MainShell` owns dispatch.
- Cast, screenshot, property-query, and IME behavior use their Feature interfaces.
- Remote gestures may call `RcController` directly; app actions use `RpmService` directly. Avoid forwarding-only Feature methods.
- `AppsScreen` collects `vm.rpm.appList`. Its `LaunchedEffect` is the sole automatic refresh on entering app management; `MoreScreen` only navigates. Manual refresh and successful-operation refresh remain separate triggers.
- `SettingsScreen` reads the single `connectedDevice` snapshot for connection fields.
- Platform file/image/BLE behavior belongs in the existing platform adapters, not in screen conditionals.

Run the actual app after navigation or composition changes. Do not add screenshot/source-text tests as a substitute for behavior verification.
