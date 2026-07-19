# TVLink

YunOS 电视/盒子的跨平台局域网控制客户端（Android + Windows 桌面）。
基于对 `com.yunos.tvhelper` v5.2.2 的逆向分析（见 `docs/REPORT.md` 与 `docs/re/`），
以原创代码重新实现其局域网协议栈与功能，**不包含**原 App 的任何代码、素材与商标。

## 功能

| 功能 | Android | 桌面端 | 说明 |
|---|---|---|---|
| 设备发现 | ✅ | ✅ | mDNS（`_alitv_remote_control._tcp.local`）+ /24 子网 TCP 13511 遍历 |
| 遥控（按键/触屏/手柄/方向盘） | ✅ | ✅ | IB 快速通道 TCP 3988 + IDC 回退（TCP 13510, OpCmd_Key） |
| 体感遥控 | ✅ | — | 陀螺仪/加速度经 IB 通道发送 |
| 远程文字输入 | ✅ | ✅ | 电视 IME 激活自动弹出（IDC 10600-10900） |
| 本地投屏 | ✅ | ✅ | 手机/电脑内嵌 HTTP 服务（8192+，Range），电视回拉；控制通道 TCP 13520 |
| 电视截屏 | ✅ | ✅ | IDC Cmd 20900→21000，JPEG 回传保存 |
| 应用管理 | ✅ | ✅ | 列表/打开/卸载/按 URL 推装（RPM over VConn） |
| 语音指令 | ✅（系统语音识别） | ✅（文本输入） | 文本经 `asr_streaming` 转发，NLU 在电视端执行 |
| 魔投 BLE 配网 | ✅ | — | MagicCast 扫描 + GATT 写入 SSID/密码 |

## 构建

```bash
./gradlew :androidApp:assembleDebug     # Android APK
./gradlew :desktopApp:run               # 桌面端运行
./gradlew :shared:desktopTest           # 协议层单元测试
```

需要 JDK 17+、Android SDK（`local.properties` 指向）。

## 结构

```
shared/                 协议栈与共享 UI（Kotlin Multiplatform）
  src/jvmCommonMain/    IDC/IB/投屏/mDNS/RPM 协议实现（两端 JVM 共用，java.net）
  src/commonMain/       Compose UI（全部页面两端共享）
  src/androidMain/      BLE 配网、系统语音识别、传感器、SAF 媒体选择
  src/desktopMain/      AWT 文件对话框、截图保存
androidApp/             Android 壳（权限、MulticastLock）
desktopApp/             Compose Desktop 壳（Windows exe/msi）
docs/REPORT.md          逆向分析总报告
docs/re/01-06           分模块协议细节
```

## 协议要点（逆向所得，用于互操作）

- IDC 帧：16B 大端头（magic=130311 / key / packetId / totalLen）+ 包体；字符串与字节数组均为 4B 长度前缀
- 登录后 connKey 作为后续包的 key；本客户端以 `encryption_algorithm_ver=0` 走明文会话（原协议支持的合法分支）
- IB 帧：20B 头（magic=0x11223399 / size / type / reserve / checksum=(size+reserve)^helloId）+ 文本包体
- 投屏控制：HTTP/1.1 风格文本，`yunos-session-id` 头，POST /setmedia|/play|/pause|/seek|/volume 等

## 免责声明

本项目仅用于学习协议与私人设备的互操作控制。与原 App 厂商无任何关联；
云端业务（影视内容、账号、推送）依赖厂商闭源签名组件，不在本项目范围内。
