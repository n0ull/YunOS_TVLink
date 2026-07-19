<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# theme

## Purpose

Material3 theme configuration — color scheme, typography, and shape definitions shared across all screens on both
platforms.

## Key Files

| File          | Description                                                                  |
|---------------|------------------------------------------------------------------------------|
| `Theme.kt`    | `TvTheme` composable — Material3 color scheme, typography, shapes            |
| `TvColors.kt` | `object TvColors` — 自定义调色板常量（AccentStart/Red/Green/TextSecondary 等） |

## For AI Agents

### Working In This Directory

- Use `MaterialTheme.colorScheme.*` in screens — never hardcode colors;项目自定义色用 `TvColors.*`
- Dark theme follows system preference via `isSystemInDarkTheme()`

<!-- MANUAL: -->
