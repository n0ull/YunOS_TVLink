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
**手机遥控电视端 AppStore**（`rpm/biz/main/RPM.java`）。全部经 VConn 发 `com.yunos.tv.appstore.idc.datapacket` JSON 包：

| 包（ID） | 方法 | 参数 | 用途 |
|---|---|---|---|
| IdcPacket_GetSystemInfo | `doGetTvSystemInfo` | — | 取电视系统信息 |
| IdcPacket_GetListRequest | `doGetAppList` | `pageSize` | 电视应用列表 |
| IdcPacket_GetAppInfoRequest | `doGetAppInfo` | `packageName` | 应用详情 |
| IdcPacket_InstallRequest (7) | `doInstallByPackageName` / `doInstallByUrl` | `packageName+apkUrl+appName+iconUrl+apkSize` | **推装 APK**：只把 URL 下发给电视，电视端自行下载安装——文件不经过手机 |
| IdcPacket_UpdateRequest / IdcPacket_UninstallRequest | `doUpdate` / `doDeletePackage` | `packageName` | 更新/卸载 |
| IdcPacket_InstallCancelRequest / ContinueDownloadRequest | — | `packageName` | 取消/续传 |
| IdcPacket_OpenAppRequest | `doOpenByUri/PackageName` | `uri` 或 `packageName` | 远程打开应用 |

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
- RPM：`rpm/biz/main/RPM.java:68-201`、`com/yunos/tv/appstore/idc/datapacket/IdcPacket_InstallRequest.java:11-17`
- 推送：`push/biz/main/AccsBiz.java:105-128`、`push/biz/main/PushMgr.java:252,265,318`

## 7. 不确定之处

1. mtop 响应 JSON 结构以 fastjson 映射到 `*Resp` 类，未逐一展开。
2. ACCS 推送原始 payload 格式未细读，确认"通知→mtop 拉详情"两段式。
3. asoToken 的实际获取路径（TbAsoToken 为空壳）。
4. `preid` 常量用途不明，登录/未登录分支同值。
