<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-20 | Updated: 2026-07-22 -->

# .github

## Purpose

GitHub Actions CI 配置。单工作流 `Build & Test`：代码质量门禁通过后并行构建 Android APK 与 Windows 桌面安装包。

## Key Files

| File                  | Description                                                                     |
|-----------------------|---------------------------------------------------------------------------------|
| `workflows/build.yml` | 3 个 job：`test-and-lint`（门禁）→ `android`（APK）+ `desktop`（Windows 安装包） |

## For AI Agents

### Working In This Directory

- **job 结构**：`test-and-lint` 跑 `./gradlew check`（单元测试 + ktlint + detekt + Android lint 统一入口）；
  `android` / `desktop` 经 `needs:` 被门禁拦截后才构建
- **desktop 仅 windows-latest**：`desktopApp` 的 `targetFormats` 只有 Exe/Msi，ubuntu 上
  `packageDistributionForCurrentOS` 无产物。要加 Linux 包须先加 `TargetFormat.Deb` 再恢复 os 矩阵
- **SDK 版本**：compileSdk 36，android 系 job 显式 `sdkmanager --install "platforms;android-36" "build-tools;36.0.0"`；
  升降 compileSdk 时此处与两处 build.gradle.kts 需同步
- **不要给 check 的任务匹配加回 `detektGenerateConfig`**：它把根 detekt.yml 声明为输出，与各模块 Detekt
  任务的输入形成未声明依赖，Gradle 校验直接判死（2026-07-20 的 CI 连续失败即此因，详见根 build.gradle.kts 注释）
- run 步骤统一 `shell: bash`（Windows runner 上即 Git Bash，`./gradlew` 可用）
- **触发条件**：`push` / `pull_request` 到 `main`
- **产物保留期**：test-report 7 天；APK 与桌面安装包 14 天（`retention-days`）

### Build & Test Commands

- 推送前本地 `./gradlew check` 必须全绿（与 CI 同一命令）；产物路径见各 upload-artifact 步骤

<!-- MANUAL: -->
