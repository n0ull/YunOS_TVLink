<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# re

## Purpose

Detailed per-module reverse-engineering protocol specifications. Each file documents one
functional area of the YunOS TV helper protocol with byte-level frame layouts, command
codes, port assignments, and captured examples.

## Key Files

| File | Description |
|------|-------------|
| `01-device-discovery.md` | mDNS + subnet sweep discovery protocol (TCP 13511 DETECT handshake) |
| `02-remote-control.md` | IB fast channel (TCP 3988) + IDC OpCmd_Key fallback, key codes |
| `03-dongle-ble-asr.md` | MagicCast BLE provisioning GATT layout + ASR voice streaming |
| `04-local-projection-screenshot.md` | HTTP-style cast control (TCP 13520) + screenshot command (20900/21000) |
| `05-cloud-api.md` | Cloud APIs (out of scope for implementation, reference only) |
| `06-ui-structure.md` | Original app UI/UX structure analysis |

## For AI Agents

### Working In This Directory

- **Authoritative protocol reference** — consult before modifying any `shared/src/jvmCommonMain/.../proto/` code
- Port numbers, magic bytes, frame sizes, and command IDs in these docs are ground truth
- Do not modify unless fixing a verified factual error

<!-- MANUAL: -->
