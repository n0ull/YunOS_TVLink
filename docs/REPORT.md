# com.yunos.tvhelper 逆向分析报告（总报告）

> 对象：`com.yunos.tvhelper.apk` v5.2.2（versionCode 2100500202，minSdk 21 / targetSdk 26）
> 方法：apktool 解资源 + jadx 解 Java（890 个自有类），按模块并行分析
> 明细文档：`docs/re/01`~`06`（本文件为汇总，协议字段级细节以分报告为准）

## 一、App 概览

阿里 YunOS 电视生态的手机助手（"阿里TV助手"）。核心定位：**手机遥控/投屏/管理电视盒子与天猫魔投（Dongle）**。

架构上分两大世界，复刻可行性截然不同：

| 世界 | 内容 | 依赖 | 复刻可行性 |
|---|---|---|---|
| **局域网设备控制** | 发现、遥控、投屏、截屏、应用管理、远程输入、语音转发 | 私有协议（IDC/IB/投屏），协议格式已全部逆向 | ✅ 完全可行（与电视固件直接互通，无需阿里云端） |
| **云端业务** | 影视内容、消息推送、账号体系、TTS 定制、弹幕、升级检查 | mtop 网关 + SecurityGuard 签名（wua/x-sign，闭源 native）、ACCS、Weex 远程页面 | ❌ 不可行（签名组件与 appKey 不可得；且该版本云端大概率已下线） |

**结论：复刻范围应锁定局域网设备控制套件**，云端内容类功能以"接口已记录、云端不可用"处理。语音功能可用本地 STT + 文本转发路径实现（见下）。

## 二、协议总图（局域网，全部已逆向）

```
手机                                                    电视/盒子/魔投
 │ ① mDNS 查询 _alitv_remote_control._tcp.local  ──▶  UDP 5353（PTR/SRV/TXT: deviceid, projectionPort）
 │ ② /24 网段遍历探测 ──────────────────────────▶  TCP 13511（DETECT 登录握手）
 │ ③ IDC 主控长连接（16B 头二进制包+JSON，AES 可选）─▶  TCP 13510（登录/心跳20s/模块路由/VConn）
 │ ④ IB 高速输入通道（20B 头，按键/鼠标/摇杆/体感）──▶  TCP 3988
 │ ⑤ 投屏控制（HTTP 风格文本协议）──────────────▶  TCP 13520（或 ddh 下发端口）
 │ ⑥ 手机内嵌 HTTP 媒体服务 ◀────回拉媒体────  端口 8192+（Range 支持）
 │ ⑦ BLE 配网（仅魔投，SSID/密码明文写 GATT）───▶  Service 0xb81d / 特征 aa21-aa23
```

业务全部复用 ③ 的模块路由（VConn + JSON 包）：
- **遥控按键回退**：`OpCmd_Key`(10500，Android 键码）
- **远程输入**：IME 10600/10700/10800/10900（电视拉起手机输入法，全量文本回传）
- **应用管理 RPM**：模块名 `com.yunos.idc.appstore`（消息类 Java 包为 `com.yunos.tv.appstore.idc.datapacket`；列表/详情/按URL推装/卸载/打开/进度推送，细节见 [re/05 §3](re/05-cloud-api.md)）
- **电视截屏**：Cmd 20900→21000（1280×720 JPEG q90 回传）
- **语音**：模块 `com.yunos.tv.asr:etao`，`asr_streaming` 流式文本实时转发（NLU 在电视端）
- **伴看 immersive**：setMedia/playerAction/danmaku 等
- **魔投设置**：模块 `com.ali.ott.dongle.setting`

## 三、功能模块清单（复刻范围划分）

### P0 核心（协议完整、可无云端运行）
1. **设备发现与连接**：mDNS + 子网遍历 + 扫码（`tvhelper://IDC_DIAG/<base64>`）三方式；历史设备按 SSID 记忆重连；断线重连
2. **遥控面板 5 模式**：按键 / 触屏（手势→方向键）/ 体感（陀螺仪）/ 手柄（双摇杆+功能键）/ 方向盘（鼠标模式）；IB 主通道 + IDC 回退；电源键待机确认
3. **远程文字输入**：电视输入框激活 → 手机弹键盘 → 全量文本同步
4. **本地投屏**：照片/视频/音乐三 tab；手机起 HTTP 服务，电视回拉；播放控制（播/停/seek/音量/进度查询/预载/图片缩放）
5. **电视截屏**：单击截图、长按连拍、保存相册

### P1 增强
6. **应用管理**：电视应用列表/详情/打开/卸载/按 URL 推装（电视端下载）
7. **语音输入**：本地语音识别（Android SpeechRecognizer / 桌面端 STT）→ 伪装 `asr_streaming` 完成包转发（原 App 的 `ASR.sendText` 即此路径，**无需阿里 ASR 云**）
8. **魔投 Dongle**：BLE 配网（仅 Android 端）、配网后走标准 IDC、魔投设置（分辨率/重启/出厂）

### P2 云端依赖（不可复刻，仅文档记录）
9. 影视内容/搜索/详情/收藏/播放记录、弹幕、消息推送、淘宝/优酷账号、TTS 语音定制、App 自升级
   —— mtop 接口清单已完整记录于 `docs/re/05-cloud-api.md`，若云端仍存活可尝试无签名只读接口，否则砍除。

### 明确不做的
- 录屏功能（原 App 本就不存在）
- 淘宝/支付宝/微博/微信三方 SDK 全家桶、Weex/H5 远程页面容器（主页内容原 App 就是远程渲染，复刻版主页信息架构自行设计）
- 任何阿里品牌素材/图标/文案的照搬（复刻版为原创 UI 与命名）

## 四、复刻技术风险与待验证点

| 风险 | 说明 | 对策 |
|---|---|---|
| IDC 加密协商 | `encryption_algorithm_detail` 用 SecurityGuard 加密 seed，算法闭源不可还原 | 首连时置 `encryption_algorithm_ver=0` 走明文分支（代码证实存在）；若电视强制加密则需真机抓包重放验证 |
| AES 密钥派生 | 已完整逆向（初始密钥 + HmacSHA256 派生，见 01 报告） | 可直接实现 |
| IB 手柄键码 | LB/RB/SELECT/START 与标准 Linux 码有出入 | 真机抓包校验，或先以按键/触屏模式为主 |
| seek 单位 | 毫秒为高置信推断 | 真机验证 |
| ASR 采样率 | Java 侧不可见，惯例 16kHz | 语音走文本转发路径则无需关心 |
| 云端死活 | v5.2.2 为旧版，mtop 接口大概率已下线 | 实机抓包确认后砍除 P2 |

## 五、复刻工程建议（待确认）

- **技术栈**：Kotlin Multiplatform + Compose Multiplatform
  - `shared` 模块：协议栈全量共享（mDNS/IDC/IB/投屏/HTTP 媒体服务/RPM/截屏），Kotlin 一次实现两端复用
  - `androidApp`：完整功能（含 BLE 配网、陀螺仪/重力体感、系统语音识别）
  - `desktopApp`（Compose for Desktop, Windows）：除 BLE 与体感外的全部 LAN 功能
- **UI**：按 06 报告的页面结构原创实现（深色遥控面板 + 五模式切换 + 设备栏/Nowbar 骨架），不使用原 App 任何美术资源与商标
- **里程碑**：M1 协议核心+设备发现 → M2 遥控三通道 → M3 投屏+截屏 → M4 应用管理+语音 → M5 桌面端适配 → M6 魔投 BLE（仅 Android）
