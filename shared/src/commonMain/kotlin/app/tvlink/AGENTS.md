<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# app.tvlink (commonMain)

## Purpose

Platform-agnostic Kotlin source set — the root of the KMP source-set hierarchy. Contains only code with zero platform
dependencies (no java. *, no android.*). Currently holds IB data types used by both `jvmCommonMain` protocol code and
potential future non-JVM targets.

## Key Files

| File                 | Description                                                                   |
|----------------------|-------------------------------------------------------------------------------|
| `proto/ib/IbData.kt` | `RcKey` object — remote-control key code constants (UP, DOWN, OK, BACK, etc.) |

## For AI Agents

### Working In This Directory

- **Strictly platform-agnostic**: no `java.*`, `android.*`, or JVM-specific imports
- Only put code here if it must compile for ALL Kotlin targets (including hypothetical JS/Native)
- Most shared code belongs in `jvmCommonMain` instead (both current targets are JVM)

<!-- MANUAL: -->
