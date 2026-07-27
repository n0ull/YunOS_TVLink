# 02 遥控协议（rc-agent）

## 1. 总览：两条通道

遥控指令有**两条并行下发通道**，由 `IbRc.sendKeyEventIf()` 运行时二选一：

| 通道 | 端口 | 用途 | 触发条件 |
|---|---|---|---|
| **IB (InputBoost)** | TCP **3988** | 高速输入：按键、鼠标、摇杆、陀螺仪 | IDC 建立且设备为 LAN 类型时自动连接（`IbBizBu.java:25`） |
| **IDC 主控通道** | TCP **13510**（探测 13511） | 登录/控制主链路；按键**回退通道**、远程文字输入(IME)、语音(VConn) | 设备管理模块建立（`DevmgrBizBu.java`） |

按键路由逻辑（`inputboost/biz/rc/IbRc.java:60-78`，源码判定 `IbRc.java:63-75`）：
- **IB 分支**：满足 `mAndroidVal==0`（手柄键，始终走 IB）**或**（`isIbAvailable()` 且（`!mNeedCheckIbVer` 或 `getIbVersion()>=313`））→ 走 IB 通道，body 为 JSON 风格数组。
- **IDC 分支**：`mAndroidVal!=0` 且（`isIbAvailable()` 为 false **或**（需版本校验 且 IB 版本 <3.13））→ 走 IDC，发 `IdcRawPacket_OpCmd_Key`（仅 keyClick）。注意：ENTER/ESC/MENU/HOME/POWER/VOL± 等需校验键在 **IB 已连但版本 <3.13** 时也会回退 IDC。
- 两者都不可用 → 丢弃。

## 2. IB 通道协议（InputBoost）

### 2.1 连接与保活（`inputboost/biz/main/IB.java`、`comm/IbConn.java`）
- IDC 建立后向 `设备IP:3988` 发起 TCP（NIO 异步 socket，连接超时 6s）。
- 连接成功即发 `IbPacket(type=1 REQ_HELLO)`；回 `RSP_HELLO(0x10000001)`，body JSON `{"ver":"x.xx","sid":n}`，版本×100 得整数版本号（如 313）。
- 随后发 `REQ_MODULEINFO(8)`（**空 body**，`new IbPacket(8, …, new byte[0])`）与 `REQ_CHANGETYPE(280)`（body `[%d]`，DEFAULT=32, MOTIONPAD=0, JOYSTICK=1）。
- 保活：hello 后 10s 首发，之后**每 15s** 发 `REQ_KEEPALIVE(0)`，回包 `0x10000000`。
- 断线(`onError`)1s 后启动重连任务(`:71-73`:`removeCallbacks`→`start()`→`postDelayed(1000L)`),每 5s 重试(`RetryConnectTask.run()`内 `postDelayed(this, 5000L)`);初始连接失败(`onConnect-failure`,`:40`)仅 `start()` 设标志、不自动重调度,需外部再 `connect()`。
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
| 272 | PROTO_MULTITOUCH | `{"mt_pc":n,"mt_dt":[[x,y,id,act],...]}` | 多点触控（**无 UI 调用方**，预留 API）。**272 同时被用作鼠标左键码 BTN_LEFT**（见下方鼠标点击） |
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
混淆常量（`SecExceptionCode.java`，已据源码还原真实值，勿与常量语义名混淆）：

| 手柄键 | 引用常量 | 常量真实值（IB 码） | 备注 |
|---|---|---|---|
| A | `SEC_ERROR_STA_KEY_NOT_EXISTED` | 306 | |
| B | `SEC_ERROR_STA_INCORRECT_DATA_FILE_DATA` | 305 | 非 304 |
| X | —（直接字面量） | 307 | |
| Y | —（直接字面量） | 304 | |
| LT | `SEC_ERROR_STA_INVALID_ENCRYPTED_DATA` | 310 | 非 307 |
| LB | —（直接字面量） | 308 | |
| RT | `SEC_ERROR_STA_DECRYPT_MISMATCH_KEY_DATA` | 311 | |
| RB | —（直接字面量） | 309 | |
| SELECT | `IbType.PROTO_JOYSTICK` | 296 | |
| START | `SEC_ERROR_STA_LOW_VERSION_DATA_FILE` | 312 | |

这些键码**不是**标准 Linux input-event 码；与 Python 工具真机实测值一致。"需 IB≥3.13"对应
`IbKey.mNeedCheckIbVer`：不足版本时原 App 对这些键回退 IDC OpCmd_Key。

## 4. 触摸板 / 摇杆 / 体感协议

### 4.1 触摸板 Touchpad（`rcpad/TouchpadView.java`）——不是鼠标板，是手势→方向键翻译器
- 滑动累计位移超 80dp → 判定方向，按速度发 1~2 次 keyClick（≥10000px/s 发 2 次）；原地不动 2×长按超时进“连发模式”（方向键 down 不松，换向先 up 旧键再 down 新键）。
- 单击 → ENTER keyClick；长按 → 弹出方向盘，划向返回方向发 ESC。
- 最终仍走 263 按键命令。

### 4.2 方向盘 Steeringwheel（`rcpad/SteeringwheelView.java:294-302`）——唯一真实鼠标模式
拖动 → `sendMouseMoveIf(-dx,-dy)` 发 `[2,0,dx,dy,0]`；单击 → down/up 一对。像素位移直发，无归一化、无节流。

### 4.3 摇杆 Joystick（`rcpad/JoystickView.java` + `view/RcStickView.java:157-163`）
- UI 归一化偏移到 **[-100,+100]**（`IB_MAX_HAT=100`），每次 onUpdateAxis 直接转发；**无时间节流**（区别于体感 50ms），但 `RcStickView` 有 **±10 位置死区**（`MIN_HAT_THRESHHOLD=10`）：仅当归一化值变化 ≥10 或回到中心(0,0) 才触发 `onUpdateAxis`（`RcStickView.java:159`）。
- type 296：左摇杆 axis 0(X)/1(Y)，右摇杆 axis 2(X)/5(Y)。
- 值映射：死区 `|v|<5`→128；否则 `((v/100)+1)*255/2`，即 [-100,100]→[0,255]，中点 128。
- 例：`[{"axis":0,"value":255},{"axis":1,"value":128}]`

### 4.4 体感 Motionpad（`inputboost/biz/sensor/IbSensor.java`）
- 进入页面注册加速度计(type1)+陀螺仪(type4)，SENSOR_DELAY_GAME；**节流 50ms/类**（`willSend`）。
- 加速度：`x*2000, y*(-2000), z*1000`（竖屏；横屏交换 x/y），type=257 `[x,y,z]`。
- 陀螺仪：`值*286.47888`（≈rad/s 定点化），type=260 `[x,y,z]`。

## 5. 远程文字输入 Rinput（走 IDC 通道）

1. **电视发起**：电视 IME 激活下发 `Ime_StartInput`(ID **10600**)，body 含输入框属性：`mInputType`(输入类型)、`mOptions`(imeOptions)、`mActionId`、`mActionLabel`(动作按钮文案)、`mHintText`(提示)、`mExistedText`(TV 端已输入文本回填，供手机端续打)；手机震动弹 `RinputActivity`（`RinputMgr.java:40`）。
2. **手机→电视**：文本每次变化即发 `Ime_TextChange`(ID **10800**)：`String mText(长度前缀UTF)` + `int mCursorPos`——全量文本非增量（`RinputFragment.java:67-72`）。
3. **完成**：发 `Ime_Action`(ID **10900**, actionId=-1) 通知 TV,同时**本地 `finish()` 关闭页面**(`RinputFragment.java:36-38`:`sendPacket(...)` 后立即 `activity().finish()`)。
4. 收到 `Ime_FinishInput`(**10700**) 或 IDC 断开 → 关闭。

IDC 帧（`ali_tvidclib/packet/BaseIdcPacket.java`）：16 字节大端头 `[magic=130311(0x1FD07)][key][packetID][length]` + 参数体；`length` = 整包长度（含 16 字节头 = 参数体+16）。注意两个独立概念：① 头部 `key` 字段 = `mConnKey`，由握手 LoginResp 经 `assignConnectionKey` 赋值（默认 `-130324`），仅标识连接；② body 加密密钥 = `mSecretKey`，由握手种子派生，`encode()` 加密门控为 `mSecretKey != null`，**与头部 key 字段是否为正无关**，只是握手后二者同时置位。**`mSecretKey` 已分配（握手种子交换后）时 body 加密**：算法 `AES/CBC/PKCS5Padding`，key=IV=派生 16 字节密钥（见 `ali_tvsharelib/all/utils/CipherUtils.java:23-53`）。密钥派生：`IdcConnection.assignSeed()` → `IdcEncryptionHelper.getAesSecret(clientSeed, serverSeed)`，固定种子 `a31c5c871c597d133cb15cd68fefdc1a`（16 字节）→ 覆盖前 4 字节(little-endian) = `(clientSeed ^ 0x03129A8C ^ serverSeed)` → `HmacSHA256(全量, 全量)` 取前 16 字节为 AES-128 密钥。握手明文包仅放行 packetID `10000/10090/10100`（种子交换），之后所有包须带已分配 key。按键回退包 `OpCmd_Key`(ID **10500**)：`int keyCode(Android码) + int op(0=click,1=down,2=up)`，App 只用 click。**注意：该 IDC 回退路径在 2026-07-15 真机实测中未命中（疑旧版/未启用），按键主通道是 IB 3988。**

## 6. 语音输入 ASR

独立子协议：`asr/biz/main/ASR.java` + `AsrVConn.java`。音频采集由 JNI 层 `asr/biz/main/jni/AudioRecorderImp.java` 通过 `AudioRecord` 完成(单声道 16-bit PCM 已确认:`new AudioRecord(1, sRate, 2=CHANNEL_IN_MONO, 2=ENCODING_PCM_16BIT, bufSize)`);**16kHz 采样率由 native 经构造参数传入,Java 源码不可见,未确认**。发 5 类上行包与 2 类下行包，全部以 `BaseAsrPacket.mMsgType`（**字符串**，非整型命令字）标识；包类名与线上 `mMsgType` 映射：`out_startRecord`→`record_start`、`out_asrStreaming`(流式识别结果,编码 question/result_code/finish,非裸音频)→`asr_streaming`、`out_volume`→`volume`、`out_stopRecord`→`record_stop`、`out_recognizeResult`→`result`；下行 `in_asrLanguage`→`asr_language`、`in_asrMode`→`asr_mode`。**注意：源码不存在整型 `0x1001/0x1002/…` 命令字，也无整型 service id（`0x1501`/`5377` 等均无关）；ASR 以字符串模块名 `ASR_MODULE_NAME = "com.yunos.tv.asr:etao"`（`asr/biz/AsrDef.java:8`）经 IDC `acquireModule` 获取。** `BaseAsrPacket extends IIdcVConnPacket.Stub`——复用 IDC 链路上的**虚拟连接(VConn)**，`mMsgType` 字符串标识的 JSON 风格消息。UI 入口 `ui/rc/asr/AsrView.java:67-82`（按住说话）。**VConn 分帧格式已可推导**（见 `AsrVConn.java` / `BaseAsrPacket.java` / `AsrPacketFactory.java`）：外层经 `mAsrModule.sendVConnPacket` 在单条 IDC 连接上以 module 名多路复用；内层 JSON 信封 `{"asr_name":ASR_MODULE_NAME,"asr_data":{"pk_type":mMsgType,"pk_content":…}}`，`decode()` 按 `pk_type` 还原，`onRecvPacket` → `AsrPacketFactory.createRecvPacket(mMsgType)` 分派到具体包（`asr_language`→`AsrPacket_in_asrLanguage`、`asr_mode`→`AsrPacket_in_asrMode`）。注：解码侧 `BaseAsrPacket.decode()` 以 `AsrDef.ASR_COMMAND_NAME`（值 `"ASR_COMMAND"`，`asr/biz/AsrDef.java:5`）校验 `asr_name`，与发送端 `ASR_MODULE_NAME` 为不同常量。

## 7. 关键文件索引（均在 `jadx_out/sources/` 下）

- IB 发送：`com/yunos/tvhelper/inputboost/biz/rc/IbRc.java`
- IB 连接/保活/帧：`inputboost/biz/main/{IB.java, comm/IbConn.java, packet/IbPacket.java, protocol/IbType.java}`
- 键值表：`inputboost/api/IbPublic.java:91`；IB 启动端口 3988：`inputboost/biz/IbBizBu.java:25`
- 体感：`inputboost/biz/sensor/IbSensor.java`
- UI→发送桥：`ui/rc/main/{RcUtil.java, RcCommon.java}`
- 触摸板/方向盘/摇杆：`ui/rc/main/rcpad/{TouchpadView,SteeringwheelView,JoystickView}.java` + `ui/rc/main/view/RcStickView.java`
- 远程输入：`ui/rc/rinput/{RinputMgr,RinputFragment}.java`
- IDC 包：`com/tmalltv/tv/lib/ali_tvidclib/packet/{BaseIdcPacket,IdcRawPacket_OpCmd_Key,IdcRawPacket_Ime_*,IdcPacketFactory}.java`；端口 `com/tmalltv/tv/lib/ali_tvidclib/IdcConst.java:5-6`
- 语音：`com/yunos/tvhelper/asr/biz/main/{ASR,AsrVConn}.java`

## 8. 不确定点

1. ~~IB 手柄键码 LB/RB/SELECT/START 与标准 Linux 码有出入，需抓包验证。~~ （已确认：码值取自 `SecExceptionCode` 混淆常量 A=306/B=305/LT=310/RT=311/START=312，且与真机实测一致，非标准 Linux 码）
2. IB 通道无加密/鉴权（hello 即建立，`IbPacket.encode()` 仅拼 20 字节头+原始 body，无 crypto）；IDC key>0 时 body 加密，**算法已追踪**：`AES/CBC/PKCS5Padding`，key=IV=派生 16 字节密钥（固定种子 `a31c5c871c597d133cb15cd68fefdc1a` + `(clientSeed^0x03129A8C^serverSeed)` 覆盖前 4 字节 + `HmacSHA256` 取前 16 字节；见 `CipherUtils.java` / `IdcEncryptionHelper.java` / `IdcConnection.java`）。
3. ASR VConn 分帧/多路复用格式**已可推导**（非未知项）：外层走 IDC VConn（module 名复用），内层 JSON 信封 `{asr_name, asr_data:{pk_type, pk_content}}`，`pk_type` 经 `AsrPacketFactory` 分派（见 `BaseAsrPacket.java` / `AsrVConn.java`）。
4. `sendMultitouchEventIf`(272) 与 `IB_TOUCH_SCALE=10000` 无 UI 调用方,为预留 API（全仓 grep 仅 `IbRc.java`/`IbPublic.java` 定义与声明，无调用方）。**10000px/s 速度阈值已确认**:`TouchpadView.java:351` 快速滑动时 `iAbs<10000` 发 1 次 keyClick,`≥10000` 发 2 次(双发连射)。注：`272` 为 IB 包类型 `PROTO_MULTITOUCH`，`IB_TOUCH_SCALE=10000` 为坐标缩放常量，二者为独立概念。
5. ~~IbObserver 的 isAvailable 判定细节（MODULEINFO 回包 264 解析）未读取。~~ （已确认：`isAvailable` 由 **HELLO 握手**驱动——`IB.processHelloResponse` 处理 `RSP_HELLO(0x10000001)` 后调用 `notifyAvailable(true)`（`IB.java:189`），断开/失败则 `notifyAvailable(false)`（`IB.java:76/169`）；`IbObserver.onAvailable()` 据此置 `mIsAvailable=true` 并通知监听者（`IbObserver.java:16-17`）。佐证：`PROTO_RSP_MODULEINFO=264` 仅为 `IbType.java:26` 常量，**全仓无任何 handler**（不会被解析）；`cur_app` 字段属于 `PROTO_CURRENTAPP=274`，由 `IB.java:201-223` 的 `processCurrentAppNotification` 解析，与 `isAvailable` 无关。）
