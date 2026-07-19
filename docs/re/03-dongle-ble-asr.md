# 03 Dongle 蓝牙配对与语音链路（dongle-asr-agent）

## A. Dongle BLE 配对与配网

### 架构链路
UI（`DongleBLEPairFragment`）→ `PairControllerFactory` → `BLEPairServiceController` → 前台 `DongleBlePairService` → 核心 `DongleBlePairManager`（直接用 Android BLE API）。Service↔UI 用广播：结果 `com.yunos.tv.dongle.action.BLE_PAIR`（extra_pair_result 1成/2败），取消 `BLE_OPTION_ACTION`。

- `ui/dongle/pair/DongleBlePairService.java:56-87`（startPair 传 ssid/password）
- `ui/dongle/fragment/DongleBLEPairFragment.java:76-78`（ssid/password/security/hotelMode 来自 Wi-Fi 设置页）

### 扫描过滤（`DongleBlePairManager.isDongle`, :438-455）
任一命中即 Dongle：
1. 设备名以 **"MagicCast"** 开头（`res/values/strings.xml:567`）；
2. 广播包含 16-bit Service UUID **0xb81d**（128-bit: `0000b81d-0000-1000-8000-00805f9b34fb`, :74）。

老式 `startLeScan`，扫描超时 40s；支持多台同时配网（每 MAC 独立状态机）。

### GATT UUID（:70-74）
| 用途 | UUID |
|---|---|
| Service | `00006287-3c17-d293-8e48-14fe2e4da212` |
| 写 Wi-Fi 密码 | `f000aa21-0451-4000-b000-000000000000` |
| 写 SSID | `f000aa22-0451-4000-b000-000000000000` |
| 写安全类型 + notify 结果回传 | `f000aa23-0451-4000-b000-000000000000` |

注：`equalsUUID`(:826-847) 额外做字节序反转比较，兼容固件大小端。

### 配网数据格式（`startWriteConnectMsg` :476-496, `writeCharacteristic` :548-613）
- 三特征各排写队列：aa21←密码 UTF-8 明文；aa22←SSID UTF-8 明文；aa23←`"<security>;<hotelMode>"`（如 `"2;0"`；security：0=OPEN/1=WEP/2=PSK/3=EAP，`utils/WifiSecurityUtil.java:11-15`），同时对 aa23 开 CCC notify。
- 按 **18 字节分包**（MTU23-5），writeType=2（带响应），`onCharacteristicWrite` 串行驱动下一包。
- **凭证明文 BLE 直写，无鉴权/加密**。

### 状态机（:47-53）
`PREPARE(0)→CONNECT(1)→WRITING(2)→WRITE_SUCCEED(3)→PAIR_SUCCEED(4)/PAIR_FAIL(5)`。
- 反射调 `connectGatt(...,transport=2/LE)`(:462)，连上后延 500ms discoverServices；断线且 state<3 重连最多 3 次。
- 成功判定：设备经 aa23 notify 回传 UTF-8 字符串 **恰等于 "success"**（:282-290），否则 fail；随后关 GATT。

## B. 配对后通信通道：局域网（IDC），不走 BLE
BLE 仅一次性配网。之后：
1. `pair/DevAutoConnector.java:51-61`：IDC 局域网遍历按设备名找到 Dongle → `DevmgrApiBu.connect(uuid)` 建 IDC 长连接。
2. 遥控按键：`DongleRemoteControlFragment` → `ui/dongle/utils/RcUtil.java:38-58` → `IbApiBu.api().rc()`（与电视遥控共用的 inbox 通道，IDC over LAN）。
3. Dongle 设置（系统信息/分辨率/出厂/重启/网络诊断）：`ui/dongle/rcs/biz/RcsSetting.java`，IDC 模块 **`com.ali.ott.dongle.setting`**（`rcs/api/RcsPublic.java:17`），JSON `RcsPacket_*`。
4. 播放状态同步：`RemoteController.java` 经 IDC + Immersive 服务监听 `com.yunos.tv.yingshi.boutique` 播放器快照。

## C. 语音链路（ASR）

### 引擎：阿里 iDST/ETAO 在线流式 ASR，**非讯飞**
- JNI 加载 `jsoncpp`+**`alitvasr_opus`**+**`alitvasr_sdk`**（`asr/biz/main/jni/AsrJniImp.java:55-63`）。全库无 `iflytek`/`loadLibrary("msc")` 引用 → `libmsc.so` 未被使用（残留/打包冗余）；`alicomphonenumberauthsdk` 属号码认证，与语音无关。
- 云端：**`speechapi.m.taobao.com`**（etao 对话 API），`appId="com.alibaba.alitv.3.0"`，编码 **`sc="opu"`（Opus）**，`queryType/dsType="dialogue"`（`jni/AsrInitCfg.java`）。
- appKey 两代（`ASR.initRecognizer`, `asr/biz/main/ASR.java:102-118`）：
  - IDST 模式：`alitv-ds-streaming`（asrMode==1 输入法场景用 `alitv-shurufa-online`）——手机只拿流式文本；
  - 老模式：`21721491`——SDK 直接回含 NLP 的结果。
  - 由电视端 ASR 模块版本 ≥ **2100300000** 决定走 IDST（`biz/observer/AsrObserver.java:24`）。

### 录音参数
`jni/AudioRecorderImp.java:28-41`：`AudioRecord(MIC, sRate, MONO, PCM_16BIT)`，buffer=帧长×40，按帧回调 native 做 Opus 编码上行。**采样率由 native 传入，Java 侧不可见；按该 SDK 惯例应为 16 kHz——不确定。**
控制参数（ASR.java:113-116）：自动停录开、无声停滞上限 10s、最短录音 1500ms、静音阈值 400。状态机 `IDLE→WILL_START_RECORD→RECORDING→WILL_STOP_RECORD→RECOGNIZING`。UI 为按住说话：`ui/rc/asr/AsrView.java:168-190`（按下 startRecord/松开 stopRecord）。

### 结果下发：手机不执行指令，实时转发电视
IDC 虚拟连接模块 **`com.yunos.tv.asr:etao`**，包 `{"asr_name":"ASR_COMMAND","asr_data":{"pk_type":...,"pk_content":...}}`（`biz/AsrDef.java`、`packet/BaseAsrPacket.java:41-96`）：
- 手机→电视：
  - `asr_streaming`（IDST）：每次中间/最终结果即转，字段 `result_code/question(从 asr_out JSON 取 "result")/finish/手机型号`（`packet/AsrPacket_out_asrStreaming.java:26-51`）——边说边推流式文本；
  - `recognize_result`（老模式）：整条 `RecogResult`（`asr_out/ds_out/nlp_out/results`）；
  - `start_record/stop_record/volume(0-100)`：录音状态/音量同步电视 UI。
- 电视→手机：`asr_language`（仅 mandarin，其他语言拒绝启动 ASR.java:146）、`asr_mode`。
- 键盘输入复用：`ASR.sendText` 伪造 `{"finish":1,"result":"...","status":1,"version":"4.0"}` 的 asr_streaming 包发电视（ASR.java:176-196）。

### 指令映射
**手机端无 NLU/slot 解析、无映射表。** 文本经 IDC 交电视端 `com.yunos.tv.asr` 服务做语义理解并执行搜索/遥控；手机拿到最终结果仅做 UT 埋点 `ASR_RESULT`+复位按钮（`AsrView.java:130-135`）。老模式云端 `nlp_out/results` 亦原样转发。**"指令→电视操作"映射在电视固件侧，APK 内不存在。**

## 关键文件
| 主题 | 文件：行 |
|---|---|
| BLE UUID/状态机/写包 | `ui/dongle/pair/DongleBlePairManager.java:70-74, 476-613` |
| 配对 Service | `ui/dongle/pair/DongleBlePairService.java:56-87` |
| 设备过滤 | `DongleBlePairManager.java:438-455`；`res/values/strings.xml:567` |
| 配网 UI 入口 | `ui/dongle/fragment/DongleBLEPairFragment.java:58-78` |
| 配对后回连 | `ui/dongle/pair/DevAutoConnector.java:51-61` |
| Dongle 遥控 | `ui/dongle/utils/RcUtil.java:38-58` |
| Dongle 设置 | `ui/dongle/rcs/biz/RcsSetting.java`；`rcs/api/RcsPublic.java:17` |
| ASR JNI/库 | `asr/biz/main/jni/AsrJniImp.java:55-63` |
| ASR 云配置 | `asr/biz/main/jni/AsrInitCfg.java` |
| 录音 | `asr/biz/main/jni/AudioRecorderImp.java:28-41` |
| ASR 主逻辑 | `asr/biz/main/ASR.java:102-118, 265-298` |
| 语音包协议 | `asr/biz/main/packet/BaseAsrPacket.java:41-96`、`AsrPacket_out_asrStreaming.java` |
| IDC 模块注册 | `asr/biz/observer/AsrObserver.java:24, 70` |
| 按住说话 UI | `ui/rc/asr/AsrView.java:168-190, 130-135` |

## 不确定之处
1. 采样率：native 传入，Java 无值；惯例 16 kHz。
2. 电视端 NLU/指令映射在固件侧，APK 不可见。
3. `libmsc.so` 无 Java 引用，判为未使用（未核 .so 间依赖）。
4. `com.ali.ott.dongle` 包另有一套经典蓝牙 SPP+Socket 配网实现（`bluetoothUtil/BluetoothClient*`），属 OttDongle 旧库/另一种配件，与 `ui/dongle` 的 BLE 流程独立，未展开。
