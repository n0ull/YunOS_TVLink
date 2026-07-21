<!-- Parent: ../../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# ib (commonMain)

## Purpose

Platform-agnostic IB (InputBoost) data types shared across source sets — wire constants and the remote-control key
table. Consumed by `jvmCommonMain` (`proto.ib.IbChannel`, `device.RcController`, `device.Discovery` IB probe).

## Key Files

| File        | Description                                                                                                                                     |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `IbData.kt` | `IbConst` object — port 3988, frame magic 0x11228899, request/sensor protocol types, `RSP_MASK`; `RcKey` enum — `ibVal` (IB wire keycode) / `androidVal` (IDC fallback keycode) / `needIb313` (IB only when server ver ≥ 313) |

## For AI Agents

### Working In This Directory

- **Strictly platform-agnostic**: no `java.*`, `android.*`, or JVM-specific imports
- `RcKey.ibVal` values are NOT standard Linux input codes — gamepad values come from decompiled SecExceptionCode constants; see `docs/re/02-remote-control.md`
- Keep `IbConst.MAGIC` decimal/hex pair in sync (287475865 == 0x11228899)

## Dependencies

### Internal

- Consumed by `app.tvlink.proto.ib.IbChannel` and `app.tvlink.device` (jvmCommonMain)

<!-- MANUAL: -->
