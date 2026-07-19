# 04 本地投屏 / 截图（cast-agent）

## 1. 投屏协议判定：阿里 YunOS 私有协议，非 DLNA/UPnP

依据：
- 投屏代码路径中没有任何 `AVTransport`/`SetAVTransportURI`/SOAP/cling/CyberLink 引用（全库 grep 命中均与投屏无关）。
- 协议由两个自建部分组成：
  1. **控制通道**：手机作 TCP 客户端，直连电视媒体控制端口，跑 HTTP/1.1 风格私有文本协议（YunOS 自定义 header，如 `yunos-session-id`）。
  2. **媒体通道**：手机内嵌 NanoHTTPD 改造的 HTTP 文件服务器，电视凭 URL 回拉手机上的媒体文件。

## 2. 整体架构与连接建立

- 设备发现/连接走 IDC 通道（`com.tmalltv.tv.lib.ali_tvidclib`，与遥控器同链路）。IDC 建立后触发投屏初始化：
  - `localprojection/biz/LprojBizBu.java:17-33` `onEstablished()` → `ProjectionMgr.createInst()` + `initMediaProjection()`
  - `LprojBizBu.java:73-87`：控制端口默认 **13520**；若 ddh 参数含 key `mediaprojection` 的 JSON，取 `projectionport` 覆盖；目标地址 `idcDevInfo.mDevAddr`（电视 IP）。
- 手机 HTTP 媒体服务器：
  - `ui/localprojection/UiLocalProjectionBu.java:60-69` 进入本地媒体功能时 `initMediaServer()`，把 `手机IP:端口` 注入 PhotoHelper/MusicDataMgr/VideoDataMgr；WiFi 变化时重启（`:19-27`）。
  - `mediaserver/MediaServer.java:20-34`：**从 8192 起逐端口尝试绑定直到成功**；`:46-66` IP 取 `wlan0` 的 IPv4。

## 3. 媒体推送流程（选片 → 播放）

1. 选媒体 → 注册内容节点并拼 URL：
   - 视频 `video/VideoDataMgr.java:99-111`：`video-item-<id>` → `http://<手机IP:port>/video-item-<id>`，`ContentTree.addNode(id, ContentNode(id, item, 本地路径))`
   - 音乐 `music/MusicDataMgr.java:168-183`（`audio-item-<id>`，专辑封面也挂同一服务）
   - 图片 `activity/ImagePreviewFragment.java:383-406`（`image-item-<id>`）
   - 前缀常量 `mediaserver/ContentTree.java:7-13`（video=1/audio=2/image=3）
2. 播放：`mediacontrol/TVProjectionPlayer.java:395-416` → `projection().setMedia(type,url,title,thumbnail)`；类型映射 "1"→video、"2"→audio、"3"→image（`:290-298`）。
3. `MediaProjectionClientImpl.setMedia()`（`:243-252`）：首次新起线程 TCP 连电视（`:402`），10 秒超时（`:591-601`）；已连接则复用长连接。
4. 发 **POST /setmedia**，电视凭 `content_url` 回拉手机 HTTP 服务播放。
5. 电视经同一连接回发 `POST /event?state=...` 上报状态，手机回 200 OK（`MediaClientMessageProcessor.java:138-145,174-176`）。
6. 图片预览会 `POST /preload` 预载下一张（`ImagePreviewFragment.java:408-417`）。

## 4. 私有控制协议：消息字段级格式

裸 TCP 长连接，HTTP/1.1 风格文本。手机=客户端，电视=服务端。

请求（`core/RequestMessage.java:25-29`、`core/MediaMessage.java:13-38`）：
```
<METHOD> <URI> HTTP/1.1\r\n
<Header>: <value>\r\n ...
Content-Length: N\r\n\r\n
<body>
```
响应（`core/ResponseMessage.java:47-50`）：`HTTP/1.1 200 OK \r\n...`

**投屏指令**（`core/MediaMessageFactory.java:117-136`）：
```
POST /setmedia HTTP/1.1
yunos-mediatype: video|audio|image
yunos-session-id: <UUID，进程启动生成，MediaProjectionClientImpl.java:37>
Content-Length: N

{"content_url":"http://<手机IP:port>/video-item-12",
 "content_name":"标题",
 "thumbnail_url":"http://.../封面",   // 仅音乐
 "exclusive":true,
 "start_position":0}
```

**播放控制**（均带 `yunos-session-id`）：

| 指令 | 报文 |
|---|---|
| 播放 | `POST /play` |
| 暂停 | `POST /pause` |
| 停止/退出 | `POST /stop` |
| 跳转 | `POST /seek?value=<N>`（N 高置信为毫秒） |
| 音量 | `POST /volume?value=<N>` |
| 倍速 | `POST /rate?value=<N>` |
| 查询进度 | `GET /playback-info` → JSON `{"name","duration","position","volume","rate"}`（字符串值） |
| 服务信息 | `GET /server-info` → JSON `{features,protocol_vers,server_vers,server_code,display_name}` |
| 预载 | `POST /preload`，body `{"content_url":...}` |
| 图片缩放 | `POST /zoom?scale=<f>&cx=<f>&cy=<f>` |

**电视→手机状态事件**：
```
POST /event?state=<prepared|playing|paused|loading|stopped|completed|error|occupied>[&duration=<N>&position=<N>]
```
手机回 `HTTP/1.1 200 OK`，映射到 `IStateListener` 回调；`occupied`=电视被其他投屏占用。

工厂另有 `PUT /image`（直推 JPEG：`yunos-assetaction: cacheOnly|displayCached`、`yunos-assetkey`、body 为文件流）和 `POST /reverse`（101 Switching Protocols 反向通道）。**这两方法本 APP 内无调用方**，应是共享库供电视端用；本 APP 图片走 URL 拉流。**（不确定项）**

## 5. 手机本地 HTTP 服务细节

- `mediaserver/HttpServer.java`：NanoHTTPD 单文件改造，ServerSocket 每连接一线程（`:56-67`）。
- 端口 8192 起扫描绑定；URL 规则：去前导 `/` 查 `ContentTree` HashMap → 换本地真实路径 → `serveFile()`（`:90-99`）。
- 支持 **Range / 206 Partial Content**（`:650-696`）+ `Accept-Ranges: bytes`，电视可拖进度；MIME 按扩展名（`:707-710`）。
- 拒绝 `..` 穿越（`:580-582`）；未注册路径 404。
- 退出功能/WiFi 断开时 `stopHttpServer()`。

## 6. 截图功能（电视画面回传）

走 **IDC 命令通道**（二进制私有 packet，与遥控器同链路），非投屏 HTTP-like 通道：

1. `ui/screenshot/fragment/ScreenShotFragment.java:332-341`：发 `IdcPacket_Cmd_ScreenShot_Req`（packet id **20900**），参数 JSON `{"resize_ratio":0,"resize_w":1280,"resize_h":720,"compress_quality":90}`。
2. 电视截屏→缩放 1280x720→JPEG q90，回 `IdcPacket_Cmd_ScreenShot_Resp`（id **21000**），字节在 `mImgData`。
3. 保存 `DCIM/TV_SCREEN*/<时间戳>.jpg`（`ui/screenshot/util/PhotoSaveUtil.java:17,35`），触发媒体扫描。
4. 单击 1 张；长按连拍 300ms/帧，上限 `SCREENSHOT_MAX_NUM`；电视版本 ≥ `SCREENSHOT_RCS_VER` 才可用。

## 7. 录屏：不存在

- 无 MediaProjectionManager/VirtualDisplay 业务调用；`record_*` 资源全属 TTS 语音录制；VideoPlayActivity 是本地播放页。

## 8. 关键文件索引

| 功能 | 文件：行 |
|---|---|
| 协议常量 | `localprojection/biz/core/MediaConstants.java:9-30` |
| 全部指令构造 | `localprojection/biz/core/MediaMessageFactory.java:36-211` |
| TCP 客户端 | `localprojection/biz/client/MediaProjectionClientImpl.java:402,243-341` |
| 事件/响应解析 | `localprojection/biz/client/MediaClientMessageProcessor.java:101-182` |
| 端口 13520+ddh | `localprojection/biz/LprojBizBu.java:16,73-87` |
| 手机 HTTP 服务 | `ui/localprojection/mediaserver/HttpServer.java`、`MediaServer.java:20-34` |
| URL→文件映射 | `ui/localprojection/mediaserver/ContentTree.java:7-13` |
| 视频 URL | `ui/localprojection/video/VideoDataMgr.java:99-111` |
| 音乐 URL+封面 | `ui/localprojection/music/MusicDataMgr.java:168-183`、`mediacontrol/MusicPlayerCenter.java:107` |
| 图片投屏+预载 | `ui/localprojection/activity/ImagePreviewFragment.java:383-417` |
| 播控封装 | `ui/localprojection/mediacontrol/TVProjectionPlayer.java:282-369` |
| 截图请求 | `ui/screenshot/fragment/ScreenShotFragment.java:332-341` |
| 截图 packet | `ali_tvidclib/packet/IdcPacket_Cmd_ScreenShot_Req/Resp.java`（id 20900/21000） |
| 截图保存 | `ui/screenshot/util/PhotoSaveUtil.java:17,35` |

## 不确定项

1. seek/duration/position 单位：高置信毫秒（变量名 `mMsce` + MediaPlayer 体系），无协议文档不能 100% 确认。
2. `PUT /image`、`POST /reverse` 本 APK 无调用点，可能是共享库死代码或电视端配套。
3. 投屏控制连接（13520）与 IDC 是两条独立 TCP；`mediaprojection.projectionport` 命名表明电视端投屏服务独立监听。
