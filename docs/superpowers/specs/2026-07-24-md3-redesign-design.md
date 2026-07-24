# TVLink Material Design 3 全面重设计

- 日期：2026-07-24
- 状态：已批准（用户经 brainstorming 四轮决策确认）
- 范围：`shared/src/jvmCommonMain/kotlin/app/tvlink/ui/` 全部 UI；协议层、服务层零改动

## 决策记录（用户逐项确认）

| 决策点 | 结论 |
|--------|------|
| 改造范围 | 全面重新设计（含信息架构） |
| 导航结构 | 遥控为主 + "更多"收纳：连接后 3 tab（遥控 / 投屏 / 更多），截屏·应用·设置进"更多" |
| 视觉基调 | 青色品牌（seed `#0E7490`）+ 跟随系统深浅色；遥控 tab 恒深 |
| 执行策略 | 主题先行，逐屏重构，每步可编译可跑 |
| 默认项 | 图标 `material-icons-extended` 替换全部 emoji；不做动态取色；系统默认字体；圆角走 M3 形状 scale |

## 背景与现状问题

当前 UI 已用 Compose Material3 组件库，但不符合 M3 设计规范：

1. **主题残缺**：`Theme.kt` 每套 scheme 只覆盖约 6 个颜色角色，M3 默认紫色（tertiary 等）泄漏；无自定义字阶、形状系统。
2. **颜色绕过主题**：各屏硬编码 `TvColors.X` 与十六进制字面量（如 `Color(0xFF34343A)`），不走 `MaterialTheme.colorScheme`，深色模式无法适配。
3. **非 M3 范式**：emoji 当图标；各屏手搓"‹ 返回"行而非 `TopAppBar`；圆角每屏手写 `RoundedCornerShape(10/12/14)` 不一致。
4. **导航层级低效**：功能间切换必须先回 Home 宫格页。

平台约束（经 Context7 核实）：Compose Multiplatform 1.8.0 的 material3 对应 Jetpack material3 1.3.x，为稳定版 M3，**无 M3 Expressive**；动态取色仅 Android 可用（本项目不做）。

## 设计

### 1. 主题系统（`ui/theme/`）

- **Color.kt**：以 `#0E7490` 为 primary seed，按 Material Theme Builder 模式补齐全套 M3 颜色角色（primary/secondary/tertiary/error 四族及各自 container/on 色、surface 族、surfaceVariant、outline、outlineVariant、inverse 族、scrim、surfaceTint），light/dark 双套，命名采用 `md_theme_light_*` / `md_theme_dark_*` 惯例。
- **语义收敛**：成功/警告/错误统一走 `colorScheme` 角色（error 族 / tertiary 族），不再使用裸 `TvColors.Red/Green`。
- **TvColors 退役**：仅保留青→紫渐变 brush（`AccentStart #37E8FF`→`AccentEnd #F586FF`）作品牌点缀（遥控旋钮、摇杆、搜索脉冲），及遥控屏深色面板色，迁入 theme 包。
- **Type.kt**：使用 M3 默认字阶（display/headline/title/body/label 五组），不引字体文件。
- **Shape.kt**：M3 形状 scale（extraSmall 4 / small 8 / medium 16 / large 24）；卡片统一 `MaterialTheme.shapes.medium`，删除各屏手写圆角。
- **TvTheme**：`dark` 参数逻辑保留并扩展——遥控 tab 激活时全局深色，其余跟随系统（`isSystemInDarkTheme()`）。

### 2. 导航壳与信息架构

- **`MainShell`**（新 composable，连接后进入）：3 个平级 tab——遥控 | 投屏 | 更多。
  - 窄屏：底部 `NavigationBar`；窗口宽度 ≥600dp：左侧 `NavigationRail`。用 `BoxWithConstraints` 手搓切换（约 20 行）。
  - 不引入 Navigation 库、不引入 material3-adaptive 库。
- **更多** tab：`ListItem` 菜单页 → 截屏 / 应用管理 / 设置，栈式进入，返回键回 More 列表。
- **`Screen` 模型调整**：`DevicePicker / Main(tab, moreSub)`。
  - `navBack` 规则：`moreSub != null` → 回 More 列表；`tab != 遥控` → 回遥控 tab；遥控 tab（Main 根）→ `BackHandler(enabled = false)` 不拦截，交系统默认（退出/最小化），避免现状在 Home 页吞掉返回键的行为。
  - 断开连接 → DevicePicker（现状不变）。
- **遥控 tab 恒深**：激活时 `TvTheme(dark = true)` 整壳深色（含导航栏），切走恢复跟随系统——延续现状行为，遥控器品类惯例。

### 3. 逐屏重设计

| 屏 | 设计要点 |
|----|----------|
| **DevicePicker** | 品牌区（渐变圆 logo + 标题）+ M3 `TopAppBar`；设备卡片改 `ListItem` 式（渐变圆 TV 图标 + 名称 + 副信息行 + chevron）；主操作 `Button`；手动 IP 为可折叠 `OutlinedTextField` 区；搜索中显示 `CircularProgressIndicator`，保留渐变脉冲圆作为品牌时刻（去掉"搜"文字） |
| **Remote**（恒深） | 5 模式切换改 M3 `SegmentedButton`（单选行）；按键改 `Surface` + tonal elevation 圆形钮，OK 键用 primary 色；摇杆/方向盘保留渐变旋钮与手势逻辑；底部语音条改 `BottomAppBar` 样式。**手势、SendThrottle、发送逻辑零改动，纯视觉层重构** |
| **Cast** | `TopAppBar`；媒体类型选择改三枚 `ElevatedCard`（图标 + 文字）；播放控制卡：状态 `AssistChip`、进度条改可拖动 `Slider`（接 `cast?.seek()`，替代只读 LinearProgressIndicator）、播放/暂停主 `Button` + 退出 `OutlinedButton`、音量 `Slider` 带图标 |
| **More** | 分组 `ListItem`（图标 + 标题 + 副标题 + chevron）：电视截屏 / 应用管理 / 设置 |
| **Screenshot** | `TopAppBar` 返回；预览卡（空态文案）+ `FilledTonalButton` 截屏/保存 |
| **Apps** | `TopAppBar` + 刷新 action；列表改 `ListItem` + `IconButton`（打开/卸载）；卸载走 `error` 色；空态保留模块未就绪说明文案 |
| **Settings** | 分组 `ListItem`：当前连接组（设备/IP/IB 版本/IB sid/媒体服务/IB 通道状态）+ 关于组 |

### 4. 图标映射（emoji → Material Icons extended）

Tab 图标：遥控→`Icons.Filled.SportsEsports`、投屏→`Icons.Filled.Cast`、更多→`Icons.Filled.MoreHoriz`。功能/操作图标：截屏→`Icons.Filled.PhotoCamera`、应用管理→`Icons.Filled.Apps`、设置→`Icons.Filled.Settings`、魔投配网→`Icons.Filled.Bluetooth`、返回→`Icons.AutoMirrored.Filled.ArrowBack`、刷新→`Icons.Filled.Refresh`、卸载→`Icons.Filled.Delete`、播放/暂停/停止→`PlayArrow/Pause/Stop`（core 集自带）。

### 5. 依赖变更

- 新增：`compose.materialIcons`（extended 集）。
- 不引入：Navigation 库、material3-adaptive、动态取色、字体文件。
- 共享组件不预抽：TopAppBar/ListItem 直接用 M3 原生；出现真实重复再抽（ponytail 原则）。

### 6. 数据流与状态管理

服务层（device/）与协议层（proto/）零改动。`AppViewModel`（属 UI 层）调整导航状态（`Screen` 模型 + tab/moreSub 状态），并新增投屏 seek 透传方法（`CastController.seek` 已存在，仅透传）。UI 继续单向观察 ViewModel 快照状态。

### 7. 错误处理

沿用现状：`notice` snackbar 提示（连接失败、投屏失败、安装进度等）；电源键确认 `AlertDialog`、卸载确认 `AlertDialog`、IME 输入 `AlertDialog` 保留并 M3 化（M3 AlertDialog 默认即合规）。无新错误场景。

### 8. 测试与验证

- 实现顺序：Theme → MainShell 导航壳 → DevicePicker → Cast → More/截屏/应用/设置 → Remote（最复杂最后）。
- 每步验证：`./gradlew :desktopApp:run` 人工核对 + `./gradlew check` 门禁（单测 + ktlint + detekt + Android lint）。
- 协议单测（desktopTest）不受影响；不新增 UI 测试（YAGNI）。
- 完成标准：8 屏全部 M3 化；无 emoji 图标残留；无硬编码 `TvColors`/十六进制色值残留（theme 包除外）；`./gradlew check` 通过。

## 不做（YAGNI）

- M3 Expressive（CMP 1.8.0 不支持）
- 动态取色（Material You 壁纸取色）
- Navigation 库 / material3-adaptive 库
- UI 自动化测试、字体文件、共享组件预抽象
