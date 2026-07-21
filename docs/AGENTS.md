<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# docs

## Purpose

Reverse-engineering analysis documentation for the `com.yunos.tvhelper` v5.2.2 protocol.
These documents are the authoritative reference for protocol byte formats, command codes,
and device behavior when implementing or debugging the protocol stack in `shared/`.

## Key Files

| File | Description |
|------|-------------|
| `REPORT.md` | Master reverse-engineering report — overview of all protocols and findings |

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `re/` | Per-module protocol analysis (see `re/AGENTS.md`) |

## For AI Agents

### Working In This Directory

- These are **reference documents**, not code — do not modify unless correcting factual errors
- When implementing a new protocol feature, read the relevant `re/0X-*.md` first
- Frame formats, command codes, and port numbers here are authoritative
- `REPORT.md` line 36: the RPM **module name** is `com.yunos.idc.appstore` (IDC routing name); the Java package `com.yunos.tv.appstore.idc.datapacket` is only its message-class namespace — do not conflate them in proto code

### Common Patterns

- Each `re/` file covers one functional area with byte-level protocol details
- Includes captured packet examples and field-by-field breakdowns

<!-- MANUAL: -->
