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
      调用点,电视端实现属推断,该轮验证即首次实证
- [ ] 图片投屏(`/setmedia` image 类型;失败则换「备选」的 `PUT /image`)
- [ ] 远程文字输入(电视端触发 IME,如进入搜索框,手机应弹输入窗)
- [ ] 语音指令(Android 系统语音识别 / 桌面文本输入 → `asr_streaming`)
- [ ] 方向盘模式(= 鼠标移动同路,低风险)/ 体感模式(仅 Android)
- [ ] BLE 魔投配网(需魔投硬件;无硬件则挂起)

### P2 — 新协议能力(已逆向,按需;帧格式注意)

> CmdReqBase 家族(SysProp/PackageInfo/PathInfo)body 为两段 LPString
> (`LPString({"cmdReqID":N})` + `LPString({参数})`);LaunchSth 例外,单段 LPString 直发。

1. `Cmd_LaunchSth`(20400):实现最简单。反编译实见 action:
   `com.yunos.tv.intent.RemoteControlServer.ACTION_DIAGNOSTIC`(activity_new)、
   `yunos.appstore.startprocessservice`(service)。<2100200600 直接不发包。
   注:应用管理页「打开」已走 AppStore(14) 覆盖常规拉起,LaunchSth 的增量价值
   是诊断页与自定义 service。
2. `Cmd_SysProp`(21100/21200):读写 TV 系统属性。
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
