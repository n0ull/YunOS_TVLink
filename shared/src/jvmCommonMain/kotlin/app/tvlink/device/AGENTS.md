# device

Device-facing modules between UI state holders and wire protocols.

## Ownership

- `DeviceSelection`: current target, IDC connection, `SelectionSnapshot`, history, reconnects, module callbacks, and VConn listener distribution. Raw `connection` is internal to the device layer.
- `Discovery`: owns `DiscoveryState` and scan workers; mDNS plus /24 probing of IDC 13511 and IB 3988; results are merged by IP. Scan state is independent of connection state.
- `RcController`: IB connection and reconnects, with IDC key fallback.
- `RpmService` / `DongleSettingService`: module-specific VConn setup and request state.
- `AsrTextService`: text commands over the ASR VConn.
- `ScreenshotService` / `SysPropService`: IDC requests and response matching.

Do not reintroduce a `ConnectionManager`/`DiscoveryAgent` forwarding split. They had no independent consumers and depended on `DeviceManager` nested types.

## Invariants

- Preserve `DeviceSelection.select` mutex serialization, stable selection IDs, request generation, early active-session installation, and stale-session cleanup. `DeviceSelectionTest` exercises these races.
- Preserve the 5s → 15s reconnect policy after an established IDC session drops. Explicit disconnect cancels reconnect work.
- `ConnectionMode.IB_ONLY` does not create an IDC connection.
- `SelectionBinding` is internal: it pairs a `TargetLease` with the current `IdcBinding`. Public pages read only `SelectionSnapshot`.
- Target/channel revocation and the IDC writer's final permit share a short synchronization point; socket I/O remains outside it.
- IB uses the same target lease plus its own channel lease; `RcController` owns binding and cancellation without public attach/detach calls.
- Service listeners are multicast and receive their source `IdcBinding`; consumers reject older selection/connection identities and never write through a newer target's connection.
- `RpmService` and `DongleSettingService` open their own VConn when their module is available.
- `Discovery.hostProber` is the test seam for subnet scans; production probing remains in `Discovery`.

Use `app.tvlink.platform.KeyValueStore` for device history. Device code does not depend on UI storage adapters.

Run `:shared:desktopTest` after changes. Protocol details live in `docs/re/`, not in duplicated file-by-file documentation here.
