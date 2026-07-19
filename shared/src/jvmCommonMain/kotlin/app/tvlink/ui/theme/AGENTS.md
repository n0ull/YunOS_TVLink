<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# theme

## Purpose

Material3 theme configuration — color scheme, typography, and shape definitions shared across all screens on both
platforms.

## Key Files

| File       | Description                                                                        |
|------------|------------------------------------------------------------------------------------|
| `Theme.kt` | `TVLinkTheme` composable — Material3 color scheme (light/dark), typography, shapes |

## For AI Agents

### Working In This Directory

- Use `MaterialTheme.colorScheme.*` in screens — never hardcode colors
- Dark theme follows system preference via `isSystemInDarkTheme()`

<!-- MANUAL: -->
