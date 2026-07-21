# TVLink

[![Build & Test](https://github.com/n0ull/YunOS_TVLink/actions/workflows/build.yml/badge.svg)](https://github.com/n0ull/YunOS_TVLink/actions/workflows/build.yml)

YunOS 电视/盒子的跨平台局域网控制客户端（Android + Windows 桌面）。

基于对 `com.yunos.tvhelper` v5.2.2 的逆向分析（见 [`docs/REPORT.md`](docs/REPORT.md) 与 [`docs/re/`](docs/re/)），以原创代码重新实现其局域网协议栈与功能。**不包含**原 App 的任何代码、素材与商标。

---

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装与构建](#安装与构建)
- [CI（GitHub Actions）](#cigithub-actions)
- [使用方法](#使用方法)
- [项目结构](#项目结构)
- [协议要点](#协议要点)
- [真机验证状态](#真机验证状态)
- [配置说明](#配置说明)
- [代码质量检查（ktlint / detekt）](#代码质量检查)
- [免责声明与许可证](#免责声明与许可证)

---

## 功能特性

| 功能 | Android | 桌面端 | 说明 |
|------|:-------:|:------:|------|
| 设备发现 | ✅ | ✅ | mDNS（`_alitv_remote_control._tcp.local`）+ /24 子网 TCP 13511 遍历 |
| 遥控（按键/触控板/手柄/方向盘） | ✅ | ✅ | IB 快速通道 TCP 3988（键码经真机校准）+ IDC 回退（OpCmd_Key，已验证有效） |
| 体感遥控 | ✅ | — | 陀螺仪/加速度经 IB 通道发送 |
| 远程文字输入（未测试） | ✅ | ✅ | 电视 IME 激活自动弹出（IDC 10600–10900） |
| 本地投屏 | ✅ | ✅ | 内嵌 HTTP 服务（8192+，Range），电视回拉；控制通道 TCP 13520（ddhParams 下发，实测 13521）；播放状态/进度轮询 playback-info |
| 电视截屏 | ✅ | ✅ | IDC Cmd 20900→21000，JPEG 回传保存（真机已验证，无需加密通道） |
| 应用管理（暂时没用） | ✅ | ✅ | 列表/打开/卸载/按 URL 推装（RPM over VConn） |
| 语音指令 | ✅ | ✅ | 系统语音识别（Android）/ 文本输入（桌面），经 `asr_streaming` 转发 |
| 魔投 BLE 配网（未测试） | ✅ | — | MagicCast 扫描 + GATT 写入 SSID/密码 |

---

## 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 17+ |
| Kotlin | 2.1.20 |
| Compose Multiplatform | 1.8.0 |
| Gradle | 8.11.1（wrapper 自带） |
| Android SDK | compileSdk 36（`local.properties` 指向；build-tools 36.0.0） |
| Android 设备 | API 21+（Android 5.0） |

---

## 安装与构建

### 1. 克隆项目

```bash
git clone <repo-url> tvhelper3
cd tvhelper3
```

### 2. 配置 Android SDK

在项目根目录创建 `local.properties`（已 gitignore）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

> macOS 示例：`sdk.dir=/Users/<you>/Library/Android/sdk`

### 3. 构建

```bash
# Android APK
./gradlew :androidApp:assembleDebug

# 桌面端运行（开发模式）
./gradlew :desktopApp:run

# 桌面端打包（Windows exe/msi）
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### 4. 运行测试

```bash
# 协议层单元测试
./gradlew :shared:desktopTest
```

---

## CI（GitHub Actions）

推送/PR 到 `main` 触发 [`.github/workflows/build.yml`](.github/workflows/build.yml)，三个 job：

| Job | 内容 | 产物 |
|-----|------|------|
| Test & Code Quality | `./gradlew check`（单元测试 + ktlint + detekt + Android lint 统一门禁） | 测试报告 |
| Android APK | `:androidApp:assembleDebug`（门禁通过后） | `androidApp-debug.apk`（保留 14 天） |
| Desktop (Windows) | `:desktopApp:packageDistributionForCurrentOS`（门禁通过后） | exe/msi 安装包（保留 14 天） |

> 桌面端仅 Windows runner：`desktopApp` 的 `targetFormats` 只声明 Exe/Msi，Linux 上无可打包格式。

---

## 使用方法

### 连接电视

1. 确保手机/电脑与电视在**同一局域网**
2. 启动 App → 自动扫描（mDNS + 子网遍历）
3. 点击发现的设备 → 连接

> 也可手动输入电视 IP 地址连接。

### 遥控

连接后进入遥控页面，顶部切换五种模式：
- **按键**：方向键 / OK / 返回 / 主页 / 菜单 / 音量（IB 快速通道，低延迟；IB 不可用时自动回退 IDC）
- **触控板**：拖动移动电视光标，单击确认（鼠标模式）
- **手柄**：双摇杆 + ABXY/LB/RB/START/SELECT（游戏手柄键码）
- **方向盘**：大圆盘拖动控制光标
- **体感**（仅 Android）：陀螺仪/加速度实时转发
- **文字输入（未测试，调不出来）**：电视激活 IME 后自动弹出输入框

### 投屏

1. 进入「投屏」页面
2. 选择本地视频/音频/图片文件
3. 点击播放 → 电视通过 HTTP 回拉媒体；播放状态、总时长与进度每秒刷新

### 截屏

进入「截屏」页面 → 点击「截取」→ JPEG 保存到本地。

### 应用管理

进入「应用」页面：查看已安装应用、打开、卸载、通过 APK URL 推装。

---

## 项目结构

```
tvhelper3/
├── shared/                      # 协议栈 + 共享 UI（Kotlin Multiplatform 库）
│   ├── src/commonMain/          #   平台无关代码（IB 键码常量）
│   ├── src/jvmCommonMain/       #   JVM 共用：协议实现 + Compose UI
│   │   └── kotlin/app/tvlink/
│   │       ├── device/          #     设备服务层（Discovery, DeviceManager, RcController...）
│   │       ├── proto/           #     协议实现
│   │       │   ├── idc/         #       IDC 控制协议（TCP 13510）
│   │       │   ├── ib/          #       IB 快速输入通道（TCP 3988）
│   │       │   ├── cast/        #       投屏控制（TCP 13520）+ HTTP 媒体服务
│   │       │   └── mdns/        #       mDNS 设备发现
│   │       └── ui/              #     Compose UI（screens, theme, widgets）
│   ├── src/androidMain/         #   Android actual（BLE 配网、传感器、语音、SAF）
│   ├── src/desktopMain/         #   Desktop actual（AWT 对话框、截图保存）
│   └── src/desktopTest/         #   单元测试
├── androidApp/                  # Android 应用壳（权限、MulticastLock）
├── desktopApp/                  # Compose Desktop 壳（Windows exe/msi）
├── docs/                        # 逆向分析文档
│   ├── REPORT.md                #   总报告
│   └── re/                      #   分模块协议细节（01–06）
├── .github/                     # GitHub Actions CI（Build & Test 工作流）
├── apktool_out/                 # 原 App 反编译资源（逆向参考，只读）
├── jadx_out/                    # 原 App 反编译源码（逆向参考，只读）
├── media/                       # 本地真机测试媒体（gitignored）
├── TODO.md                      # 真机验证档案与待办清单（经 jadx 反编译复核）
└── AGENTS.md                    # AI 代理导航文档（层级结构）
```

---

## 协议要点

> 逆向所得，仅用于互操作。详见 [`docs/re/`](docs/re/)。

| 协议 | 端口 | 帧格式 |
|------|------|--------|
| IDC | TCP 13510 | 16B 大端头（magic=130311 / key / packetId / totalLen）+ 包体 |
| IB | TCP 3988 | 20B 头（magic=0x11228899 / size / type / reserve / checksum）+ 文本包体 |
| 投屏控制 | TCP 13520（ddhParams 可下发其他端口，如 13521） | HTTP/1.1 风格文本，`yunos-session-id` + `yunos-device-id` 头 |
| 媒体服务 | TCP 8192+ | 标准 HTTP/1.1 + Range（电视回拉） |
| mDNS | UDP 5353 | `_alitv_remote_control._tcp.local` 查询 |
| DETECT | TCP 13511 | IDC 握手子集（设备发现用） |

**关键细节：**

- 字符串与字节数组均为 4B 长度前缀 + UTF-8
- 登录后 `connKey` 作为后续帧的 key 字段（每帧必带，否则 TV 拒收）
- 本客户端以 `encryption_algorithm_ver=0` 走明文会话（原协议支持的合法分支；真机已验证明文可截屏、可按键回退）
- Cmd 类包（截屏/SysProp 等）body = `LPString({"cmdReqID":N})` + `LPString({参数})`；`Cmd_LaunchSth` 为单段 LPString
- IB checksum = `(size + reserve) ^ helloId`
- 投屏播放状态：实测固件（server_vers 3.2.0）不推 `POST /event`，需轮询 `GET /playback-info`（自带 state 字段）
- 所有 socket 写均在后台发送线程执行（Android 主线程网络 IO 会崩溃）

---

## 真机验证状态

在 YunOS TV（M638_ALI 系列，192.168.1.109）上实测通过：设备发现（子网兜底）、IDC 登录、
按键/触控板/手柄遥控、电视截屏、投屏（状态/总时长/进度）、OpCmd_Key 回退、投屏 server-info。
详见 [`TODO.md`](TODO.md) 的真机档案与遗留待办。

---

## 配置说明

| 文件 | 用途 | 是否入库 |
|------|------|:--------:|
| `local.properties` | Android SDK 路径 | ❌ gitignored |
| `gradle.properties` | JVM 参数、Kotlin 代码风格 | ✅ |
| `settings.gradle.kts` | 模块声明（shared / androidApp / desktopApp） | ✅ |
| `.editorconfig` | ktlint 代码风格（`ktlint_official`、行宽 120、导入布局） | ✅ |
| `detekt.yml` | detekt 静态分析规则集（复杂度/命名/协程/异常等） | ✅ |

> 本项目为纯局域网应用，**无云端配置、无账号系统、无 API Key**。

---

## 代码质量检查

代码风格与静态质量由 **ktlint**（格式）与 **detekt**（静态分析）保证，二者职责分离、规则不冲突：

- **ktlint** 负责格式（缩进、换行、尾随逗号、导入顺序、行宽、文件末尾换行等），遵循 Kotlin 官方编码规范（`ktlint_official`）。
- **detekt** 负责静态分析（命名、复杂度、潜在缺陷、协程、性能、异常等），不启用 `formatting` 规则集，因此不会与 ktlint 重复检查。

### 配置文件

| 文件 | 作用 |
|------|------|
| `.editorconfig` | ktlint 官方风格配置；`expect/actual` 平台文件已关闭 `filename` 规则 |
| `detekt.yml` | 在 detekt 默认规则集之上叠加自定义规则（`buildUponDefaultConfig`） |

### 常用命令

```bash
# CI 统一入口：运行全部子模块的单元测试 + ktlintCheck + detekt + Android lint
./gradlew check

# 按 .editorconfig 自动格式化全部源码（可修正的格式问题一键修复）
./gradlew ktlintFormat

# 仅运行 detekt 静态分析
./gradlew detekt
```

### 说明

- 报告输出：`*/build/reports/ktlint/`（各源集 HTML）与 `*/build/reports/detekt/`（按源集分文件 HTML/SARIF）。
- 构建产物与生成代码（`build/`、`build/generated/...`）已排除，不参与检查。
- `./gradlew check` 默认在发现任何问题时失败（`ignoreFailures = false`、`maxIssues = 0`）。运行 `./gradlew ktlintFormat` 可自动修正大部分格式问题；剩余的命名/复杂度等问题需人工处理。

---

## 免责声明与许可证

本项目仅用于学习协议与私人设备的互操作控制。与原 App 厂商无任何关联。
云端业务（影视内容、账号、推送）依赖厂商闭源签名组件，不在本项目范围内。

**私人学习用途，未开源许可。** 未经授权不得用于商业分发。
