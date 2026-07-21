# TODO / 备忘录

来源：`D:\n0ull\Desktop\1\Java\tvhelper`(同一 APK 5.2.2 的早期逆向 + **2026-07-15 真机实测**)、
`D:\n0ull\Desktop\1\Java\tvhelper2`(Go 版)、`D:\n0ull\Desktop\1\Java\yunos_tvhelper`(带 web
遥控器的 Python 版)的交叉提取,均经 jadx 反编译复核。已落地的修正见 `a116245`
(IB 魔数/手柄键码/路由)、`547a8bd`(截图 Cmd 帧格式)。以下为本项目待办。

## 真机档案(2026-07-20 探针实测,TV 192.168.1.109)

- 端口:13510/13511/3988/13521 开放;13520 关闭(投屏走 ddh 下发的 13521)
- IB `ver=3.29`(≥3.13 → needIb313 键走 IB 通道);IB 魔数修正后握手成功
- IDC `mVer=2121108324`(≥2100200600 → LaunchSth 可用 activity_new)
- **截图无加密成功**(229090 字节 JPEG)——加密墙不成立
- **投屏:TV 不推 POST /event**(server_vers 3.2.0),播放状态/时长/进度靠轮询
  `GET /playback-info`(自带 state 字段;未知时长报 `duration:-1`)——`4679231` 已改为
  play() 后 1s 轮询,真机复测总时长/进度均正常
- 本 PC mDNS 套接字创建失败(WinError 10065)→ 子网扫描兜底是必需路径,非可选项

## 待真机验证

### 0. IDC OpCmd_Key 兜底路径有效性 — ✅ 已验证有效(2026-07-20)

反编译事实:`OpCmd_Key`(10500)在 App 端**确实接线**——唯一发送点 `IbRc.java:71`(IB 不可用
且有 Android 键码时回退,op=keyClick);编码 `int keyCode + int op.ordinal`(click=0/down=1/up=2)
与本项目 `OpCmdKey` 一致。`OpCmd_Multitouch`(11200)/`OpCmd_MouseClick`(10400)手机端
**无发送方**(仅 `IdcPacketFactory` 注册用于解码),纯预留。

2026-07-20 探针:以正确 connKey 连发 3 次 `OpCmd_Key(VOL_UP=24, op=keyClick)`,
**电视音量 OSD 出现**——兜底路径在此 TV 上有效,`RcController` 的静默回退是真实可用的
(IB 掉线时按键仍可达)。tvhelper2 的「未命中」结论同样系错误 key(UNASSIGNED)所致。

## 已知限制

### 1. 截图 — ✅ 已解决(2026-07-20 真机验证通过)

无加密 IDC + 正确 connKey + 正确帧格式,真机截屏成功(229090 字节 JPEG,192.168.1.109)。
「加密墙」不成立——tvhelper idc.py 的断连实验系其自身三处 bug 所致(UNASSIGNED key、
conn_key 读错偏移、Cmd body 缺 `LPString({"cmdReqID":N})` 前缀段)。本项目帧格式已于
`547a8bd` 修正。剩余工作:

- [ ] App 内验证:`ScreenshotService` 走真机截图(`./gradlew :desktopApp:run` 或 Android)
- [ ] Cmd 类包实现时的帧格式分工:**CmdReqBase 家族**(ScreenShot/SysProp/PackageInfo/PathInfo)
      要带 `LPString({"cmdReqID":N})` 前缀段;**LaunchSth 例外**——它直接继承 BaseIdcPacket,
      body 是单段 `LPString({"launch_type":N,"action":A,"extra_str":E})`,且由
      `idcComm().sendPacket()` 直发(IdcCmds.java:151),不走 IdcCmds 的 req/resp 配对
- [ ] (加密深挖已非必需,仅存档)KDF:`a31c5c871c597d133cb15cd68fefdc1a` 转 16B,前 4 字节
      小端覆写 `(clientSeed ^ 51550860) ^ serverSeed`,同 buffer 作 key=data 做 HmacSHA256
      取前 16B 得 AES-128(`IdcEncryptionHelper.java`)

## 健壮性增强

### 2. 子网扫描补 3988 探测 — 低优先

实测有 TV 不应答 mDNS(防火墙/精简固件)。本项目兜底是扫 13511 做 DETECT 登录(比
tvhelper 工具扫 3988 更强,能拿设备名);但若某型号 TV 不监听 13511 会漏扫。

- [ ] `Discovery.probeHost` 增加 TCP 3988 端口探测分支(仅证明"是 TV",无设备名)

### 3. 图片投屏备选路径 `PUT /image` — 备选

Python 实测可用:`PUT /image`,头 `yunos-assetkey: <id>` + `yunos-assetaction:
cacheOnly|displayCached`(可空),body 为 JPEG 字节。TV 直收,无需手机起 HTTP 服务。
本项目现走 `/setmedia` + 内嵌 `MediaHttpServer`——视频投屏真机已验证(2026-07-21,
含轮询进度)。图片投屏未单独验证。

- [ ] 若真机图片投屏失败,改为 `PUT /image` 直传

## 工具

### 4. 真机交叉验证 CLI

- **Python(首选,真机验证过)**:`D:\n0ull\Desktop\1\Java\tvhelper\tvhelper_tool`,纯标准库:
  `python tvremote.py scan|send <ip> HOME|shell|mouse|click|stick|proj|proj-info|proj-video`。
  注意其 `tv_protocol.py` 注释里魔数十六进制是错的,十进制 287475865 才对(与 a116245 一致)。
- **Go(tvhelper2,勿直接用)**:`tvhelper2/tvhelper.exe` 的 IB 魔数写的是 `0x11223359`
  (≠ 真值 0x11228899,同一类十六进制转写错误),不修跑不通 IB 握手;仅发现功能可借鉴。

## 未实现的协议能力(已逆向,按需再做)

- `Cmd_LaunchSth`(20400):远程拉起 TV Activity/Service;launch_type 枚举序
  activity=0/service=1/activity_new=2(按 ordinal 上线)。⚠ 反编译纠正:TV 版本 <2100200600 时
  `launchRemoteActivity` 直接不发包(`IdcCmds.java:121`),**没有**"退化为 activity"分支。
  反编译实见的 action 仅两个,可作首批真机目标:
  `com.yunos.tv.intent.RemoteControlServer.ACTION_DIAGNOSTIC`(activity_new,DebugFragment.java:48)、
  `yunos.appstore.startprocessservice`(service,RpmObserver.java:17)。
  ⚠ yunos_tvhelper 的 intent.py 宣称"TV 搜索走本地 LaunchSth 不依赖云端",但其 pid=21300
  (jadx 中 21300/21400/21500 均不存在,真值 20400)、action 名(com.yunos.tv.search.ACTION_SEARCH
  等)在 APK 中零出处、launch_type=1 实为 service——该路径**不可能成功**,搜索只能走
  ASR 模块文本路径(本项目 AsrTextService 已实现)
- `Cmd_SysProp`(21100/21200):读写 TV 系统属性
- AppStore 增量:UpdateRequest(20)、ContinueDownload(21)、GetListCancel(26)、GetAppInfo(2/3)。
  ⚠ packetId **21 撞号(反编译已证实)**:`ContinueDownloadRequest` super(21)
  (IdcPacket_ContinueDownloadRequest.java:7,18)与 `ID_UPDATE_RESPONSE`=21
  (AbsIdcDataPacket.java:32)冲突,疑源码笔误——实现前先抓包确认 TV 实际用哪个
- PROTO_MULTITOUCH(272):多点触控(原 App 也无 UI 调用方)

**明确不做**:Racct 账号/支付、弹幕 MTOP、TV 搜索(均依赖已停服云端/SecurityGuard 签名)。
