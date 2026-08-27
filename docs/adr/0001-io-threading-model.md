# ADR-0001 — IO 线程模型：阻塞 Socket + per-connection 写 FIFO

> **状态：现状记录（observed, pending deliberate confirmation）**
> 产出方式：`/grill-with-docs` 全项目 grilling（2026-08-27）。
> 作者：n0ull（经 grilling 确认）。

---

## Context

TVLink 的协议栈需要与 TV 端维持多个长连接（IDC 命令通道、IB 遥控通道、VConn 大数据通道）。Android 平台禁止在主线程执行网络 IO（`NetworkOnMainThreadException`），且协议帧需要保序。

## 现状（observed）

- 协议类使用**阻塞 `java.net.Socket` + reader 线程**，不用 coroutine IO（`Channel`/`await` 等）。
- Socket 写操作**永不在调用线程**执行：`IdcConnection.send` / `IbChannel.sendBody` 将写请求入队到 per-connection 单线程 FIFO（`idc-send` / `ib-send`），由专属 writer 线程顺序消费。
- 效果：FIFO 保序 + 主线程安全。

## 决策

**暂未 deliberate 确认。** 当前实现是历史演化的结果，不是经过显式权衡的选择。

## 待确认问题

1. **D1（reader 线程 vs coroutine IO）**：当前阻塞 reader 线程模型是否值得保留？coroutine IO 能否带来可观测的收益（资源占用、代码简洁度）？
2. **D2（per-connection 单线程写 FIFO）**：是否值得保留？多连接并发写是否会出现 writer 线程成为瓶颈？

## 翻转条件

若以下任一成立，重新 deliberate 本 ADR：

- 连接数显著增长（>10 并发），writer 线程出现可测量的排队延迟。
- 有明确的 coroutine IO 迁移收益（代码量减少、错误处理简化）。
- Android 平台网络 IO 限制变化。

## 约束（不得破坏）

在 deliberate 确认之前，以下行为约束必须保持：

- 🔴 Socket 写永不在调用线程（Android `NetworkOnMainThreadException` 红线）。
- 🔴 帧保序（FIFO）。

## 相关链接

- `shared/src/jvmCommonMain/kotlin/app/tvlink/proto/idc/IdcConnection.kt`
- `shared/src/jvmCommonMain/kotlin/app/tvlink/proto/ib/IbChannel.kt`
- `AGENTS.md`「Common Patterns」节
