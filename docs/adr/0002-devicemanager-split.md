# ADR-0002 — DeviceManager 三拆：ConnectionManager + DiscoveryAgent + 薄门面

> **状态：deliberate（已确认）**
> 产出方式：`/grill-with-docs` 全项目 grilling（2026-08-27），决策源自 `/improve-codebase-architecture` skill。
> 驱动 skill：`improve-codebase-architecture`（代码库深化路径）。
> commit：`0465873`（Iteration 1 拆分），`ba69fde`（merge 收尾）。

---

## Context

原 `DeviceManager` 同时承担连接管理、设备发现、模块感知与 VConn 编排，职责过重，单文件复杂度高，不利于 agent 操作与人类维护。

## 决策

将 `DeviceManager` 拆为三个模块：

1. **`ConnectionManager`**：持有连接状态（IDC/IB/VConn 生命周期），负责连接的建立、复用、断连与世代（generation）管理。
2. **`DiscoveryAgent`**：负责设备发现（mDNS + 子网 TCP 遍历），产出设备列表，不持有连接状态。
3. **薄门面（`DeviceManager`）**：不直接持有连接状态，保留「模块感知 → VConn 自动打开」的编排职责。通过 `addModuleListener` 感知 module 上线后主动 `openVConn()`，打破 moduleId 死锁。

## 理由

- **简洁可维护**：每个模块单一职责，文件行数与复杂度下降（拆分后评分 60.0 → 98.2）。
- **agent 友好**：小文件、低耦合，agent 操作时上下文窗口占用更少。
- **seam 清晰**：连接管理与发现是天然的独立 seam，拆开后各自可独立测试。

## 门面保留的职责

门面 `DeviceManager` 保留：
- 模块感知（`addModuleListener` / `removeModuleListener`）。
- VConn 自动打开编排（module 上线 → `openVConn()`）。
- `CopyOnWriteArrayList` 多播回调（service 添加/移除监听器）。

门面**不保留**：
- 直接持有连接状态（IDC/IB socket、reader 线程）。
- 发现逻辑（mDNS / 子网扫描）。

## 翻转条件

若以下任一成立，重新考虑合并或调整：

- 门面再次膨胀（>400 行），说明拆分粒度不够或编排逻辑过重。
- 出现需要同时操作连接状态与发现逻辑的跨模块用例，且通过门面编排导致循环依赖。

## 相关链接

- `shared/src/jvmCommonMain/kotlin/app/tvlink/device/DeviceManager.kt`
- `shared/src/jvmCommonMain/kotlin/app/tvlink/device/ConnectionManager.kt`
- `shared/src/jvmCommonMain/kotlin/app/tvlink/device/DiscoveryAgent.kt`
- `CONTEXT.md`「关键模式」节（VConn 自动打开）
