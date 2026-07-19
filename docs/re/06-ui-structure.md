# 06 UI 页面结构（ui-agent）

源码：`jadx_out/sources/com/yunos/tvhelper/ui/`，布局：`apktool_out/res/layout/`。已排除支付宝/淘宝/优酷 SDK 布局。

## 1. 页面清单

### trunk 主干（`ui/trunk/`）

| Activity/页面 | 布局 | 内容与功能 |
|---|---|---|
| MainpageActivity → MainpageFragment | fragment_mainpage | 主页。仅 WeexFragment 容器 + 断网层，内容由 Weex 远程 URL 渲染 |
| WelcomeActivity | fragment_welcome | 首次启动欢迎页 + 隐私协议弹窗 |
| GuideActivity | fragment_guide, guide_page | 功能引导页（ViewPager） |
| DevpickerActivity → DevpickerFragment | fragment_devpicker + devpicker_top/bottom_* | 设备选择页：WiFi 状态/搜索动画 + 设备列表 + 扫码连接入口 |
| SettingActivity | fragment_setting | 设置：振动反馈、消息提醒、版本更新、账号安全中心、隐私政策、用户协议、注销账号、调试入口 |
| AboutActivity | fragment_about | 关于 |
| SearchActivity | fragment_search + search_* | 搜索页：热搜词、结果列表、影视/游戏/应用分类筛选 |
| PushmsgActivity | fragment_pushmsg | 消息中心 |
| QrcodeActivity | fragment_qrcode | 扫码（连接设备用） |
| AcctActivity / AcctYkActivity / AcctsActivity | fragment_acct(s) | 账号页：淘宝/优酷登录切换 |
| PrivacyManage/List/DetailActivity | fragment_privacy_* | 隐私管理 |
| LogOffActivity | fragment_logoff | 注销账号 |
| RecordCreateActivity / RecordingActivity | record_* | TTS 语音定制录制 |
| GenderSelectActivity | gender_select_layout | 性别选择（TTS 用） |
| DebugActivity / IdcdiagActivity / ConfigEnvActivity | fragment_debug 等 | 调试/IDC 诊断/环境切换 |
| RacctDlgActivity / RacctYkDlgActivity | fragment_racct_dlg | 账号弹窗式登录 |

### rc 遥控（`ui/rc/`）

| Activity/页面 | 布局 | 内容与功能 |
|---|---|---|
| RcGroupActivity → RcGroupFragment | fragment_rc_group | 遥控主面板（5 种模式） |
| ├ KeypadView | rc_keypad | 按键遥控：通用按键区 + 五向方向盘 |
| ├ TouchpadView | rc_touchpad | 触屏遥控：触摸板 + 屏幕光标 |
| ├ MotionpadView | rc_motionpad | 体感遥控（陀螺仪） |
| ├ JoystickView | rc_joystick | 游戏手柄 |
| └ SteeringwheelView | rc_steeringwheel | 方向盘（重力感应） |
| JoystickGroupActivity | fragment_joystick_group | 手柄组 |
| JoystickPickerActivity | fragment_joystickpicker | 手柄样式选择器 |
| TouchpadGuideActivity / JoystickGuideActivity | *_guide | 首次使用引导 |
| RinputActivity | fragment_rinput | 远程输入法 |
| floatingpad（浮窗） | fragment_rc_floatingpad | 悬浮遥控板 |
| AsrFragment（语音） | asr_view | 语音识别覆盖层 |

### localprojection 本地投屏（`ui/localprojection/`）

| Activity/页面 | 布局 | 内容与功能 |
|---|---|---|
| LocalMediaActivity | layout_media_content | 本地媒体首页：三个 tab：照片/视频/音乐 |
| FolderAlbumActivity | fragment_photo_folder | 相册文件夹浏览 |
| ImagePreviewActivity | activity_image_preview | 图片预览并投屏 |
| VideoPlayActivity | activity_video_preview | 视频投屏播放 + 控制条 |
| MusicPlayActivity | activity_music_preview | 音乐投屏播放 + 控制条 |

### hotmovie 影视（`ui/hotmovie/`）

| Activity/页面 | 布局 | 内容与功能 |
|---|---|---|
| MovieEnterActivity | movie_enter_view | 影视入口页（推荐流 + 分类） |
| MovieDetailActivity | fragment_moviedetail | 详情：封面/简介/选集/演职员/弹幕/预告/推荐；投屏播放按钮 |
| MovieActorActivity | activity_actor_detail | 艺人详情 |
| MyMovieActivity | fragment_my_movie | 我的影视（收藏+历史） |
| CollectActivity | fragment_collect | 我的收藏 |
| UserPlayLogActivity | fragment_history | 播放历史 |
| DanmakuActivity | activity_danmuku | 弹幕发送页 |

### screenshot 电视截屏

| ScreenShotActivity | screen_shoot_main | 截取电视画面 → 预览 → 保存/分享；长按连拍 |

### dongle 天猫魔投（`ui/dongle/`）

| Activity/页面 | 布局 | 内容与功能 |
|---|---|---|
| DongleEntryActivity | fragment_dongle_entry | 魔投设备列表、添加 |
| DonglePairActivity | fragment_dongle_pair_* | 配网：蓝牙/WiFi/热点/酒店模式 + 结果页 |
| DongleRemoteControlActivity | fragment_dongle_remote_control | 魔投专属遥控 |
| DongleSettingActivity | fragment_dongle_setting | 设置：WiFi/分辨率/重启/恢复出厂 |
| DongleResolutionSelectActivity | fragment_dongle_resolution_select | 分辨率选择 |
| DongleAboutActivity | fragment_dongle_about | 固件版本/IP/MAC/SN/UUID |

### h5/weex 容器

| PureWebViewActivity | 纯 H5 容器；WindVaneActivity | 内嵌浏览器；StdWeexActivity/WeexFragment | Weex 容器 |

### 公共 UI 组件（`ui/app/uielem/`）

- DevbarFragment：顶部当前设备栏
- TitlebarFragment：统一标题栏（深/浅色）
- NowbarFragment：悬浮底部"正在播放"条 + 遥控入口
- LayerLayout 状态层：断网/加载/断连/升级

## 2. 主页导航结构

- 主页**不是底部 tab**：MainpageActivity 单 Fragment 容器，内容 Weex 远程渲染。
- 全局导航 = Activity 栈跳转；页面共用骨架 `page_layout.xml`（CoordinatorLayout + page_content + 底部 PageNowbarContainer）。
- Nowbar：左侧正在播放信息，右侧遥控入口（→ RcGroupActivity）。
- 主要入口：主页 → 设备选择/搜索/消息/设置/我的影视/本地投屏/电视截屏/魔投。

## 3. 遥控面板布局结构（fragment_rc_group）

深色底 #252529，自上而下：设备栏 → 标题栏（模式切换）→ 遥控区（5 模式之一）→ 语音覆盖层。

通用按键区 rc_general_keys（三段式）：
- 上段：左**电源**、右**魔键**，中间五向方向盘（上下左右+OK）
- 中段：左**音量−**、中**语音占位**、右**音量+**
- 下段：左**主页**、中**返回**（大键）、右**菜单**

语音覆盖层：三级声波圆环动画 + "正在收听…" + 提示 + 底部圆形语音按钮；电源键带待机二次确认。

## 4. 关键文案与配色

- 应用名"阿里TV助手"，slogan"用 手 机 玩 电 视"
- 配色：通用背景 #f0f3f5，遥控页深色 #252529/#28282c，选中渐变 #37e8ff→#f586ff，点缀橙 #ff9500/绿 #24d870/红 #c92e30

## 5. 不确定之处

1. **主页内部结构不可考**：内容完全由远端 Weex JS 渲染，复刻时主页信息架构需自行设计。
2. 布局↔Activity 对应主要依据命名推断，个别二级布局拼装顺序可能有出入。
3. 体感/手柄/方向盘模式的按键排布未逐个验证。
4. RcGroupActivity 与 JoystickGroupActivity 的分组关系未完全确认。
