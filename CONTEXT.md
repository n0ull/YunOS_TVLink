# CONTEXT — TVLink

> 产出方式：`/grill-with-docs` 全项目 grilling（2026-08-27）。
> 维护者：n0ull。修改本文件前，确认变更与 `docs/re/` 反编译基准不冲突。

---

## Scope

TVLink 是 **YunOS 电视/盒子的跨平台局域网控制客户端**（Android + Windows 桌面）。

**主动划定的边界（设计意图，非环境约束）：**

- ✅ 只做 LAN 内能闭环的能力：设备发现、遥控、投屏、截屏、应用管理、语音指令、BLE 配网。
- ❌ 明确不做：Racct 账号/支付（原 App 即空实现 `asoToken`）、弹幕 MTOP、TV 搜索（依赖已停服云端）。见 `TODO.md`「明确不做」节。
- ❌ 不包含原 App 的任何代码、素材与商标——全部以原创代码重新实现协议栈。

**协议基准：** 所有协议行为以 `docs/re/` 反编译报告为 primary source。修改协议代码前，先核对对应章节（`docs/re/01`～`05`）。

---

## Glossary

以下词汇的 primary source 均为 `docs/re/` 反编译文档，不是本项目的发明。

| 词 | 含义 | primary source |
|----|------|---------------|
| **IB** | 低延迟遥控上行通道（TCP 3988）。承载按键、触控板、手柄、方向盘、体感（陀螺仪/加速度）。魔数 `0x11228899`。 | `docs/re/02` |
| **IDC** | 命令/响应通道（TCP 13511）。承载截屏（20900→21000）、应用管理、远程文字输入（10600～10900）、LaunchSth 等。 | `docs/re/01` |
| **VConn** | 大数据量通道。RPM 包管理（列表/打开/卸载/URL 推装）的唯一通道 = appstore VConn。 | `docs/re/05` |
| **ddhParams** | 投屏控制通道。TV 下发的投屏参数（含端口），优先于兜底端口 13520/13521。 | `docs/re/04` |
| **asr_streaming** | 语音帧隧道。全会话帧转发，双端统一文本输入。 | `docs/re/` |
| **module** | 有歧义：① **协议模块**（`IdcModule` 等，代码层抽象）；② **TV 固件模块**（appstore / asr / RemoteControlServer 等，TV 端实际加载的组件）。两者含义不同，见下方「歧义词」节。 | `docs/re/01`、`docs/re/05` |
| **RPM** | 远程包管理（Remote Package Management）。经 VConn 的应用管理操作（12 种操作码）。 | `docs/re/05` |
| **TV-A** | 真机测试设备代号（M638_ALI 固件）。`TODO.md` 真机档案的实证来源。 | `TODO.md` |

### 歧义词：module

「module」在本项目语境下有两层含义，**不要混用**：

1. **协议模块**：代码层的 `IdcModule` 抽象，对应 IDC 协议中可按 moduleId 寻址的功能单元。
2. **TV 固件模块**：TV 端实际加载的组件（如 `com.yunos.idc.appstore`、`com.yunos.tv.asr:etao`、`RemoteControlServer`）。`Cmd_PackageInfo` / `Cmd_LaunchSth` 操作的是这一层。

关键事实（`docs/re/05`，TV-A 实证）：TV-A 固件**无 appstore 模块**——`Cmd_PackageInfo` 查 5 个 appstore 候选包名全部 `existed=False`；`Cmd_LaunchSth` 唤醒 appstore 动作无广播回应。因此 RPM 在 TV-A 上不可用是**固件能力缺失**，非代码问题。

---

## 关键模式（代码行为约束）

以下模式是代码的实际行为约束，修改相关代码时不得破坏。

| 模式 | 说明 | 红线 |
|------|------|------|
| **Socket 写永不在调用线程** | `IdcConnection.send` / `IbChannel.sendBody` 将写操作入队到 per-connection 单线程 FIFO（`idc-send` / `ib-send`）。Android 会以 `NetworkOnMainThreadException` 杀死主线程 socket 写。 | 🔴 不得在调用线程直写 socket |
| **阻塞 Socket + reader 线程** | 协议类使用阻塞 `java.net.Socket` + reader 线程，不用 coroutine IO。 | 现状记录，见 `docs/adr/0001` |
| **VConn 自动打开** | service 通过 `DeviceManager.addModuleListener` 感知 module 上线后主动 `openVConn()`，打破 moduleId 死锁（先有鸡还是先有蛋：需要 moduleId 才能打开 VConn，但 moduleId 要通过 VConn 才能拿到）。 | 🟡 不得改回同步顺序 |
| **投屏状态轮询** | TV 固件 server_vers 3.2.0 不推 `POST /event`，状态/时长/进度走 `GET /playback-info` 轮询。 | 🟡 不得改回事件驱动假设 |
| **封面供给注册制** | 不走原 App 的绝对路径回退方案；Android 端从 MediaStore 取专辑封面拷贝到本地缓存供片。 | 🟡 commit `893c3b2` |
| **帧格式：大端、长度前缀** | 所有协议帧 big-endian，length-prefixed。 | 🔴 不得改端序 |
| **JSON 解析** | `parseJsonObject()` 返回 `FlatJson` facade，包裹 `kotlinx.serialization` 的 `JsonObject`。 | — |

---

## 工作流锚点

- P1 扫尾（当前主路径）：取 `.scratch/<feature>/spec.md` → `/to-tickets` → `/implement`（内驱 `/tdd` + `/code-review`）。
- 新特性想法：`/grill-with-docs` → `/to-spec` → `/to-tickets` → `/implement`。
- Bug / 外部请求：`/triage` → agent-ready issue → `/implement`。
- 架构深化：`/improve-codebase-architecture` → 产出深化机会 → 汇入 `/grill-with-docs`。
- P2 硬件挂起项（RPM / 远程文字输入 / BLE）：不进 flow，等设备后再激活。

---

## 文件索引

| 文件 | 作用 |
|------|------|
| `docs/re/` | 反编译报告与协议帧格式基准（primary source for glossary） |
| `docs/adr/` | 架构决策记录（本文件派生） |
| `TODO.md` | 真机档案 + 待办清单（P0/P1/P2 + 明确不做 + 审查排期） |
| `.scratch/<feature>/` | 工单（spec + issues），本地 markdown issue tracker |
| `AGENTS.md` | 项目 AI agent 指南（构建命令、Common Patterns、依赖） |
