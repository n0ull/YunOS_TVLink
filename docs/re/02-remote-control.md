# 02 遥控协议（rc-agent）

## 1. 总览：两条通道

遥控指令有**两条并行下发通道**，由 `IbRc.sendKeyEventIf()` 运行时二选一：

| 通道 | 端口 | 用途 | 触发条件 |
|---|---|---|---|
| **IB (InputBoost)** | TCP **3988** | 高速输入：按键、鼠标、摇杆、陀螺仪 | IDC 建立且设备为 LAN 类型时自动连接（`IbBizBu.java:25`） |
| **IDC 主控通道** | TCP **13510**（探测 13511） | 登录/控制主链路；按键**回退通道**、远程文字输入(IME)、语音(VConn) | 设备管理模块建立（`DevmgrBizBu.java`） |

按键路由逻辑（`inputboost/biz/rc/IbRc.java:60-78`）：
- IB 可用（且该键不要求 IB 版本 ≥3.13）→ 走 IB 通道，body 为 JSON 风格数组。
- IB 不可用但键有 `mAndroidVal`（方向/OK/返回/主页/菜单/电源/音量）→ 走 IDC，发 `IdcRawPacket_OpCmd_Key`（仅 keyClick）。
- 两者都不可用 → 丢弃。

## 2. IB 通道协议（InputBoost）

### 2.1 连接与保活（`inputboost/biz/main/IB.java`、`comm/IbConn.java`）
- IDC 建立后向 `设备IP:3988` 发起 TCP（NIO 异步 socket，连接超时 6s）。
- 连接成功即发 `IbPacket(type=1 REQ_HELLO)`；回 `RSP_HELLO(0x10000001)`，body JSON `{"ver":"x.xx","sid":n}`，版本×100 得整数版本号（如 313）。
- 随后发 `REQ_MODULEINFO(8)` 与 `REQ_CHANGETYPE(280)`，body `[%d]`（DEFAULT=32, MOTIONPAD=0, JOYSTICK=1）。
- 保活：hello 后 10s 首发，之后**每 15s** 发 `REQ_KEEPALIVE(0)`，回包 `0x10000000`。
- 断线 1s 后启动重连任务，每 5s 重试。
- 电视端主动推 `PROTO_CURRENTAPP(274)`，body `{"cur_app":"..."}`。

### 2.2 二进制帧格式（`packet/IbPacket.java`）
20 字节大端头 + body：

```
[0]  int32 magicNum = 0x11228899 (287475865)
[4]  int32 size      = body 长度
[8]  int32 type      = 命令字
[12] int32 reserve   = 随机数（连接期生成）
[16] int32 checkSum  = (size + reserve) XOR helloId
[20] byte[size] body = 文本（JSON/数组）
```

### 2.3 指令消息（body 均为 ASCII 文本）

| type | 名称 | body 格式 | 说明 |
|---|---|---|---|
| 263 | PROTO_MOUSE | `[evType,code,dx,dy,pressed]` | 按键与鼠标共用 |
| 272 | PROTO_MULTITOUCH | `{"mt_pc":n,"mt_dt":[[x,y,id,act],...]}` | 多点触控（**无 UI 调用方**，预留 API） |
| 296 | PROTO_JOYSTICK | `[{"axis":a,"value":v},...]` | 摇杆轴 |
| 257 | PROTO_G_SENSOR | `[x,y,z]` | 加速度计 |
| 260 | PROTO_GYRO_SENSOR | `[x,y,z]` | 陀螺仪 |
| 280 | REQ_CHANGETYPE | `[mode]` | 切换输入模式 |
| 0/1/8 | keepalive/hello/moduleinfo | 空 body | 链路管理 |

- 按键（`IbRc.java:66`）：`[1, ibKey, 0, 0, down?1:0]`（evType=1 EV_KEY；1=按下 0=抬起）
- 鼠标点击（`:95,113`）：`[1,272,0,0,1]` 紧跟 `[1,272,0,0,0]`（272=BTN_LEFT）
- 鼠标移动（`:104`）：`[2,0,dx,dy,0]`（evType=2 EV_REL，相对像素位移，无归一化）

## 3. 键值映射表

来源：`inputboost/api/IbPublic.java:91-128`（IbKey 枚举 `(ibVal, androidVal, needCheckIbVer)`）；UI 映射 `ui/rc/main/RcUtil.java:100-172`。ibVal 是 IB 线协议键码（普通键与 Linux input-event 码一致，手柄键来自混淆常量，见下表注）。

| 按键 | IB 码 | Android 码 | 备注 |
|---|---|---|---|
| 上/下/左/右 | 103/108/105/106 | 19/20/21/22 | |
| OK ENTER | 28 | 23 | 需 IB≥3.13 |
| 返回 ESC | 1 | 4 | 需 IB≥3.13 |
| 菜单 MENU | 139 | 82 | 需 IB≥3.13 |
| 主页 HOME | 172 | 3 | 需 IB≥3.13 |
| 电源 POWER | 116 | 26 | 需 IB≥3.13 |
| 音量-/+ | 114/115 | 25/24 | 需 IB≥3.13 |
| 手柄 A/B/X/Y | 306/305/307/304 | 0 | 仅 IB 通道 |
| LT/LB/RT/RB | 310/308/311/309 | 0 | 仅 IB；UI `rc_key_d` 也映射 LB |
| SELECT/START | 296/312 | 0 | 仅 IB |
| 魔键 MAGIC | 193 | 0 | 仅 IB |

手柄键无 Android 码，IB 不可用时**不回退** IDC。手柄键码取自 `IbKey` 引用的 `SecExceptionCode`
混淆常量（A=306/B=305/LT=310/RT=311/START=312，已在 `SecExceptionCode.java` 还原），
**不是**标准 Linux input-event 码；与 Python 工具真机实测值一致。"需 IB≥3.13"对应
`IbKey.mNeedCheckIbVer`：不足版本时原 App 对这些键回退 IDC OpCmd_Key。

## 4. 触摸板 / 摇杆 / 体感协议

### 4.1 触摸板 Touchpad（`rcpad/TouchpadView.java`）——不是鼠标板，是手势→方向键翻译器
- 滑动累计位移超 80dp → 判定方向，按速度发 1~2 次 keyClick（≥10000px/s 发 2 次）；原地不动 2×长按超时进“连发模式”（方向键 down 不松，换向先 up 旧键再 down 新键）。
- 单击 → ENTER keyClick；长按 → 弹出方向盘，划向返回方向发 ESC。
- 最终仍走 263 按键命令。

### 4.2 方向盘 Steeringwheel（`rcpad/SteeringwheelView.java:294-302`）——唯一真实鼠标模式
拖动 → `sendMouseMoveIf(-dx,-dy)` 发 `[2,0,dx,dy,0]`；单击 → down/up 一对。像素位移直发，无归一化、无节流。

### 4.3 摇杆 Joystick（`rcpad/JoystickView.java` + `view/RcStickView.java:157-163`）
- UI 归一化偏移到 **[-100,+100]**（`IB_MAX_HAT=100`），每次 onUpdateAxis 直接转发，无频率限制。
- type 296：左摇杆 axis 0(X)/1(Y)，右摇杆 axis 2(X)/5(Y)。
- 值映射：死区 `|v|<5`→128；否则 `((v/100)+1)*255/2`，即 [-100,100]→[0,255]，中点 128。
- 例：`[{"axis":0,"value":255},{"axis":1,"value":128}]`

### 4.4 体感 Motionpad（`inputboost/biz/sensor/IbSensor.java`）
- 进入页面注册加速度计(type1)+陀螺仪(type4)，SENSOR_DELAY_GAME；**节流 50ms/类**（`willSend`）。
- 加速度：`x*2000, y*(-2000), z*1000`（竖屏；横屏交换 x/y），type=257 `[x,y,z]`。
- 陀螺仪：`值*286.47888`（≈rad/s 定点化），type=260 `[x,y,z]`。

## 5. 远程文字输入 Rinput（走 IDC 通道）

1. **电视发起**：电视 IME 激活下发 `Ime_StartInput`(ID **10600**)，带输入框属性；手机震动弹 `RinputActivity`（`RinputMgr.java:40`）。
2. **手机→电视**：文本每次变化即发 `Ime_TextChange`(ID **10800**)：`String mText(长度前缀UTF)` + `int mCursorPos`——全量文本非增量（`RinputFragment.java:67-72`）。
3. **完成**：发 `Ime_Action`(ID **10900**, actionId=-1) 关页面。
4. 收到 `Ime_FinishInput`(**10700**) 或 IDC 断开 → 关闭。

IDC 帧（`ali_tvidclib/packet/BaseIdcPacket.java`）：16 字节大端头 `[magic=130311(0x1FD07)][key][packetID][length]` + 参数体；key>0 时 body 加密（算法在 idc lib，未展开）。按键回退包 `OpCmd_Key`(ID **10500**)：`int keyCode(Android码) + int op(0=click,1=down,2=up)`，App 只用 click。**注意：该 IDC 回退路径在 2026-07-15 真机实测中未命中（疑旧版/未启用），按键主通道是 IB 3988。**

## 6. 语音输入 ASR

独立子协议：`asr/biz/main/ASR.java` + `AsrVConn.java`。手机 `AudioRecord` 采 16kHz 单声道 PCM，发 `out_startRecord / out_asrStreaming(音频流) / out_volume / out_stopRecord / out_recognizeResult`；`BaseAsrPacket extends IIdcVConnPacket.Stub`——复用 IDC 链路上的**虚拟连接(VConn)**，`mMsgType` 字符串标识的 JSON 风格消息。UI 入口 `ui/rc/asr/AsrView.java:67-82`（按住说话）。VConn 分帧细节未展开，标注不确定。

## 7. 关键文件索引（均在 `jadx_out/sources/` 下）

- IB 发送：`com/yunos/tvhelper/inputboost/biz/rc/IbRc.java`
- IB 连接/保活/帧：`inputboost/biz/main/{IB.java, comm/IbConn.java, packet/IbPacket.java, protocol/IbType.java}`
- 键值表：`inputboost/api/IbPublic.java:91`；IB 启动端口 3988：`inputboost/biz/IbBizBu.java:25`
- 体感：`inputboost/biz/sensor/IbSensor.java`
- UI→发送桥：`ui/rc/main/{RcUtil.java, RcCommon.java}`
- 触摸板/方向盘/摇杆：`ui/rc/main/rcpad/{TouchpadView,SteeringwheelView,JoystickView}.java` + `ui/rc/main/view/RcStickView.java`
- 远程输入：`ui/rc/rinput/{RinputMgr,RinputFragment}.java`
- IDC 包：`com/tmalltv/tv/lib/ali_tvidclib/packet/{BaseIdcPacket,IdcRawPacket_OpCmd_Key,IdcRawPacket_Ime_*,IdcPacketFactory}.java`；端口 `IdcConst.java:5`
- 语音：`com/yunos/tvhelper/asr/biz/main/{ASR,AsrVConn}.java`

## 8. 不确定点

1. IB 手柄键码 LB/RB/SELECT/START 与标准 Linux 码有出入，需抓包验证。
2. IB 通道无加密/鉴权（hello 即建立）；IDC key>0 时 body 加密，算法未追踪。
3. ASR VConn 分帧/多路复用格式未展开。
4. `sendMultitouchEventIf`(272) 与 `IB_TOUCH_SCALE=10000` 无 UI 调用方，为预留 API；触摸归一化仅能推测为 0~10000 定标。
5. IbObserver 的 isAvailable 判定细节（MODULEINFO 回包 264 解析）未读取。
