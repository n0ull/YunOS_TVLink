<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-24 -->

# theme

## Purpose

Material3 theme configuration — color scheme, typography, and shape definitions shared across all screens on both
platforms.

## Key Files

| File       | Description                                                                              |
|------------|------------------------------------------------------------------------------------------|
| `Theme.kt` | `TvTheme` composable — Material3 color scheme, typography, shapes                        |
| `Color.kt` | M3 tone 阶梯色板(seed 品牌青 #0E7490),浅/深双套 `mdLight*`/`mdDark*` 角色常量             |
| `Brand.kt` | `object Brand` — 品牌点缀(青→紫渐变 `accentBrush`);仅 logo、搜索脉冲、遥控旋钮/摇杆等品牌时刻 |

## For AI Agents

### Working In This Directory

- Use `MaterialTheme.colorScheme.*` in screens — never hardcode colors or emoji;`TvColors` 已退役(M3 重设计,2026-07)
- 品牌渐变仅用 `Brand.*`;语义色(error 等)走 colorScheme,勿再建平行自定义色板
- Dark theme follows system preference via `isSystemInDarkTheme()`(遥控 tab 恒深,见 `App.kt`)

<!-- MANUAL: -->
