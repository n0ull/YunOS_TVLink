# 01 设备发现与连接协议（discovery-agent）

## 1. 发现机制（双通道并行，无 SSDP/UPnP/NsdManager）

`DevmgrBizBu.search()` 同时启动两条发现路径（`DevmgrBizBu.java:377-387`），仅在 WiFi 下工作。全局搜索确认 `com/yunos` 范围内**不存在** `M-SEARCH`/`ssdp`/`239.255.*`/`NsdManager`。

### 通道 A：mDNS 组播发现（标准 Bonjour，自实现报文解析）

- 组播地址 `224.0.0.251`，端口 **UDP 5353**（`MDNSConstants.java:9-10`）
- 服务名：`_alitv_remote_control._tcp.local`
- 查询报文：标准 DNS 报文（12 字节头 + 1 个 ANY 类型 Question），发 1 次（`MDNSManager.SearchThread`，`MDNSManager.java:103-138`）
- 响应解析（`DNSMessage.parse`，`DNSMessage.java:70-131`），逐条 Answer 提取：
  - **PTR** → 设备名（取服务名前缀部分）
  - **SRV** → 遥控服务端口
  - **A/AAAA** → IP（实际主要用 UDP 包源地址 `getIpFromSocketAddr`，`MDNSManager.java:179-184`）
  - **TXT** → `deviceid`=MAC 地址、`projectionPort`=投屏服务端口（`DNSMessage.java:111-123`）
- 过滤条件：包长 ≥180 且 <2048，name/ip 非空
- **关键**：mDNS 只用来拿 IP+MAC；拿到后立刻对该 IP 的 **TCP 13511** 发起 IDC 探测（`DevmgrBizBu.handleDnsDevAdd`，`DevmgrBizBu.java:559-570`），完整设备信息仍由 IDC 通道获取

### 通道 B：/24 网段 TCP 遍历探测（IdcTraversal）

- 取本机 IPv4 的 /24 前缀，从"自己 IP±10"起环形枚举 1~254（跳过 .0/.255/自己），每 **50ms** 一台，对每台 **TCP 13511** 发起 `IdcDetector.detect()`（`IdcTraversal.java:127-202`）
- 探测 = 一次完整的 TCP 连接 + `DETECT` 类型登录握手，收到 LoginResp 即判定为设备，随后断开（`IdcDetector.java:132-182`）

### 触发时机与历史设备

- 设备选择页 `DevpickerFragment.java:64`、自动连接 `DevAutoConnector.java:72,88` 调 `search()`
- WiFi 恢复、亮屏、回前台 → 按**当前 SSID** 查历史记录直接重连（`DevmgrBizBu.java:49-71`、`HistoryDevMgr.getLastConnectDevBySSID`，`HistoryDevMgr.java:40-55`），历史存 SP 的 JSON 数组
- 断线自动重连：15s 间隔、最多 2 次（`DevmgrBizBu.java:213-242`）

## 2. 连接建立（私有 TCP 二进制协议 IDC）

### 通道与端口

| 端口 | 用途 |
|---|---|
| TCP **13510** (`IDC_TCP_PORT`) | 正式控制连接 |
| TCP **13511** (`IDC_TCP_PORT_2`) | 探测/遍历专用 |
| 投屏端口 | 来自 mDNS TXT `projectionPort`（测试设备硬编码 13520，`DevmgrBizBu.java:631`） |

（`IdcConst.java:5-6`）

### 报文格式（`BaseIdcPacket.java:13-86`）

16 字节大端头：`magic(4B)=130311` | `key(4B)` | `packetId(4B)` | `length(4B)`，随后是包体。登录前 key=`-130324`（未分配）；可选 AES 加密包体。

### 握手/鉴权流程（`IdcComm.connect` → `loginToServer`，`IdcComm.java:239-343`）

1. TCP 连接 TV 的 13510
2. 发 **LoginReq (10000)**，包体为 JSON：
   - `name`=手机包名、`app_ver_code`、`client_type`="Android"、`dev_name`=厂商-型号
   - `login_type`：NORMAL=1 / DETECT=2 / QRCODE=3
   - `login_magic_number`（仅 QRCODE 登录，来自扫电视二维码）
   - `encryption_algorithm_ver`=1、`encryption_algorithm_detail`=阿里安全组件加密后的 `{seed, digest}`（客户端随机种子，`IdcUtils.java:17-27`）
3. 收 **LoginEncryptionResp (10090)**：服务端回自己的 seed（同样加密传输）；双方 seed 经 `IdcEncryptionHelper.getAesSecret(clientSeed, serverSeed)` 派生 16 字节 AES 密钥——把内置初始密钥 `a31c5c871c597d133cb15cd68fefdc1a` 的前 4 字节（小端）替换为 `clientSeed ^ 51550860 ^ serverSeed` 后做 HmacSHA256 自哈希取前 16 字节（`IdcEncryptionHelper.java:45-60`）；之后报文体走 AES
4. 收 **LoginResp (10100)**：`ver`、`connKey`（后续包头 key 字段）、`udpPort`、JSON（`dev_name/dev_model/dev_uuid/dev_os/dev_os_ver`）、ddhParams（`IdcPacket_LoginResp.java:81-108`）→ 连接 established
5. 心跳：每 **20s** 发 HeartBeat，回包 (10200) seq 必须匹配，否则断开（`IdcComm.java:28,134-154`）
6. 运行时 TV 可推 11000 更新设备名

### 配对方式

- **局域网 NORMAL**：发现后自动/点选连接（`DevmgrBizBu.connect`，`DevmgrBizBu.java:418-433`）
- **二维码 QRCODE**：扫电视屏上二维码 `tvhelper://IDC_DIAG/<base64>`（`QrcodeProcessorMgr.java:12,53,74-101`）得到 IP+magic number，带 magic 走 QRCODE 登录（`DevmgrBizBu.java:443-460`）
- 连接建立后业务多路复用同一条 TCP：VConn 虚拟连接（`IdcPacket_VConnSyn/Data/Fin`）+ Cmd 请求响应（`IdcPacket_CmdReqBase/RespBase`，见 `IdcPublic.IIdcRemoteModule`）

## 3. 设备信息数据模型

`DevmgrPublic.DevInfo`（`DevmgrPublic.java:8-18`）：

| 字段 | 含义 | 来源 |
|---|---|---|
| `ip` / `name` | IP / 设备名 | LoginResp |
| `mac` | MAC | mDNS TXT `deviceid` |
| `uuid` | 设备唯一 ID | LoginResp `dev_uuid`；缺失时用 `name+mac` 拼（`handworkUuid=true`，不自动连，`DevmgrBizBu.java:532-545`） |
| `deviceModel` | 型号 | LoginResp `dev_model` |
| `projectionPort` | 投屏端口 | mDNS TXT |
| `loginMagicNumber` | 二维码配对魔数 | 扫码 |
| `ddhParams` | Map<String,byte[]> 能力参数 | LoginResp |

底层 `IdcPublic.IdcDevInfo`（`IdcPublic.java:158-179`）多出：`mDevPort`、`mDevType`(LAN/WAN)、`mVer`、`mDevOs/mDevOsVer`、`mIsEncrypted`。历史记录 `HistoryDevBean` = {ip, ssid, uuid}。

## 4. 关键类与证据（均在 jadx_out/sources/ 下）

- `com/yunos/tvhelper/devmgr/biz/DevmgrBizBu.java` — 发现编排、自动连接、重连（:377, :418, :443, :559）
- `com/yunos/tvhelper/devmgr/biz/mnds/MDNSManager.java` — mDNS 收发（:50-62 建组播 socket，:103-138 查询，:140-171 接收）
- `com/yunos/tvhelper/devmgr/biz/mnds/MDNSConstants.java:9-10`、`DNSMessage.java:70-131`
- `com/yunos/tvhelper/idc/biz/traverse/IdcTraversal.java:127-202` — /24 遍历
- `com/yunos/tvhelper/idc/biz/detect/IdcDetector.java:132-182` — 单台探测（DETECT 登录）
- `com/yunos/tvhelper/idc/biz/comm/IdcComm.java:239-365` — 正式连接、握手、心跳
- `com/tmalltv/tv/lib/ali_tvidclib/IdcConst.java:5-6` — 端口 13510/13511
- `com/tmalltv/tv/lib/ali_tvidclib/packet/BaseIdcPacket.java:13-16`、`IdcPacketHeader.java:13-30` — 报文头
- `com/tmalltv/tv/lib/ali_tvidclib/packet/IdcPacket_LoginReq.java:38-54`、`IdcPacket_LoginResp.java:81-108`、`IdcPacket_LoginEncryptionResp.java`
- `com/tmalltv/tv/lib/ali_tvidclib/helper/IdcEncryptionHelper.java:14,45-60` — AES 密钥派生
- `com/yunos/tvhelper/idc/biz/IdcUtils.java:17-27` — LoginReq 公共字段

## 5. 不确定 / 无法从代码确认的点

- **seed/digest 的加密组件**：`staticSafeEncrypt/staticSafeDecrypt` 是阿里安全保镖（SecurityGuard）SDK，算法在 native/闭源组件中，Java 层无法还原；复刻客户端时 TV 端若不校验该字段则可直接置 `encryption_algorithm_ver=0` 跳过加密（代码显示 ver=0 走 "no encryption" 明文分支，`IdcComm.java:98-100`）
- **13510 与 13511 的分工**是从用法推断（连接用 13510、探测用 13511）；TV 端是否两端口都接受正式连接无法确认
- **LoginResp 的 `udpPort`**：Java 层未见消费方，推测供语音/其他 UDP 业务使用，未确认
- mDNS 过滤阈值"包长 ≥180"为经验值（`MDNSManager.java:156`），非协议要求
- **shake（摇一摇）与发现协议无关**：`utils/shake/ShakeBiz.java` 只是淘宝 ShakeSensor 的封装，摇动后给 Weex 页面发 `ON_SHAKE` 事件（`_WeexFragment_evt.java:100-107,160-167`），具体动作由前端 JS 决定，不直接触发 `search()`
- `WAN` 类型（云端中继连接）在 `IdcComm.connect` 中被 assert 禁用（`IdcComm.java:254-256`），本版本实际只走 LAN
