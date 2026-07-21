<!-- Parent: ../../../../../../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# desktop

## Purpose

Compose Desktop 入口。`fun main()` 创建应用窗口并承载 shared 模块的 Compose `App()`；
对启动致命异常做兜底（stderr 输出类名/消息/堆栈，无头环境给出提示后以退出码 1 结束）。

## Key Files

| File      | Description                                                                                |
|-----------|--------------------------------------------------------------------------------------------|
| `Main.kt` | `application { Window(...) { App() } }`；启动异常兜底打印（`stackTraceToString()` 到 stderr） |

## For AI Agents

### Working In This Directory

- 保持极薄：窗口标题/尺寸等元数据在此配置，业务逻辑一律放 `shared/`
- 桌面平台差异化代码（AWT 文件对话框、截图保存）在 `shared/src/desktopMain/`，不在这里
- 不要引入 `e.printStackTrace()`（detekt PrintStackTrace 拦截）——用 `System.err.println(e.stackTraceToString())`

### Build & Test Commands

- 运行验证：`./gradlew :desktopApp:run`
- 打包验证：`./gradlew :desktopApp:packageDistributionForCurrentOS`（仅 Windows 格式 Exe/Msi）

<!-- MANUAL: -->
