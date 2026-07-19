<!-- Parent: ../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-20 -->

# app.tvlink (androidApp main)

## Purpose

Android 宿主 Activity 层。唯一的 Activity 负责：初始化 `AndroidPlatform`（向 shared 注入 applicationContext）、
获取 mDNS 发现所需的 Wi-Fi `MulticastLock`、把 shared 模块的 Compose `App()` 设为内容视图。

## Key Files

| File              | Description                                                                                     |
|-------------------|-------------------------------------------------------------------------------------------------|
| `MainActivity.kt` | 单 Activity——`AndroidPlatform.init(this)`、acquire/release MulticastLock、`setContent { App() }` |

## For AI Agents

### Working In This Directory

- 保持极薄：不在这里加业务逻辑（放 `shared/`）
- `AndroidPlatform.init()` 必须先于任何 shared 平台服务调用（`appContext` 为 lateinit）
- MulticastLock 在 `onCreate` 获取、`onDestroy` 释放——mDNS 收包在部分机型上无锁会被系统丢弃

### Testing Requirements

- `./gradlew :androidApp:assembleDebug` 编译验证

## Dependencies

### Internal

- `shared` 的 `app.tvlink.ui.App`（Compose 根）、`app.tvlink.ui.widgets.AndroidPlatform`

<!-- MANUAL: -->
