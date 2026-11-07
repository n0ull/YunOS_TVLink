# 阻塞 Socket 与稳态写 FIFO

**状态：accepted**

IDC、IB 等长连接使用阻塞 `java.net.Socket` 和专属 reader 线程。Android 禁止主线程网络 IO，协议又要求帧顺序，因此稳态写入必须保持现有的每连接单线程 FIFO：`IdcConnection.send` 使用 `idc-send`，`IbChannel.sendBody` 使用 `ib-send`。

该约束不虚构“所有 socket 写都经过 FIFO”：连接建立阶段的握手由建连线程直接写入，IB keepalive 由其保活线程发送。这些路径已有明确线程所有者，不与 UI 的稳态写入交叉。

不抽取通用 Socket 基类。IDC、IB、Cast 的握手、帧格式、响应配对和活性判定不同；共享基类只会把差异变成额外参数与回调。若未来修改线程模型，必须验证 Android 主线程安全、帧顺序和断连清理。
