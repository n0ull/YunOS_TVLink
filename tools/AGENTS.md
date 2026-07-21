<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# tools

## Purpose

外部参考工具存放区。**不参与 Gradle 构建**(`settings.gradle.kts` 仅 include
`:shared` / `:androidApp` / `:desktopApp`),`./gradlew check` 与 CI 均不触及此目录。
内容为协议逆向验证期写的 Python 参考工具,供真机调试时与 Kotlin 实现对照。

## Subdirectories

| Directory | Purpose |
|-----------|---------|
| `tvhelper_tool/` | Python 纯标准库遥控/投屏/IDC 验证工具(源码在姊妹研究目录,详见 `tvhelper_tool/AGENTS.md`) |

<!-- MANUAL: -->
