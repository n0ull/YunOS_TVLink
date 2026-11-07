# device

Device-facing modules between UI state holders and wire protocols.

## Ownership

- `DeviceManager`: one implementation for discovery, current IDC connection, connection/discovery state, history, reconnects, module callbacks, and VConn listener distribution. `connection` is read-only to callers.
- `Discovery`: mDNS plus /24 probing of IDC 13511 and IB 3988; results are merged by IP.
- `RcController`: IB connection and reconnects, with IDC key fallback.
- `RpmService` / `DongleSettingService`: module-specific VConn setup and request state.
- `AsrTextService`: text commands over the ASR VConn.
- `ScreenshotService` / `SysPropService`: IDC requests and response matching.

Do not reintroduce a `ConnectionManager`/`DiscoveryAgent` forwarding split. They had no independent consumers and depended on `DeviceManager` nested types.

## Invariants

- Preserve `DeviceManager.connect` mutex serialization, request-time generation, early active-session installation, and stale-session cleanup. `DeviceManagerConnectTest` exercises these races.
- Preserve the 5s → 15s reconnect policy after an established IDC session drops. Explicit disconnect cancels reconnect work.
- `CONNECTED` with `ibOnly=true` does not imply a non-null IDC `connection`.
- Service listeners are multicast; attach/detach must not overwrite another service's listener.
- `RpmService` and `DongleSettingService` open their own VConn when their module is available.
- `Discovery.hostProber` is the test seam for subnet scans; production probing remains in `Discovery`.

Use `app.tvlink.platform.KeyValueStore` for device history. Device code does not depend on UI storage adapters.

Run `:shared:desktopTest` after changes. Protocol details live in `docs/re/`, not in duplicated file-by-file documentation here.
