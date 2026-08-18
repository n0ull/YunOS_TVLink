# TODO / 备忘录

来源：三份旧研究（`tvhelper` 真机实测报告、`tvhelper2` Go 版、`yunos_tvhelper` Python+web 版）
交叉提取，均经 jadx 反编译复核与真机探针验证。协议帧格式基准已全量落地 `docs/re/`
（2026-07-28 回灌完成，含 Cmd 通道直发包节）；本文件留存真机档案、待办清单与工程备忘。

## 真机档案（TV-A，2026-07 实测）

- 端口：13510/13511/3988/13521 开放；13520 关闭（投屏走 ddh 下发的 13521）
- IB `ver=3.29`（≥3.13 → needIb313 键走 IB）；IB 魔数 `0x11228899` 握手成功
- IDC `mVer=2121108324`（≥2100200600 → LaunchSth 可用 activity_new）
- 截图：无加密 + 正确 connKey + 正确帧格式即成功（“加密墙”证伪，229,090 B JPEG）
- 投屏：固件 server_vers 3.2.0 不推 POST /event，状态/时长/进度轮询 `GET /playback-info`
- LaunchSth：lt=2+标准 action+extra_str 可触发 TV intent 解析（弹选择框）；
  `ACTION_DIAGNOSTIC` 无对应 activity；直拉酷喵三轮 scheme/包名均失败（详见已归档）
- OpCmd_Key（IDC 按键回退）：07-15 未命中，07-20 复测有效（VOL_UP 音量 OSD 实测）
- 该 PC mDNS 创建失败（WinError 10065）→ 子网扫描兜底是必需路径，非可选项
- 桌面端已验证：连接/按键/触控板/手柄/截图/投屏视频（状态/总时长/进度）
- Android 真机已验证：各遥控模式不闪退（2026-07-21），`5284b54` 主线程网络 IO 闪退修复真机闭环（P0 关闭）
- **RPM 诊断结论（2026-07-22，诊断脚本 `tools/tvhelper_tool/rpm_diag.py|pkg_probe.py|wake_probe.py`）**：
  此固件**无 appstore 模块**——登录广播仅 3 模块（`com.yunos.tv.asr:etao`/`RemoteControlServer`/
  `{"category":"immersive","name":"com.yunos.tv.yingshi.boutique"}`，后者实证 R3 的 JSON m_name 分支真实存在）；
  `Cmd_LaunchSth` 唤醒（lt=1/2 × appstore 动作、ACTION_DIAGNOSTIC）全部无广播回应，在此固件为空操作；
  `Cmd_PackageInfo`（20500）阳性对照有效（`com.youku.taitan.tv`→existed=True v11.8.3.24），
  5 个 appstore 候选包名全部 existed=False → **未安装**（非探测语义问题）；
  `login.name` 校验假设**证伪**（`app.tvlink` 与 `com.yunos.tvhelper` 登录广播完全一致）；
  Cmd 通道明文可用（8/8 应答，秒回）。**结论：RPM 在本机不可用属固件能力缺失，代码侧（含 R1～R4）已按协议就绪，待提供 appstore 模块的设备验证**

## 下一步（按优先级）

### P1 — 真机扫尾（现有硬件即可：TV-A + PC + Android 手机）

- [ ] **方向盘模式**（桌面+Android）：与鼠标移动同帧 `[2,0,dx,dy,0]`（docs/re/02 §4.2），低风险
- [ ] **体感模式**（仅 Android）：IB 257/260 + 缩放系数（加速度 x*2000/y*-2000/z*1000，陀螺仪 *286.47888）
      + 50 ms/类节流（docs/re/02 §4.4）
- [ ] **音乐投屏真机验证**：audio setmedia 基路 + 封面回拉——封面已实现并改注册制供片
      （`893c3b2`，Android MediaStore 取封面拷缓存；刻意不走原 App 绝对路径回退），未实测
- [ ] **播控残项真机验证**：`/seek`（已实现已验）、`/rate`（`04fa4e7` 已实现未验）、图片 `/zoom`
      （未实现，需求驱动；`/preload` 已判不做，见“明确不做”）
- 以上打完即发 v1.0：README 功能矩阵除硬件依赖项全绿

### P2 — 硬件依赖挂起（代码全部就绪，只等设备）

- [ ] **应用管理（RPM）——换设备验证**：本机（M638_ALI）固件无 appstore 模块（2026-07-22 诊断
      证实，见真机档案），**本机不可验证**；反编译复核判死替代路径（RPM 12 操作唯一通道 =
      appstore VConn，`immersive/yingshi.boutique` 是影视播放器与应用管理无关，LaunchSth 无任意
      包名拉起语义）。代码侧 R1～R4 已就绪（`RpmFixTest` 5/5，帧格式见 docs/re/05 §3）。
      获得提供 `com.yunos.idc.appstore` 模块的设备后：列表（4）→打开（14）→卸载（11）→URL 推装（7）；
      `result==2` 下载开始、`appStatus=18` 完成）；增量操作（UpdateRequest 20、续传 21——
      **packetId 21 与 UpdateResponse 撞号，靠收发方向消歧**，常量 `ID_CONTINUE_DOWNLOAD=24`
      为死常量）同轮验证。注：卸载/列表/打开在原 App v5.2.2 无 UI 调用点，该轮验证即首次实证
- [ ] **远程文字输入——换设备验证**：2026-07-25 双实证设备能力缺失——①原 App（加密会话 ver=1，
      排除“明文门”变量）同 TV 同搜索框不弹输入窗 → TV 不推 `Ime_StartInput`（10600）；②明文探针
      `tools/tvhelper_tool/ime_probe.py` 直发 `Ime_TextChange` 未上屏。根因 = 固件能力缺失
      （同 RPM appstore 一类）。代码侧已就绪：修复 `ImeStartInput.decodeBody` 布局错误（旧实现把
      inputType int 当 LPString 长度读、必抛异常杀整条连接——在有能力设备上进搜索框即断连）+
      hint/预填利用 + 钉桩 `imeStartInputDecodeMatchesDecompiledFormat`
- [ ] **BLE 魔投配网**：需 MagicCast 硬件（扫描过滤“MagicCast”前缀/UUID 0xb81d、GATT 三特征
      明文写、`"success"` notify 判成，见 docs/re/03 §A），无硬件挂起

## 明确不做

Racct 账号/支付（asoToken 在原 App 即空实现，docs/re/05 §7.3）、弹幕 MTOP、TV 搜索（依赖已停服云端）；
PROTO_MULTITOUCH（IB 272）/ IDC OpCmdMultitouch（11200）（原 App 亦无 UI 调用方）；
POST /preload（图片预览预载下一张，docs/re/04 §3）——本项目为单片选取模型，无画廊/幻灯片流，预载无消费方。

## 审查排期（2026-08-18 两轮复审搁置项，条件触发再动）

- **多网卡 TV 误拒**：媒体服务器按已连 TV IP 全等比对（`MediaHttpServer`），双网卡 TV 从另一接口
  回拉会被拒。拒绝路径已留日志（`487e301`）——真机若见「投屏无声失败 + 日志源 IP 为另一接口」，
  再改 /24 宽容比对
- **mDNS 乱序丢 projectionPort**：TXT/A 先于 PTR 到达时记录被丢弃（`Mdns.parse` 单趟扫描），下次
  通告自愈；双趟解析不值。真机若见投影端口发现延迟再动（测试名已定
  `txtBeforePtrKeepsProjectionPort`，写了会先红）
- **测试豁免**：`staleCleanupDoesNotKillNewSession`——需强制「清理协程排在新建连之后获锁」的
  反向调度，公开 API 无注入点；守卫本身已在（`887ff34` 锁顶校验 + `CastFeature` 世代跳过）
- **测试缺口**：`nextCastEvictsPreviousEntries`——投 A→投 B 后 A-URL 失效 + B 可用 + 封面同清，
  钉 `CastFeature.file()` 的清旧契约（clear 被删/挪位目前无报警）
- **世代机制固有纳秒窗**（接受，不修）：装回校验与赋值之间 disconnect 可交错（彻底闭合需
  disconnect 入锁、最坏阻塞 ~16 s，更差）；重连 job 点火与断开纳秒竞态（scheduleReconnect 捕获
  世代可闭合，窗口纳秒级）
- **startMediaServer 起服失败留陈旧 url**：`localLanAddress()`=null 或端口全占时服务器已停但
  `mediaServerUrl` 留旧值，`file()` 守卫被绕过——触发苛刻（纯 VPN 等无 site-local IPv4 网络），
  真机见到再修
- **LOW 批量**：0.0.0.0 绑定 / readLine 无上限 / 线程池无界队列（IP 校验已兜底）、IDC 跳帧日志
  节流、CastController connect 失败 fd 泄漏、重复 Content-Length last-wins、captureBurst 窄窗口
  假忙、onDisconnected 等锁 UI 停滞（最长 ~20 s）

## 已知天花板

- **ver=1 加密会话不可复刻**：body AES 可复刻（KDF = 固定种子 `a31c5c871c597d133cb15cd68fefdc1a`
  前 4 字节小端覆写 `(clientSeed ^ 51550860) ^ serverSeed`，HmacSHA256 取前 16 B，见 docs/re/01 §2.3），
  但 LoginReq seed 封装 `staticSafeEncrypt` 是阿里安全保镖闭源 native，不可还原（引入亦违背
  原创代码前提）。固定 ver=0 明文会话，实测电视答 ver=0 即通；天花板 = 可能连不上强制 ver=1
  的设备。注释放名处：`IdcConnection.awaitLogin` 的 `LoginEncryptionResp` 分支

## 未消费字段（持有，不实现——消费条件未满足）

- `ImeStartInput.options`/`actionId`/`actionLabel`：原 App 用于 IME 动作按钮渲染，待 IME 设备
- `DevInfoUpdateDdhParam.ddhKey`/`param`：TV→手机 DDH 参数推送，`DeviceInfo.ddhParams` 未动态更新
- `CmdPackageInfoResp.existed`：阳性对照语义（20600），UI 未暴露包查询（原 App 亦无）
- `RpmService`：`ID_GETLIST_RESP` 分页标志 `isFinished`/`isInterrupt`、`requestId` 回调配对未用
- `RpmService`：`appStatus==18` 完成通知未触发 UI 刷新（依赖 TV 推送，本机无模块不可验）

## 备选方案（条件触发）

- 图片投屏失败 → `PUT /image` 直传 JPEG（`yunos-assetkey` + `yunos-assetaction:
  cacheOnly|displayCached` 头，TV 直收，免本地 HTTP 服务；Python 实测可用）

## 工具

- **Python（首选，真机验证过）**：`tools/tvhelper_tool`（本地研究目录，已 gitignore，仅 AGENTS.md
      锚点入库），纯标准库：`python tvremote.py scan|send <ip> HOME|shell|mouse|click|stick|proj|proj-info|proj-video`。
      注意其 `tv_protocol.py` 注释里魔数十六进制是错的，十进制 287475865 才对（与 a116245 一致）
- **Go（tvhelper2，勿直接使用）**：`tvhelper2/tvhelper.exe` 的 IB 魔数写的是 `0x11223359`
      （≠ 真值 0x11228899，同一类十六进制转写错误），不修跑不通 IB 握手；仅发现功能可借鉴

## 已归档（完成）

- [x] **复审修复（2026-08-18，三轮 code review 零问题闭环）**：
  - **M-1** 建连早装回 + 世代守卫撤销分支（`30dbcd7`，AGENTS.md `6baaf36`）：`conn.connect()` 成功后
    立即 `connection = conn`（原在世代守卫之后），关闭 reader 回调 `connection === conn` 判据在装回前
    遗漏的窗口；世代守卫增加撤销分支（`connection === conn` 时置 null）。窗口收窄至 `conn.connect()`
    内部（reader 启动后至返回前）。**M-1'**（`d8a83f7`，AGENTS.md `2f6023c`）：失败分支纳入世代守卫
    （失败也不把 FAILED 覆写到更新状态）；`connect()` 锁顶闸（等锁期间出现更新意图则放弃）；投屏
    清旧从供片后挪至下次投屏通道就绪后（`CastFeature.file()` 先 `clear()` 再注册）。
  - **M-2** Discovery 子网扫描池 `shutdownNow()` 关闭（`e7940d2`，AGENTS.md `e4af076`）：消除
    `stop()` 竞态下（`running=false` 先于 `interrupt()` 到达池线程）`awaitTermination` 阻塞 ~20 s 窗口；
    所有探测 I/O 已有 1200 ms 超时，中断安全。
  - **LOW-1/1'/1''/1'''** MediaHttpServer 请求行限长（`c58318a/6c972f8/c93de09/ece3799`，
    AGENTS.md `42e0024/20f248a`）：`readLine()` 先分配整行再检查 → `readLimitedLine()` 逐字符读取、
    8 KB 上限、超限丢弃至行尾返回 null；边界语义对齐原 `length > limit`（恰好 limit 字符接受）；
    `BufferedReader.read()` 返回 Int，统一 `c.toChar() == '\n'/'\r'` 比较（`ece3799` 补编译验证）；
    drain-loop EOF 判断顺序对齐（`faefdce`）。边界测试（`9449d09/9449d09/d0d79c8`）：raw socket
    发送恰好 8 KB 请求行（接受，200 + body 匹配）与 8193 字符请求行（直接关连），socket 用 `.use {}`
    管理生命周期。

- [x] **README 瘦身 + 排版规范（2026-08-06）**：README 仅保留结果（功能、机制、状态），验证过程
      与诊断叙述从 README 移除，以本文件档案为准（既有档案已全覆盖，无信息丢失）；两文件按
      document-style-guide 统一排版（全角引号、～范围连接号、单位前空格、行内代码后全角括号）
- [x] **文档回灌与 TODO 重排（2026-07-28）**：docs/re/02 §5 OpCmd_Key 回退复测有效结论；
      docs/re/05 §7.5 login.name 校验证伪 + 模块常驻性因固件而异；docs/re/05 §3 新增“Cmd 通道
      直发包”节（20400/211xx/205xx/207xx/20300/11100/11200/11000/10400）；README 语音/IME 行
      修订；`IdcConnection` ver=1 天花板 ponytail 注明
- [x] **反编译协议核对（2026-07-26）全部修正并落地 docs/re**：DevNameUpdate（11000）body 改解析
      `{"dev_name"}` JSON（原读裸串）+ 补 `encodeBody()`；ImeStartInput（10600）补 options/actionId/
      actionLabel 6 字段；LoginEncryptionResp（10090）补 `encryptionAlgorithmVer`；新增包类注册
      （11100/10400/11200/20500～20600/20700～20800，`IdcPacketFactory.create()`）；RPM JSON 键名
      （列表项 `status`、响应 `result`、`ID_GETAPPINFO_RESP`（3）读 `appIsExist`）；VConnFin（20300）
      下发线（`modules.remove` + online=false，补原 App 双路径下线）
- [x] 语音指令真机验证（2026-07-25，TV-B）：探针 `tools/tvhelper_tool/asr_probe.py`
      按修复后线协议逐字节发包，截图实证 TV 弹出语音 UI 回显“打开优酷”且优酷进入前台（前置
      广告 com.yunos.advert.service 属正常流程）。修复要点：模块名 `com.yunos.tv.asr:etao`（裸名
      曾致静默丢弃 = 原根因）、首包前 VConn SYN、`asr_name`=模块全名、`finish:"true"`、
      `result_code:0`；钉桩 `AsrTextServiceTest` 4/4。注：TV 应答推 `asr_language`（stringified JSON，
      `asr_name` 电视→手机方向才是 `"ASR_COMMAND"`）。**会话帧必须完整**：`record_start`→
      `asr_streaming(finish)`→`record_stop`（间隔 150 ms）——只发 finish 包指令照执行但“聆听中”
      卡片卡死（裸 record_stop 无效；ESC 键可兜底关卡片）；导航类指令 NLU 不执行属技能侧限制。
      原 App `ASR.sendText` 全 APK 零调用点（死代码）。**设备侧 STT 已放弃**：测试机识别服务为
      GoogleRecognitionService，无网/缺语言包/AppOps 报 error 9+12，枚举 getVoiceDetailsIntent
      返回 null；移动端与桌面统一为 ⌨ 文字输入（协议相同）。备选存档：sherpa-onnx 离线（+25 MB）/讯飞在线（需账号）
- [x] 图片投屏真机验证（2026-07-25）：`/setmedia` image 类型主路成功（本地 MediaHttpServer 供片）
- [x] LaunchSth 语义实证（2026-07-25，`launch_probe.py`）：extra_str = intent data URI，实用价值
      止于弹选择框；设置屏“TV 诊断页”入口按 ponytail 清理（唯一可测设备实证空操作，语义见
      docs/re/05 §3 与 `CmdLaunchSth` 注释，一行可恢复）
- [x] Cmd_SysProp 真机验证（2026-07-25）：设置屏查询 `ro.product.model` 回 `M638_ALI`；实现
      `SysPropReq/Resp`（Resp 无 dummy 段）+ `SysPropService`；钉桩
      `sysPropCmdFramingMatchesDecompiledFormat` 通过
- [x] 投屏播放控制修复+真机验证（2026-07-25）：播放/暂停/退出曾在 Android 主线程直调阻塞
      socket → NetworkOnMainThreadException（message 为 null，日志 `request failed: null` 曾误判
      协议问题），收进 VM 走 IO；`onConnected` 重复触发不释放旧 CastController 曾致双 13521
      控制会话，`connectCast` 先断后建+onResume 单独补建；音量轮询同步+当前值/拖动预览
- [x] RPM 修复 R1～R4（2026-07-22，依据 docs/re/05 §3 + 反编译复核）：R1 `RpmService.MODULE_NAME`
      → `com.yunos.idc.appstore`；R2 module 离线时首个请求发 CmdLaunchSth（lt=1 service 唤醒，
      无版本门）→ 等 ModuleAvailability → openVConn 补发；R3 `ModuleAvailability.decodeBody`
      增加 `m_name` JSON fallback；R4 `parseAppArray` 原实现已兼容 `apps` 单对象（零改动）
- [x] RPM 真机诊断定位根因（2026-07-22）：本机固件无 appstore 模块/包，LaunchSth 唤醒空转，
      “应用管理暂时没用”根因 = 设备能力缺失而非代码缺陷；顺带修复 AppsScreen 进屏不自动
      获取（需手按刷新）+ 空列表无提示两个 UX 缺陷
- [x] 投屏状态/总时长/进度（2026-07-21 桌面端复测，`4679231` 改轮询 playback-info）
- [x] Android 实机验证关闭 P0（2026-07-21）：各遥控模式真机无闪退
- [x] 发送改后台发送线程（`5284b54`，桌面端各模式验证；Android 真机 2026-07-21 确认不闪退）
- [x] 截图：帧格式修正（`547a8bd`）+ 无加密出图（2026-07-20 探针；2026-07-21 桌面端复测）
- [x] IB 魔数/手柄键码/路由（`a116245`，手柄模式真机验证）
- [x] IDC OpCmd_Key 兜底验证（2026-07-20，VOL_UP 音量 OSD）
- [x] `Discovery.probeHost` TCP 3988（IB）探测兜底分支（`4ff483a`）：probeIdc/probeIb 并行，
      IB hello 响应解析 ver/sid
