> 本文原存于 tvhelper 旧研究目录(已清理),2026-07-21 抢救入库;其中协议常量已经 a116245 交叉验证并修正入库,结论性待办见根目录 TODO.md。

# 阿里 TV 助手 5.2.2 逆向分析报告

> 逆向对象: `阿里TV助手_5.2.2..apk` (包名 `com.yunos.tvhelper`, 23.98 MB)
> 工具: apktool d + jadx --deobf
> 目标: 在阿里 TV 助手停止云端服务后, 解析并复用其**局域网控制能力**

## 一、功能模块定位 (AndroidManifest)
| 功能 | 关键组件 |
|------|----------|
| 遥控器 / 游戏手柄 | `ui.rc.main.*` (`RcGroupActivity`, `JoystickGroupActivity`), `inputboost.*` |
| TV 搜索 | `ui.trunk...SearchActivity` (依赖云端 mtop, 已停服) |
| 电视截屏 | `ui.screenshot.ScreenShotActivity` (走 IDC 私有协议) |
| 投屏 | `ui.localprojection.*` (本地 HttpServer + DLNA) |
| 设备发现 | `devmgr.biz.mnds.*` (mDNS) |
| 配对/登录通道 | `idc.*` (IDC TCP 协议, 端口 `IdcConst.IDC_TCP_PORT`) |

## 二、设备发现 (mDNS / DNS-SD)
- **服务名**: `_alitv_remote_control._tcp.local` (`MDNSConstants.java`)
- **多播组**: `224.0.0.251:5353` (标准 mDNS)
- 逆向的 `DNSMessage` 发送一条标准 DNS 查询 (qtype=ANY=255),
  TV 应回复多个资源记录:
  - `PTR` → 设备名 (服务名前缀前的部分)
  - `A`   → TV IP
  - `SRV` → 控制端口 (遥控器通道 = **3988**)
  - `TXT` → `deviceid`(MAC), `projectionPort`(投屏端口, 常见 13520)

> ⚠️ **真实环境实测** (2026-07-15, YunOS TV `192.168.1.104`):
> 该 TV 虽开启远程控制并监听 TCP 3988, 但**不响应/不主动发送 mDNS 组播**
> (疑为 Windows 防火墙拦截入站 UDP 5353, 或该型号精简了 mDNS 广播)。
> 因此工具 `scan` 增加 **TCP 3988 端口兜底探测**: 加入组播监听 2 秒无果后,
> 并行扫描本机网段开放 3988 的设备, 实测可稳定发现真实 TV。
> 已知 TV IP 时, 可直接 `send/click/stick <ip>` 跳过发现。

## 三、遥控器控制通道 (TCP 端口 3988)
实现位于 `inputboost/biz/main/*` + `inputboost/biz/rc/IbRc.java`。

### 3.1 二进制报文 (IbPacket)
```
头部 20 字节 (大端序, 5×uint32):
  magic    = 287475865 (0x11223359)   IB_PACKET_MAGIC_NUMBER
  size     = body 长度
  type     = 命令类型
  reserve  = 随机保留值 (协商用)
  checkSum = (size + reserve) ^ helloId
body = UTF-8 字符串 (JSON 或格式化数组)
```
(`IbPacket.encode()` 已逐字段确认)

### 3.2 会话握手 (IB.java)
1. 连接 `TV:3988`
2. 发 `HELLO` (type=1, body 空)
3. TV 回 `HELLO RSP` (type=268435457, body=`{"ver":"x.y","sid":N}`)
4. 客户端保存 `helloId=sid`, 发 `MODULEINFO`(type=8) + `CHANGETYPE`(type=280, `[32]`=DEFAULT)
5. 之后每 15s 发心跳 (type=0)

### 3.3 命令类型 (IbType.java)
| type | 含义 |
|------|------|
| 263  | 按键 / 鼠标 (`MOUSE_CMD_FMT=[%d,%d,%d,%d,%d]`) |
| 272  | 多点触控 (JSON) |
| 296  | 游戏手柄摇杆 (JSON) |
| 280  | 切换模式 |
| 0/1/8 | 心跳 / HELLO / MODULEINFO |

### 3.4 按键命令格式 (IbRc.sendKeyEventIf)
`[act, keycode, x, y, down]`:
- `act=1` 按键, `act=2` 鼠标移动(x,y 为位移)
- `down=1` 按下, `down=0` 抬起
- `keycode` = `IbKey.mIbVal` (见下表)

### 3.5 摇杆命令 (sendStickEventIf)
`[{"axis":0,"value":V},{"axis":1,"value":V}]` (左) 或 `axis 2,5` (右)
V 由 `[-100,100]` 映射到 `[0,255]`, 中点 128: `V=((x/100+1)*255)/2`

## 四、按键码表 (IbPublic.IbKey.mIbVal)
| 键 | 值 | 键 | 值 | 键 | 值 |
|----|----|----|----|----|----|
| UP 103 | DOWN 108 | LEFT 105 | RIGHT 106 | ENTER 28 | ESC 1 |
| MENU 139 | HOME 172 | POWER 116 | VOLDOWN 114 | VOLUP 115 | MAGIC 193 |
| A 306 | B 305 | X 307 | Y 304 | LB 308 | RB 309 |
| LT 310 | RT 311 | START 312 | SELECT 296 | | |

> 注: A/B/X/Y/LB/RB/LT/RT/START 的枚举值系混淆后的 `SecExceptionCode` 常量,
> 已在 `com/alibaba/wireless/security/SecExceptionCode.java` 还原为真实整数。

## 五、投屏协议 (HTTP, 端口 13521, localprojection)
- TV 在 IDC 握手后通过 `mDdhParams["mediaprojection"]` 下发投屏端口
  (`LprojBizBu.initMediaProjection`, 实测本机 TV = **13521**, 默认 13520)。
- TV 在该端口启动 **HTTP 服务器**, 手机作为 HTTP 客户端发 `yunos-*` 头请求
  (逆向自 `MediaMessageFactory` / `MediaProjectionClientImpl`):
  - `GET  /server-info` → 能力 (`features=127`, `server_vers`, ...)
  - `POST /setmedia`   → 投视频/音频, body `{"content_url","content_name",...}`
  - `PUT  /image`      → 投图片, 头 `yunos-assetkey`, body `image/jpeg`
  - `POST /play|/pause|/stop|/seek?value=|/volume?value=` → 播放控制
- **纯 HTTP、不加密, 已实测投屏图片/视频成功 (200 OK)。**

## 六、截屏协议 (IDC 私有 TCP 13510, idc.*)
- 帧头 16 字节大端: `magic(130311) | key | packetID | length`, body `[4字节长度+JSON]`。
- 登录请求 `pid=10000`, 无加密 (`encryption_algorithm_ver=0`) 可成功,
  TV 回 `pid=10100` 含 `connKey` 与投屏端口。
- 截屏命令 `pid=20900`, body `{"cmdReqID","resize_w":1280,"resize_h":720,"compress_quality":90}`;
  响应 `pid=21000` 含 JPEG 字节 (`mImgData`)。
- **限制 (实测)**: 截屏/系统属性等"命令"包 TV 会立即断开。推测命令执行需
  **加密通道** (`encryption_ver>0` 并完成种子协商), 而种子经阿里 `secguard` SDK 的
  `staticSafeDecrypt(16,"idc_conn_key",...)` 解密, 密钥在 native 库无法从 Java 层提取。
  故 `shot` 命令仅验证到登录/取端口, 截屏图获取不可用。

## 七、未实现但已逆向的功能
- **TV 搜索**: 新版依赖云端 mtop 接口 (已停服); 本地可行方案需进一步抓包。

## 八、交付物
`tvhelper_tool/` (纯 Python 标准库, 无需第三方依赖):
- `tv_protocol.py` — 协议常量、按键表、封包/命令构造
- `discover.py`     — mDNS 设备发现 + TCP 3988 兜底
- `remote.py`       — TCP 遥控器客户端 (握手 + 按键/鼠标/手柄)
- `projection.py`   — 投屏 HTTP 客户端 (server-info/投图/视频/控制)
- `idc.py`          — IDC 私有通道客户端 (无加密登录 + 截屏命令封装)
- `tvremote.py`     — 命令行入口 (scan/send/shell/mouse/click/stick/proj/proj-info/proj-video/shot)
- `README.md`       — 使用说明

### 验证结果
1. 本地 TCP stub 模拟 TV (遥控器协议) 验证通过:
   - HOME 键 → `[1,172,0,0,1]` + `[1,172,0,0,0]` ✓ / ENTER → 28 ✓ / 鼠标点击 → 272 ✓
   - 左摇杆(80,-20) → `axis0=229, axis1=102` ✓
2. **真实 YunOS TV (192.168.1.104) 实测**:
   - 遥控器/手柄/鼠标: 握手 + 导航/手柄指令全部成功 ✓
   - 投屏: `proj-info` / 投图片 / 投视频 + 播放控制 均 200 OK ✓
   - IDC 登录: 成功, 取得 `connKey` 与投屏端口 13521 ✓
   - 截屏: TV 收到命令后断开 (加密通道限制, 已明确标注) — 预期行为
