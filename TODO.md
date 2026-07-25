# TODO / 备忘录

来源:三份旧研究(`tvhelper` 真机实测报告、`tvhelper2` Go 版、`yunos_tvhelper` Python+web 版)
交叉提取,均经 jadx 反编译复核与真机探针验证。关键修正已落地:`a116245`(IB 魔数/手柄
键码/路由)、`5284b54`(发送改后台线程,修 Android 按键闪退)、`547a8bd`(截图 Cmd 帧格式)、
`4679231`(投屏改轮询 playback-info)。

## 真机档案(2026-07-20/21 实测,TV 192.168.1.109)

- 端口:13510/13511/3988/13521 开放;13520 关闭(投屏走 ddh 下发的 13521)
- IB `ver=3.29`(≥3.13 → needIb313 键走 IB);IB 魔数 `0x11228899` 握手成功
- IDC `mVer=2121108324`(≥2100200600 → LaunchSth 可用 activity_new)
- 截图:无加密 + 正确 connKey + 正确帧格式即成功(「加密墙」证伪,229090B JPEG)
- 投屏:固件(server_vers 3.2.0)不推 POST /event,状态/时长/进度轮询 `GET /playback-info`
- LaunchSth:lt=2+标准 action+extra_str(data URI 语义)可触发 TV intent 解析(弹选择框);
  `ACTION_DIAGNOSTIC` 无对应 activity;直拉酷喵三轮 scheme/包名均失败(详见 P2-1)
- OpCmd_Key(IDC 按键回退):有效(VOL_UP 音量 OSD 实测)
- 该 PC mDNS 创建失败(WinError 10065)→ 子网扫描兜底是必需路径,非可选项
- 桌面端已验证:连接/按键/触控板/手柄/截图/投屏视频(状态/总时长/进度)
- Android 真机已验证:各遥控模式不闪退(2026-07-21),`5284b54` 主线程网络 IO 闪退修复真机闭环(P0 关闭)
- **RPM 诊断结论(2026-07-22,诊断脚本 `tvhelper/tvhelper_tool/rpm_diag.py|pkg_probe.py|wake_probe.py`)**:
  此固件**无 appstore 模块**——登录广播仅 3 模块(`com.yunos.tv.asr:etao`/`RemoteControlServer`/
  `{"category":"immersive","name":"com.yunos.tv.yingshi.boutique"}`,后者实证 R3 的 JSON m_name 分支真实存在);
  `Cmd_LaunchSth` 唤醒(lt=1/2 × appstore 动作、ACTION_DIAGNOSTIC)全部无广播回应,在此固件为空操作;
  `Cmd_PackageInfo`(20500)阳性对照有效(`com.youku.taitan.tv`→existed=True v11.8.3.24),
  5 个 appstore 候选包名全部 existed=False→**未安装**(非探测语义问题);
  `login.name` 校验假设**证伪**(`app.tvlink` 与 `com.yunos.tvhelper` 登录广播完全一致);
  Cmd 通道明文可用(8/8 应答,秒回)。**结论:RPM 在本机不可用属固件能力缺失,代码侧(含 R1–R4)已按协议就绪,待提供 appstore 模块的设备验证**

## 下一步(按优先级)

### P1 — 未测功能真机验证(桌面/Android)

- [ ] **应用管理(RPM)——换设备验证**:本机(M638_ALI)固件无 appstore 模块(2026-07-22 诊断
      证实,见真机档案),**本机不可验证**。代码侧 R1–R4 已就绪(`RpmFixTest` 5/5);
      若获得提供 `com.yunos.idc.appstore` 模块的设备:列表(4)→打开(14)→卸载(11)→URL 推装(7;
      `result==2` 下载开始、`appStatus=18` 完成)。注:卸载/列表/打开在原 App v5.2.2 无 UI
      调用点,电视端实现属推断,该轮验证即首次实证。
      **反编译复核(2026-07-25,判死替代路径)**:RPM 全部 12 操作唯一通道 = appstore VConn
      (`RPM.java` 全走 `mRpmModule.sendVConnPacket`),无第二路径;本机第三模块
      `immersive/yingshi.boutique` = 沉浸式影视播放器(`ImmersivePublic`),与应用管理无关;
      `LaunchSth` 仅 intent action 拉起(诊断页/唤醒 service),无任意包名拉起语义,不能替
      「打开应用」;全 jadx 无已知包名字典可复用。**此设备应用管理结构性不可行**
- [x] 图片投屏——**真机已验证(2026-07-25)**:`/setmedia` image 类型主路成功(本地
      MediaHttpServer 供片),备选 `PUT /image` 未启用
- [ ] 远程文字输入——**设备能力缺失,挂起(2026-07-25 双实证)**:①原 App(加密会话 ver=1,
      排除「明文门」变量)同 TV 同搜索框不弹输入窗 → TV 不推 `Ime_StartInput`(10600);
      ②明文会话探针 `tvhelper/tvhelper_tool/ime_probe.py` 直发 `Ime_TextChange` 未上屏 →
      TV 侧无远程输入状态机。根因 = 固件能力缺失(同 RPM appstore 一类)。代码侧已就绪:
      修复 `ImeStartInput.decodeBody` 布局错误(旧实现把 inputType int 当 LPString 长度读、
      必抛异常杀整条连接——在有能力设备上进搜索框即断连)+ hint/预填利用 +
      钉桩 `imeStartInputDecodeMatchesDecompiledFormat`;待提供 IME 推送的设备验证
- [x] 语音指令(桌面文本输入 → `asr_streaming`)——**真机已验证(2026-07-25,TV 192.168.1.105)**:
      探针 `tvhelper/tvhelper_tool/asr_probe.py` 按修复后线协议逐字节发包,截图实证 TV 弹出语音 UI
      回显「打开优酷」且优酷进入前台(前置广告 com.yunos.advert.service 属正常流程);
      修复要点:模块名 `com.yunos.tv.asr:etao`(裸名曾致静默丢弃,=原根因)、首包前 VConn SYN、
      `asr_name`=模块全名、`finish:"true"`、`result_code:0`;钉桩 `AsrTextServiceTest` 4/4。
      注:TV 应答推 `asr_language`(stringified JSON,`asr_name` 电视→手机方向才是 "ASR_COMMAND")。
      **会话帧必须完整**:`record_start`→`asr_streaming(finish)`→`record_stop`(间隔 150ms)——
      只发 finish 包指令照执行但「聆听中」卡片卡死(裸 record_stop 无效;ESC 键可兜底关卡片);
      导航类指令(返回桌面)NLU 不执行属技能侧限制。原 App `ASR.sendText` 全 APK 零调用点(死代码)。
      **设备侧 STT 已放弃(2026-07-25 决策)**:测试机识别服务为 GoogleRecognitionService,
      无网/缺语言包/AppOps 报 error 9+12,枚举 getVoiceDetailsIntent 返回 null;移动端与桌面
      统一为 ⌨ 文字输入(协议相同)。备选存档:sherpa-onnx 离线(+25MB)/讯飞在线(需账号)
- [ ] 方向盘模式(= 鼠标移动同路,低风险)/ 体感模式(仅 Android)
- [ ] BLE 魔投配网(需魔投硬件;无硬件则挂起)

### P2 — 新协议能力(已逆向,按需;帧格式注意)

> CmdReqBase 家族(SysProp/PackageInfo/PathInfo)body 为两段 LPString
> (`LPString({"cmdReqID":N})` + `LPString({参数})`);LaunchSth 例外,单段 LPString 直发。

1. `Cmd_LaunchSth`(20400):**真机语义实证(2026-07-25,`tvhelper/tvhelper_tool/launch_probe.py`)**:
   lt=2(activity_new)+标准 action+extra_str 可触发 TV intent 解析——**extra_str 被当
   intent data URI**(非包名/组件):VIEW/MAIN+裸包名或 URI 弹「以什么应用打开」选择框
   (VIEW+任意 data 仅泰坦桌面应答;MAIN+data 匹配一堆应用)。`ACTION_DIAGNOSTIC` 在
   M638_ALI 无对应 activity(07-22 空转复证);直拉酷喵(com.youku.taitan.tv)失败——
   `youkutv://`/`youku://`/`http://v.youku.com/` 均无导出 handler,无 manifest 不续猜。
   service 型+非法 action 会致 TV 断连(WinError 10053)。
   **结论:协议存活但实用价值止于弹选择框**;曾实现的设置屏「TV 诊断页」入口已按
   ponytail 清理(唯一可测设备实证空操作,「有能力固件上有效」属推测需求=死 UI;
   需要时语义见本条与 `CmdLaunchSth` 注释,一行可恢复)。
   反编译实见 action:
   `com.yunos.tv.intent.RemoteControlServer.ACTION_DIAGNOSTIC`(activity_new)、
   `yunos.appstore.startprocessservice`(service,RPM R2 已在用)。<2100200600 直接不发包。
2. `Cmd_SysProp`(21100/21200):读写 TV 系统属性。**真机已验证(2026-07-25)**:设置屏查询
   `ro.product.model` 回 `M638_ALI`。实现:`SysPropReq/Resp`(`IdcPackets.kt`,对齐反编译
   `IdcPacket_Cmd_SysProp_Req/Resp.java`,Resp 无 dummy 段)+ `SysPropService`(按 prop_key
   配对应答);UI 仅暴露读,写 `setProp` 留服务层。钉桩
   `sysPropCmdFramingMatchesDecompiledFormat` 通过。
3. AppStore 增量:UpdateRequest(20)、ContinueDownload、GetListCancel(26)、
   GetAppInfo(2/3)。⚠ packetId **21 撞号(已证实)**:`ContinueDownloadRequest`
   `super(21)`(`IdcPacket_ContinueDownloadRequest.java:7,17`)与 `ID_UPDATE_RESPONSE`=21
   冲突,靠收发方向消歧;常量 `ID_CONTINUE_DOWNLOAD=24` 为死常量(零引用)。
   实现续传**必须发 21**。

**明确不做**:Racct 账号/支付、弹幕 MTOP、TV 搜索(依赖已停服云端);
PROTO_MULTITOUCH(原 App 也无 UI 调用方)。

## 备选方案(条件触发)

- 图片投屏失败 → `PUT /image` 直传 JPEG(`yunos-assetkey` + `yunos-assetaction:
  cacheOnly|displayCached` 头,TV 直收,免本地 HTTP 服务;Python 实测可用)
- 加密深挖(已非必需,仅存档):KDF = init-key 串 `a31c5c871c597d133cb15cd68fefdc1a`
  转 16B,前 4 字节小端覆写 `(clientSeed ^ 51550860) ^ serverSeed`,同 buffer 作
  key=data 做 HmacSHA256 取前 16B 得 AES-128(`IdcEncryptionHelper.java`)

## 工具

- **Python(首选,真机验证过)**:`D:\n0ull\Desktop\1\Java\tvhelper\tvhelper_tool`,纯标准库:
  `python tvremote.py scan|send <ip> HOME|shell|mouse|click|stick|proj|proj-info|proj-video`。
  注意其 `tv_protocol.py` 注释里魔数十六进制是错的,十进制 287475865 才对(与 a116245 一致)。
- **Go(tvhelper2,勿直接用)**:`tvhelper2/tvhelper.exe` 的 IB 魔数写的是 `0x11223359`
  (≠ 真值 0x11228899,同一类十六进制转写错误),不修跑不通 IB 握手;仅发现功能可借鉴。

## 已归档(完成)

- [x] 投屏播放控制修复+真机验证(2026-07-25):播放/暂停/退出曾在 Android 主线程直调阻塞
      socket → NetworkOnMainThreadException(message 为 null,日志 `request failed: null` 曾
      误判协议问题),收进 VM 走 IO;`onConnected` 重复触发不释放旧 CastController 曾致双
      13521 控制会话(TV 会话归属错乱),`connectCast` 先断后建+onResume 单独补建;音量轮询
      同步+当前值/拖动预览(此前 slider 固定 10f 不取 TV 值)

- [x] IDC OpCmd_Key 兜底验证(2026-07-20,VOL_UP 音量 OSD)
- [x] 截图:帧格式修正(`547a8bd`)+ 无加密出图(2026-07-20 探针;2026-07-21 桌面端复测)
- [x] 投屏状态/总时长/进度(2026-07-21 桌面端复测,`4679231`)
- [x] IB 魔数/手柄键码/路由(`a116245`,手柄模式真机验证)
- [x] 发送改后台发送线程(`5284b54`,桌面端各模式验证;Android 真机 2026-07-21 确认不闪退)
- [x] Android 实机验证关闭 P0(2026-07-21):各遥控模式真机无闪退
- [x] `Discovery.probeHost` TCP 3988(IB)探测兜底分支(`4ff483a`):probeIdc/probeIb 并行,IB hello 响应解析 ver/sid
- [x] RPM 修复 R1–R4(2026-07-22,依据 `docs/re/05` §3 + 反编译复核):
  - R1:`RpmService.MODULE_NAME` → `com.yunos.idc.appstore`(`IdcConstant.java:6`/`RpmObserver.java:16`)
  - R2:module 离线时首个请求发 `CmdLaunchSth`(20400,launch_type=1=service,action
    `yunos.appstore.startprocessservice`,单 LPString;`IdcCmds.java:115-117` 证实 service
    唤醒无版本门)→ 等 ModuleAvailability → openVConn 补发挂起请求(原 App 流程 `RpmObserver.java:74-77`)
  - R3:`ModuleAvailability.decodeBody` 增加 `m_name` JSON `{"name":…}` fallback(`IDC.java:360-368`)
  - R4:`parseAppArray` 经测试证实原实现已兼容 `apps` 单对象(零代码改动;`IdcPacket_GetListResponse.java:62-65`)
- [x] RPM 真机诊断定位根因(2026-07-22):本机固件无 appstore 模块/包,LaunchSth 唤醒空转,
      「应用管理暂时没用」根因 = 设备能力缺失而非代码缺陷(证据链见真机档案);
      顺带修复 AppsScreen 进屏不自动获取(需手按刷新)+ 空列表无提示两个 UX 缺陷
