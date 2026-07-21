# 05 云端 HTTP 接口（cloud-agent）

## 0. 总览：三条通道

| 通道 | 用途 | 传输 |
|---|---|---|
| **mtop 网关**（云端） | 几乎全部云端业务 API | mtopsdk（HTTP/HTTPS，网关域名见下） |
| **IDC 私有 TCP 协议**（局域网） | 手机↔电视互联：遥控、应用管理、投屏、账号同步 | TCP 13510/13511 + JSON 包 |
| **ACCS/Agoo 长连接**（云端推送） | 推送通道，消息体再用 mtop 拉取 | 淘宝 ACCS（SDK 内部） |

自有 OkHttp 封装（`utils/http/OkHttp.java`）全 App 只有一处实际调用：下载升级 APK 文件（`ui/trunk/update/ui/UpdateUiLogic.java:119`，URL 来自 mtop 升级接口响应）。

## 1. 环境 / 域名清单

证据：`support/api/MtopPublic.java:13-18`

| 网关 | 正式 | 预发 | 日常（测试） |
|---|---|---|---|
| 淘宝系（TAO） | `acs.m.taobao.com` | `acs.wapa.taobao.com` | `acs.waptest.taobao.com` |
| 优酷系（YOUKU） | `acs.youku.com` | `pre-acs.youku.com` | `daily-acs.youku.com` |

- 路由规则（`support/biz/mtop/Mtoper.java:50-57`）：API 名以 `mtop.youku.` / `mtop.vip.youku.` 开头 → 优酷域，且一律 **POST**；其余 → 淘宝域（GET 默认）。
- 环境切换由 `LegoApp.env()` 决定（DAILY/PREPARE/ONLINE）。
- H5/Weex 资源（`utils/cfg/ServerUrl.java:5-10`）：主页 bundle 正式 `https://g.alicdn.com/yuntv/tvhelper_weex/page/home-android-online.js`、beta `.../home-android-daily.js`；家庭监控 `family_monitor.js`；视频通话 `video_chat.js`；反馈 `http://h5.m.taobao.com/yuntv/feedback.html`。
- 其他常量：优酷扫码登录跳转 `http://qr.youku.com/pr`；会员购买页 `https://h5.vip.youku.com/buy`；优酷客户端下载 `https://down2.youku.com/youku/down.php`。

## 2. mtop 接口清单（自有业务）

所有请求对象继承 `MtopPublic.MtopBaseReq`。`NEED_SESSION`/`NEED_ECODE` 均为 false。

### push（消息中心，配合 ACCS 推送）
| API | 版本 | 关键参数 | 用途 |
|---|---|---|---|
| `mtop.wmc.use.queryMessageService.queryMessageListByMessageTypeId` | 1.1 | `userNick`(=taid), `messageTypeId`, `pageSize`, `unReadMessageCount` | 拉取推送消息列表 |
| `mtop.wmc.messageservice.readmessagebyid` | 1.1 | `messageIds`, `messageTypeId`, `target` | 标记已读 |
| `mtop.wmc.messageservice.deleteMessage` | 1.1 | `ids`, `messageTypeId`, `target` | 删除消息 |

### acctyk（优酷账号）
| API | 参数 | 用途 |
|---|---|---|
| `mtop.alitv.account.youku.psp.token.login` v1.0 | `stoken` | 换 `accessToken/refreshToken/ytid/ytname/ytpic` |
| `mtop.youku.vip.xtop.member.profile.get` v1.0 | — | 优酷会员资料 |

### immersive（"伴看"/沉浸式观影）
| API | 参数 | 用途 |
|---|---|---|
| `mtop.yunos.tvpublic.danmu.profile` v1.0 | `iid` | 弹幕配置 |
| `mtop.yunos.alitvvideo.ta.video.getProgramAdvert` v1.0 | `id` | 节目广告 |

### ui/trunk、ui/hotmovie、ui/app（升级/搜索/影视/设备/TTS）
| API | 版本 | 关键参数 | 用途 |
|---|---|---|---|
| `mtop.alitv.appupgrade.resource.update` | 1.0 | `uuid`(=taid), `appVersion`, `channelId`, `modelName`, `os` 等 | App 自升级检查；响应含 apk `url` |
| `mtop.yunos.alitvvideo.ta.taiduuid.record` | 1.0 | — | 上报手机 taid 与电视 uuid 绑定 |
| `mtop.yunos.alitvvideo.ta.ability.search` | 1.0 | — | 设备能力/在线功能配置 |
| `mtop.yunos.alitvvideo.ta.app.getAppRecommend` | 2.0 | — | 本地应用推荐 |
| `mtop.yunos.alitvvideo.ta.video.getHotWord` | 1.0 | — | 搜索热词 |
| `mtop.youku.tvpioneer.tvassist.show.search` | 1.0 | `keyword` | 影片搜索 |
| `mtop.youku.tvpioneer.tvassist.show.detail.get` | 1.0 | `id` | 视频详情 |
| `mtop.youku.tvpioneer.tvassist.show.recommendBycategory.list` | 1.0 | `programParas` | 分类推荐 |
| `mtop.yunos.alitvvideo.ta.categorylist` | 1.1 | `channelType`, `page`, `pageSize` | 频道/分类列表 |
| `mtop.yunos.alitvvideo.ta.video.getArtistPersonInfo` | 1.0 | `person_id` | 影人信息 |
| `mtop.yunos.tvassist.newmedia.video.getpersoninfo` | 1.0 | `person_id` | 影人信息（新媒体） |
| `mtop.yunos.alitvvideo.ta.getDeviceInfo` | 1.0 | `device` | 电视设备信息 |
| `mtop.yunos.alitvvideo.ta.video.getPlayConfig` | 1.0 | — | 播放配置 |
| `com.taobao.taotv.alitv.service.AlitvService.getAlitvPlayUrlV2` | 1.0 | `videoId`, `token` | 获取播放地址（http-api 风格） |
| `mtop.yunos.tvpublic.danmu.send` | 1.0 | `iid`, `uid`, `content`, `playat`, `propertis` | 发弹幕 |
| `mtop.yunos.tvpublic.douban.comments` | 1.0 | — | 豆瓣评论 |
| `mtop.yunos.tvpublic.user.youku.playlog.*` (pushuserplaylog/listuserplaylog/clearUserPlaylog/showuserplaylog) | 1.0 | 继承 `TvhUserMtopReq` | 播放记录 上报/列表/清空/查询 |
| `mtop.yunos.tvpublic.user.youku.favorite.*` (pushuserfavorite/listuserfavorite/cancelUserFavorite/isFavorite) | 1.0 | 同上 | 收藏 增/查/删/判断 |
| `mtop.vip.youku.xbproxy.benefit.recommend` | 1.0 | `bizCode`, `channel`, 设备信息 | 会员权益推荐 |
| `mtop.youku.tvspeech.tts.train` | 1.0 | `audioInfos`, `caseId`, `voiceName`, `vendorId="haier"` 等 | TTS 语音包训练提交 |
| `mtop.youku.tvspeech.tts.audioCheck` | 1.0 | `url`, `text`, `localeInfo` | 录音质检 |
| `mtop.youku.tvspeech.tts.query` / `.delete` / `.getTrainProcess` / `.getrecordingstatements` | 1.0 | `id`, `phone`, `uuid`, `utdid` | 语音任务查询/删除/进度/语句列表 |

> TTS 录音文件走 AUS 上传（OSS），上传后把 URL 传给 mtop 接口。
> Weex/H5 页内 API 透传：`ui/weex/module/TvhMtopProxy.java:79-82`、`ui/h5/util/H5MtopUtil.java:99`。

## 3. 局域网 IDC 协议上的业务模块

idc 模块 = **设备互联框架**（非测速/诊断）。发现/连接细节见 01 报告。

### rpm（Remote Package Management，远程应用管理）
**手机遥控电视端 AppStore**（`rpm/biz/main/RPM.java`）。消息类位于 Java 包 `com.yunos.tv.appstore.idc.datapacket`——**注意这只是包名**：IDC 模块线上名为 **`com.yunos.idc.appstore`**（`com/yunos/tv/appstore/idc/IdcConstant.java:6`、`rpm/biz/observer/RpmObserver.java:16`；字符串 `com.yunos.tv.appstore` 在 APK 内零字面量）。全部命令经 VConn 发 JSON 包：

| 包（ID） | 方法 | 参数 | 用途 |
|---|---|---|---|
| IdcPacket_GetSystemInfo (40) | `doGetTvSystemInfo` | 空 JSON 体 | 取电视系统信息（**兼作 RPM 就绪门**） |
| IdcPacket_GetListRequest (4) | `doGetAppList` | `pageSize`（默认 10） | 电视应用列表 |
| IdcPacket_GetAppInfoRequest (2) | `doGetAppInfo` | `packageName`、`iconType`（`JPEG`/`PNG`） | 应用详情 |
| IdcPacket_InstallRequest (7) | `doInstallByUrl` / `doInstallByPackageName` | byUrl：`packageName+apkUrl+appName+iconUrl+apkSize`(String)；byPackage：`packageName+versionNeeded`(int) | **推装 APK**：只把 URL 下发给电视，电视端自行下载安装——文件不经过手机 |
| IdcPacket_UpdateRequest (20) / IdcPacket_UninstallRequest (11) | `doUpdate` / `doDeletePackage` | `packageName` | 更新/卸载 |
| IdcPacket_InstallCancelRequest (10) / ContinueDownloadRequest | — | `packageName` / 空体 | 取消/续传 |
| IdcPacket_OpenAppRequest (14) | `doOpenByUri/PackageName` | `uri` 或 `packageName` | 远程打开应用 |

⚠ **ContinueDownload 线上值为 21**（`IdcPacket_ContinueDownloadRequest.java:7,17` `super(21)`），与 `UpdateResponse`(21) 撞号，靠收发方向消歧（手机→TV=续传，TV→手机=更新响应）；常量 `ID_CONTINUE_DOWNLOAD=24`（`AbsIdcDataPacket.java:15`）是**死常量**，全仓零引用。

**开启模块流程**（`RpmObserver.java:24-27,75-80`）：

1. **唤醒**：`launchRemoteService("yunos.appstore.startprocessservice")`——即 `Cmd_LaunchSth`(20400)，体为单 LPString JSON `{"launch_type":1,"action":...,"extra_str":...}`（launch_type 取枚举 ordinal：activity=0 / service=1 / activity_new=2，`IdcPacket_Cmd_LaunchSth.java:17-21,35-37`）
2. `tryOpenModule("com.yunos.idc.appstore", listener)`——等电视广播 `ModuleAvailability`(20000)，body 字段 `m_name/m_ver/m_extprop/m_id/isOnline`；`m_name` 可为 `{"name","category"}` JSON，原 App 双分支解析（`IDC.java:360-368`）
3. 模块上线后 `IdcRemoteModule` 构造即自动发 `VConnSyn{"mid":m_id}`（`IdcRemoteModule.java:32,105-110`）
4. `doGetTvSystemInfo` 作就绪门：`isAvailable() = mSystemInfo != null`（`RpmObserver.java:115-117`）

**帧格式与分发**：VConn 包 = **int32 packetId + int32 requestId + JSON body**（8B 大端头，`AbsIdcDataPacket.java:13,138-146`；requestId 由 `AtomicInteger` 自动分配，响应回调按 requestId 关联）。`RpmVConn.onRecvPacket`（`RpmVConn.java:103-221`）对首 int32 硬编码 switch，仅处理 3/5/8/9/12/15/21/41；`GetListResponse` 回调跨页保持至 `isFinished || isInterrupt`（:129-132）。外层再经 `IdcPacket_VConnData` 封装：长度前缀 `{"mid":N}` + 原始 payload（`IdcRemoteModule.java:120-134`）。

**线上细节**（复刻需注意）：

- `InstallResponse.result == 2` = 下载已开始（`WindVaneFragment.java:638-644`）；安装进度经 `IdcPacket_AppStatus`(9) 异步推送：`{"appInfo":{...},"appStatus":N,"errorCode":N}`，状态枚举 INSTALLING=2 / DOWNLOAD_PROGRESS=10 / INSTALLED=18 / INSTALL_FAILED=20 / UNINSTALLED=22 等（`IdcPacket_AppStatus.java:11-23`；18→onPackageAdded，22→onPackageRemoved）
- `GetListResponse` 的 `apps` 字段在**单应用场景为 JSON 对象而非数组**（`accumulate` 行为，:45；解码双分支兼容，:62-65）
- 嵌套 `AppInfo` 对象用 `size`/`status` 键（≠ 顶层 `apkSize`/`appStatus`），另有 `versionName/versionCode/time/canRemove/canShow/iconType`（`pojo/AppInfo.java:15-28`）
- `SystemInfo`(41)：`uuid/productId/productVer/productVerName/OSInfo/model/sysVer/frameVer`；**`lang` 序列化在 `"packageName"` 键下**（`pojo/SystemInfo.java:38,51`，原 App bug，解析需容忍）

**鉴权与加密**：报文中**零鉴权材料**——`InstallRequest` 仅上述业务字段（`IdcPacket_InstallRequest.java:24-35`）；IDC 层登录身份为自报字符串（`IdcUtils.java:20-23`，`name="com.yunos.tvhelper"` 可伪造）。加密为**会话级整帧**（登录协商 `encryption_algorithm_ver`；`IdcComm.java:98-100`：ver=0 即走明文继续；原 App 请求 ver=1 但电视可答 0），从不按命令加密。反编译中无任何 TV 端校验调用方签名/白名单的证据。

**调用点现状（v5.2.2）**：原 App **无原生应用管理 UI**，唯一调用方为 H5 桥 `common.installTvApk` → `doInstallByUrl`（`WindVaneFragment.java:455,607-644`）。`doDeletePackage`/`doOpenByPackageName`/`doGetAppList` 零 UI 调用点（API 面见 `rpm/api/RpmPublic.java:30-44`）——这三者的电视端实现仅有响应包定义佐证，属推断，**需真机验证**。

### remoteaccount（远程账号同步）
把手机登录态同步到电视：
- `RacctPacket_login_info`：`{nickname, asoToken, yktk, ykStoken}` —— 下发淘宝 asoToken + 优酷 yktk/stoken。
- `RacctPacket_login_enable/disable`、`RacctPacket_login_update`、`RacctPacket_pay {payURL}`。
- 注：`TbAsoToken.apply()` 为空实现，asoToken 获取路径**不确定**。

### immersive（伴看）
VConn 包 `category="immersive"`，messageType：`setMediaReq/setMediaResp`、`playerActionReq`（`mActionType`, `mPosition`）、`videoInfo`、`danmakuMsg`、`danmakuToggle`、`appState`。

## 4. 鉴权与签名机制

- **mtop 签名**：mtopsdk + SecurityGuard（x-sign/wua）。appKey 取自安全组件；`appId = appKey + "@android"`。设备 ID 注册为 taid。
- **设备身份（taid）**：`taid = UPPER_MD5(utdid)`，utdid 来自阿里 UTDevice（`support/biz/taid/TaidMgr.java:52-60`）。
- **会话**：淘宝登录后 mtop 自动携带 session/cookie；优酷登录为扫码 → SToken → `psp.token.login` 换 accessToken。优酷用户态接口固定字段 `system_info="TVASSIST"`、`access_token`。
- **公共 systemInfo**：所有 `TvhMtopReq` 子类附带 `systemInfo` 字段：client 信息、taid、utdid、连接电视后的 uuid/device_model/rcs_version。
- **LAN 鉴权**：同网段信任 + 加密 seed 协商；扫码连接额外校验 `loginMagicNumber`。

## 5. 推送链路

ACCSClient 注册服务：`accs`、`accs-console`、`tvassist` → `CallbackService`；`bindApp(ttid)` + `bindUser(taid)`。收到推送通知后，消息正文经 `mtop.wmc.*` 拉取，本地 Notification 展示。

## 6. 关键证据文件

- mtop 路由/域名：`support/biz/mtop/Mtoper.java:50-57,296-307`、`support/api/MtopPublic.java:13-18`
- 设备身份：`support/biz/taid/TaidMgr.java:52-60`
- 优酷 token：`acctyk/biz/yktoken/YkToken.java:82-115`
- RPM：`rpm/biz/main/RPM.java:68-201`、`rpm/biz/observer/RpmObserver.java:16-17,24-27,75-80`、`rpm/biz/main/RpmVConn.java:103-221`、`com/yunos/tv/appstore/idc/IdcConstant.java:6`、`com/yunos/tv/appstore/idc/datapacket/AbsIdcDataPacket.java:13,138-146`、`IdcPacket_InstallRequest.java:24-35`、`IdcPacket_ContinueDownloadRequest.java:7,17`、`IdcPacket_Cmd_LaunchSth.java:17-21,35-37`、`ui/h5/fragment/WindVaneFragment.java:455,607-644`
- 推送：`push/biz/main/AccsBiz.java:105-128`、`push/biz/main/PushMgr.java:252,265,318`

## 7. 不确定之处

1. mtop 响应 JSON 结构以 fastjson 映射到 `*Resp` 类，未逐一展开。
2. ACCS 推送原始 payload 格式未细读，确认"通知→mtop 拉详情"两段式。
3. asoToken 的实际获取路径（TbAsoToken 为空壳）。
4. `preid` 常量用途不明，登录/未登录分支同值。
5. RPM：电视固件是否校验 `login.name`（自报字符串，可伪造为 `com.yunos.tvhelper`）、`com.yunos.idc.appstore` 模块是否常驻（即 Cmd_LaunchSth 唤醒是否必需）——反编译均无证据，待真机证伪。
