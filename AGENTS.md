# TVLink

Kotlin Multiplatform LAN client for YunOS TVs/boxes. Original implementation based on reverse-engineering `com.yunos.tvhelper` v5.2.2; no original assets, trademarks, or cloud features.

## Architecture

- `androidApp` and `desktopApp` are thin shells entering `app.tvlink.ui.App()`.
- `shared/src/commonMain`: pure data only.
- `shared/src/jvmCommonMain`: protocols, device coordination, Compose UI, and platform `expect` declarations.
- `shared/src/androidMain` / `desktopMain`: real platform adapters.
- `app.tvlink.platform`: persistent key-value storage and Android application context.
- `DeviceManager` owns discovery, the active IDC session, reconnects, and module/VConn callback distribution.
- `RcController` owns IB; `CastFeature` owns Cast + HTTP; individual services open their own VConn.
- Keep one implementation behind each seam. Do not add facade/delegation layers or generic socket base classes without multiple real variants.

Protocol behavior must agree with `docs/re/`. Those documents are the protocol source of truth; README does not duplicate frame layouts or ports.

## Invariants

- Use blocking sockets with the established reader/writer model. Steady-state `IdcConnection.send` and `IbChannel.sendBody` preserve FIFO on their per-connection writers; handshake/keepalive paths are documented in `docs/adr/0001-io-threading-model.md`.
- Keep socket work off the Android main thread.
- Preserve `DeviceManager` connection serialization, generation checks, and callback installation order.
- UI state uses Compose snapshot state or StateFlow. Resource-owning modules are destroyed through `AppViewModel.onCleared()`.
- Use `expect/actual` only when platform implementations differ.

## Build and verification

Use JDK 17 and Android SDK 36. Windows: `gradlew.bat`; Unix: `./gradlew`.

```bash
./gradlew :shared:desktopTest
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run
./gradlew check
./gradlew ktlintFormat
```

`check` runs tests, ktlint, detekt, and Android lint. Style comes from `.editorconfig`; detekt rules come from `detekt.yml`.

## Project documentation

- `README.md`: user entry, current architecture, build and usage.
- `TODO.md`: active work and historical device-validation references.
- `docs/architecture.md`: jointly accepted target architecture and migration constraints; not current implementation status.
- `docs/re/`: protocol and reverse-engineering evidence.
- `docs/adr/`: architectural constraints that are not obvious from code.
- `.scratch/<feature-slug>/`: local markdown issues; see `docs/agents/issue-tracker.md`.
- Triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`; see `docs/agents/triage-labels.md`.
