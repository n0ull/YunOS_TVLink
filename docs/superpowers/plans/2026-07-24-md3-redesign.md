# TVLink M3 全面重设计 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 TVLink 全部 UI 重构为符合 Material Design 3 规范的实现（完整主题系统 + 3-tab 导航壳 + 8 屏重构）。

**Architecture:** Spec：`docs/superpowers/specs/2026-07-24-md3-redesign-design.md`。主题先行（Color/Theme/Brand），再做导航壳（MainShell + AppViewModel 导航模型），然后逐屏重构，Remote 最复杂放最后。协议层（proto/）与服务层（device/）零改动；UI 继续单向观察 AppViewModel 快照状态。

**Tech Stack:** Kotlin 2.1.20 / Compose Multiplatform 1.8.0（material3 ≈ Jetpack 1.3.x，**无 Expressive**）/ material-icons-extended 1.8.0。

**验证模型（代替 TDD）:** 本项目无 UI 测试基础设施，spec 明确不新增（YAGNI）。每个 Task 的测试周期 = ① `./gradlew :shared:compileKotlinDesktop` 编译通过 → ② 指定屏幕 `./gradlew :desktopApp:run` 人工核对清单 → ③ commit（pre-commit 钩子自动跑 ktlintFormat + `./gradlew check` 全门禁）。

## Global Constraints

每个 Task 都隐含遵守：

- 禁用 M3 Expressive API（CMP 1.8.0 不支持）；仅用 material3 1.3.x 稳定 API。
- 不引入 Navigation 库、material3-adaptive 库、动态取色、字体文件。
- 图标统一来自 `org.jetbrains.compose.material:material-icons-extended:1.8.0`；emoji 全替换。
- 主题 seed `#0E7490`；`Brand.accentBrush` 渐变仅用于品牌时刻（logo、搜索脉冲、遥控旋钮/摇杆）。
- 遥控 tab 恒深色；其余屏跟随系统深浅色。
- `ui/` 下除 `ui/theme/` 外，禁止出现 `Color(0x…)` 字面量（`Color.White` / `Color.Transparent` 除外）与 `TvColors` 引用（终扫前允许残留）。
- 服务层（device/）与协议层（proto/）一律不碰；`AppViewModel` 只改导航状态与新增 `castSeek` 透传。
- 代码风格 ktlint_official（120 列、尾随逗号、导入排序）；多 composable 文件沿用现有 `@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")` 模式。
- pre-commit 钩子若报 "ktlintFormat modified working files"：执行 `git add -u` 后原样重提（仓库既定流程）。
- Commit message 用 conventional commits；每个 Task 一次 commit。

---

### Task 1: 主题系统（图标依赖 + Color/Theme/Brand）

**Files:**
- Modify: `shared/build.gradle.kts`（jvmCommonMain dependencies 块）
- Create: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/Color.kt`
- Create: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/Brand.kt`
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/Theme.kt`（全量重写）
- 保留: `ui/theme/TvColors.kt`（暂留，屏幕 Task 逐步摘除引用，Task 7 删除）

**Interfaces:**
- Consumes: 无
- Produces:
  - `app.tvlink.ui.theme.Brand`：`AccentStart: Color`、`AccentEnd: Color`、`accentBrush: Brush`
  - `TvTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)` — 签名不变
  - 后续 Task 统一使用 `MaterialTheme.colorScheme.<role>`（primary/onPrimary/primaryContainer/onPrimaryContainer/surface/onSurface/onSurfaceVariant/surfaceVariant/surfaceContainerHigh/surfaceContainerHighest/outline/error/…）与 `MaterialTheme.shapes.medium`

- [ ] **Step 1: 添加 material-icons-extended 依赖**

`shared/build.gradle.kts` 的 `jvmCommonMain by creating { dependencies { ... } }` 块内追加一行：

```kotlin
implementation("org.jetbrains.compose.material:material-icons-extended:1.8.0")
```

- [ ] **Step 2: 创建 `Color.kt`（全套 M3 色板，seed #0E7490 的 tone 阶梯）**

```kotlin
package app.tvlink.ui.theme

import androidx.compose.ui.graphics.Color

// M3 tone 阶梯色板，seed = 品牌青 #0E7490(≈tone40)。
// 浅/深双套角色的对比度由 tone 间距(≥40)保证,运行步骤人工核对观感。

// ---- light ----
val mdLightPrimary = Color(0xFF0E7490)
val mdLightOnPrimary = Color(0xFFFFFFFF)
val mdLightPrimaryContainer = Color(0xFFC5EFF7)
val mdLightOnPrimaryContainer = Color(0xFF032936)
val mdLightSecondary = Color(0xFF3E6B77)
val mdLightOnSecondary = Color(0xFFFFFFFF)
val mdLightSecondaryContainer = Color(0xFFD2E8EE)
val mdLightOnSecondaryContainer = Color(0xFF0E252B)
val mdLightTertiary = Color(0xFF9C4FA0)
val mdLightOnTertiary = Color(0xFFFFFFFF)
val mdLightTertiaryContainer = Color(0xFFF6D8F8)
val mdLightOnTertiaryContainer = Color(0xFF331039)
val mdLightError = Color(0xFFBA1A1A)
val mdLightOnError = Color(0xFFFFFFFF)
val mdLightErrorContainer = Color(0xFFF9DEDC)
val mdLightOnErrorContainer = Color(0xFF410E0B)
val mdLightBackground = Color(0xFFF5FAFB)
val mdLightOnBackground = Color(0xFF171D1F)
val mdLightSurface = Color(0xFFF5FAFB)
val mdLightOnSurface = Color(0xFF171D1F)
val mdLightSurfaceVariant = Color(0xFFDBE4E7)
val mdLightOnSurfaceVariant = Color(0xFF3F484B)
val mdLightOutline = Color(0xFF6F797C)
val mdLightOutlineVariant = Color(0xFFBFC8CB)
val mdLightSurfaceContainerLowest = Color(0xFFFFFFFF)
val mdLightSurfaceContainerLow = Color(0xFFEFF5F6)
val mdLightSurfaceContainer = Color(0xFFE9F0F1)
val mdLightSurfaceContainerHigh = Color(0xFFE3EAEB)
val mdLightSurfaceContainerHighest = Color(0xFFDDE4E6)
val mdLightInverseSurface = Color(0xFF2C3133)
val mdLightInverseOnSurface = Color(0xFFECF2F3)
val mdLightInversePrimary = Color(0xFF8ADCEF)

// ---- dark ----
val mdDarkPrimary = Color(0xFF8ADCEF)
val mdDarkOnPrimary = Color(0xFF032936)
val mdDarkPrimaryContainer = Color(0xFF0A5B73)
val mdDarkOnPrimaryContainer = Color(0xFFC5EFF7)
val mdDarkSecondary = Color(0xFF9CC9D4)
val mdDarkOnSecondary = Color(0xFF0E252B)
val mdDarkSecondaryContainer = Color(0xFF2A4A54)
val mdDarkOnSecondaryContainer = Color(0xFFD2E8EE)
val mdDarkTertiary = Color(0xFFE3AEE6)
val mdDarkOnTertiary = Color(0xFF4A2450)
val mdDarkTertiaryContainer = Color(0xFF653769)
val mdDarkOnTertiaryContainer = Color(0xFFF6D8F8)
val mdDarkError = Color(0xFFF2B8B5)
val mdDarkOnError = Color(0xFF601410)
val mdDarkErrorContainer = Color(0xFF8C1D18)
val mdDarkOnErrorContainer = Color(0xFFF9DEDC)
val mdDarkBackground = Color(0xFF0E1517)
val mdDarkOnBackground = Color(0xFFDEE4E6)
val mdDarkSurface = Color(0xFF0E1517)
val mdDarkOnSurface = Color(0xFFDEE4E6)
val mdDarkSurfaceVariant = Color(0xFF3F484B)
val mdDarkOnSurfaceVariant = Color(0xFFBFC8CB)
val mdDarkOutline = Color(0xFF899396)
val mdDarkOutlineVariant = Color(0xFF3F484B)
val mdDarkSurfaceContainerLowest = Color(0xFF090F11)
val mdDarkSurfaceContainerLow = Color(0xFF171D1F)
val mdDarkSurfaceContainer = Color(0xFF1B2123)
val mdDarkSurfaceContainerHigh = Color(0xFF25292B)
val mdDarkSurfaceContainerHighest = Color(0xFF303537)
val mdDarkInverseSurface = Color(0xFFDEE4E6)
val mdDarkInverseOnSurface = Color(0xFF2C3133)
val mdDarkInversePrimary = Color(0xFF0E7490)
```

- [ ] **Step 3: 创建 `Brand.kt`（品牌点缀，TvColors 的唯一后继）**

```kotlin
package app.tvlink.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 品牌点缀色（青→紫渐变）。仅用于品牌时刻：logo、搜索脉冲、遥控旋钮/摇杆。 */
object Brand {
    val AccentStart = Color(0xFF37E8FF)
    val AccentEnd = Color(0xFFF586FF)
    val accentBrush = Brush.horizontalGradient(listOf(AccentStart, AccentEnd))
}
```

- [ ] **Step 4: 全量重写 `Theme.kt`（双 scheme + Shapes；字阶用 M3 默认不传）**

```kotlin
package app.tvlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightScheme =
    lightColorScheme(
        primary = mdLightPrimary,
        onPrimary = mdLightOnPrimary,
        primaryContainer = mdLightPrimaryContainer,
        onPrimaryContainer = mdLightOnPrimaryContainer,
        secondary = mdLightSecondary,
        onSecondary = mdLightOnSecondary,
        secondaryContainer = mdLightSecondaryContainer,
        onSecondaryContainer = mdLightOnSecondaryContainer,
        tertiary = mdLightTertiary,
        onTertiary = mdLightOnTertiary,
        tertiaryContainer = mdLightTertiaryContainer,
        onTertiaryContainer = mdLightOnTertiaryContainer,
        error = mdLightError,
        onError = mdLightOnError,
        errorContainer = mdLightErrorContainer,
        onErrorContainer = mdLightOnErrorContainer,
        background = mdLightBackground,
        onBackground = mdLightOnBackground,
        surface = mdLightSurface,
        onSurface = mdLightOnSurface,
        surfaceVariant = mdLightSurfaceVariant,
        onSurfaceVariant = mdLightOnSurfaceVariant,
        outline = mdLightOutline,
        outlineVariant = mdLightOutlineVariant,
        surfaceContainerLowest = mdLightSurfaceContainerLowest,
        surfaceContainerLow = mdLightSurfaceContainerLow,
        surfaceContainer = mdLightSurfaceContainer,
        surfaceContainerHigh = mdLightSurfaceContainerHigh,
        surfaceContainerHighest = mdLightSurfaceContainerHighest,
        inverseSurface = mdLightInverseSurface,
        inverseOnSurface = mdLightInverseOnSurface,
        inversePrimary = mdLightInversePrimary,
        surfaceTint = mdLightPrimary,
    )

private val DarkScheme =
    darkColorScheme(
        primary = mdDarkPrimary,
        onPrimary = mdDarkOnPrimary,
        primaryContainer = mdDarkPrimaryContainer,
        onPrimaryContainer = mdDarkOnPrimaryContainer,
        secondary = mdDarkSecondary,
        onSecondary = mdDarkOnSecondary,
        secondaryContainer = mdDarkSecondaryContainer,
        onSecondaryContainer = mdDarkOnSecondaryContainer,
        tertiary = mdDarkTertiary,
        onTertiary = mdDarkOnTertiary,
        tertiaryContainer = mdDarkTertiaryContainer,
        onTertiaryContainer = mdDarkOnTertiaryContainer,
        error = mdDarkError,
        onError = mdDarkOnError,
        errorContainer = mdDarkErrorContainer,
        onErrorContainer = mdDarkOnErrorContainer,
        background = mdDarkBackground,
        onBackground = mdDarkOnBackground,
        surface = mdDarkSurface,
        onSurface = mdDarkOnSurface,
        surfaceVariant = mdDarkSurfaceVariant,
        onSurfaceVariant = mdDarkOnSurfaceVariant,
        outline = mdDarkOutline,
        outlineVariant = mdDarkOutlineVariant,
        surfaceContainerLowest = mdDarkSurfaceContainerLowest,
        surfaceContainerLow = mdDarkSurfaceContainerLow,
        surfaceContainer = mdDarkSurfaceContainer,
        surfaceContainerHigh = mdDarkSurfaceContainerHigh,
        surfaceContainerHighest = mdDarkSurfaceContainerHighest,
        inverseSurface = mdDarkInverseSurface,
        inverseOnSurface = mdDarkInverseOnSurface,
        inversePrimary = mdDarkInversePrimary,
        surfaceTint = mdDarkPrimary,
    )

private val TvShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun TvTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = TvShapes,
        content = content,
    )
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`（TvColors.kt 仍在，旧屏幕不受影响）

- [ ] **Step 6: Commit**

```bash
git add shared/build.gradle.kts shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/
git commit -m "feat: M3 主题系统——全套色板/形状/品牌点缀 + material-icons-extended"
```

---

### Task 2: 导航模型 + MainShell + MoreScreen（删 Home）

**Files:**
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/AppViewModel.kt`
- Create: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/MainShell.kt`
- Create: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/MoreScreen.kt`
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/App.kt`（全量重写）
- Delete: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/HomeScreen.kt`

**Interfaces:**
- Consumes: Task 1 的 `TvTheme`；现有 `DongleScreen(vm)`（expect composable，`app.tvlink.ui.widgets`）；现有 7 个 Screen composable（本 Task 只接线，不改其内部）
- Produces:
  - `AppViewModel.Screen`：`data object DevicePicker`；`data class Main(val tab: MainTab = MainTab.REMOTE, val moreSub: MoreSub? = null)`
  - `AppViewModel.MainTab { REMOTE, CAST, MORE }`；`AppViewModel.MoreSub { SCREENSHOT, APPS, SETTINGS, DONGLE }`
  - `vm.navTab(tab: MainTab)`、`vm.navMore(sub: MoreSub)`、`vm.navBack()`
  - `MainShell(vm: AppViewModel, main: AppViewModel.Screen.Main)`（`app.tvlink.ui` 包）
  - `MoreScreen(vm: AppViewModel)`（`app.tvlink.ui.screens` 包）

- [ ] **Step 1: 重写 AppViewModel 导航部分**

`AppViewModel.kt` 中：

① 替换整个 `Screen` sealed interface（删除 Home/Remote/Cast/Screenshot/Apps/Settings/Dongle 各 object）：

```kotlin
    // ---- navigation ----
    sealed interface Screen {
        data object DevicePicker : Screen

        data class Main(
            val tab: MainTab = MainTab.REMOTE,
            val moreSub: MoreSub? = null,
        ) : Screen
    }

    enum class MainTab { REMOTE, CAST, MORE }

    enum class MoreSub { SCREENSHOT, APPS, SETTINGS, DONGLE }
```

② 替换 `nav`/`navBack`（删除旧 `nav(to:)`；外部一律走 navTab/navMore）：

```kotlin
    fun navTab(tab: MainTab) {
        screen = (screen as? Screen.Main)?.copy(tab = tab, moreSub = null) ?: screen
    }

    fun navMore(sub: MoreSub) {
        screen = (screen as? Screen.Main)?.copy(moreSub = sub) ?: screen
    }

    fun navBack() {
        val s = screen as? Screen.Main ?: return
        screen =
            when {
                s.moreSub != null -> s.copy(moreSub = null)
                s.tab != MainTab.REMOTE -> s.copy(tab = MainTab.REMOTE)
                else -> s
            }
    }
```

③ init 块中 CONNECTED 分支：`screen = Screen.Home` → `screen = Screen.Main()`

④ actions 区新增（供 Task 4 的 Cast 进度拖动使用）：

```kotlin
    fun castSeek(ms: Long) {
        viewModelScope.launch(Dispatchers.IO) { cast?.seek(ms) }
    }
```

- [ ] **Step 2: 创建 `MainShell.kt`（3-tab 壳，宽屏 Rail / 窄屏 Bar）**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tvlink.ui.screens.AppsScreen
import app.tvlink.ui.screens.CastScreen
import app.tvlink.ui.screens.MoreScreen
import app.tvlink.ui.screens.RemoteScreen
import app.tvlink.ui.screens.ScreenshotScreen
import app.tvlink.ui.screens.SettingsScreen
import app.tvlink.ui.widgets.DongleScreen

private data class TabDest(
    val tab: AppViewModel.MainTab,
    val label: String,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        TabDest(AppViewModel.MainTab.REMOTE, "遥控", Icons.Filled.SportsEsports),
        TabDest(AppViewModel.MainTab.CAST, "投屏", Icons.Filled.Cast),
        TabDest(AppViewModel.MainTab.MORE, "更多", Icons.Filled.MoreHoriz),
    )

@Composable
fun MainShell(
    vm: AppViewModel,
    main: AppViewModel.Screen.Main,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail {
                    tabs.forEach { d ->
                        NavigationRailItem(
                            selected = main.tab == d.tab,
                            onClick = { vm.navTab(d.tab) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            tabs.forEach { d ->
                                NavigationBarItem(
                                    selected = main.tab == d.tab,
                                    onClick = { vm.navTab(d.tab) },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (main.moreSub) {
                        AppViewModel.MoreSub.SCREENSHOT -> ScreenshotScreen(vm)
                        AppViewModel.MoreSub.APPS -> AppsScreen(vm)
                        AppViewModel.MoreSub.SETTINGS -> SettingsScreen(vm)
                        AppViewModel.MoreSub.DONGLE -> DongleScreen(vm)
                        null ->
                            when (main.tab) {
                                AppViewModel.MainTab.REMOTE -> RemoteScreen(vm)
                                AppViewModel.MainTab.CAST -> CastScreen(vm)
                                AppViewModel.MainTab.MORE -> MoreScreen(vm)
                            }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: 创建 `MoreScreen.kt`（ListItem 菜单页）**

```kotlin
package app.tvlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.widgets.platformName

private data class MoreItem(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val sub: AppViewModel.MoreSub,
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun MoreScreen(vm: AppViewModel) {
    val items =
        buildList {
            add(MoreItem("电视截屏", "截取电视当前画面", Icons.Filled.PhotoCamera, AppViewModel.MoreSub.SCREENSHOT))
            add(MoreItem("应用管理", "查看 / 打开 / 卸载电视应用", Icons.Filled.Apps, AppViewModel.MoreSub.APPS))
            if (platformName == "android") {
                add(MoreItem("魔投配网", "MagicCast 蓝牙配网", Icons.Filled.Bluetooth, AppViewModel.MoreSub.DONGLE))
            }
            add(MoreItem("设置", "连接信息与关于", Icons.Filled.Settings, AppViewModel.MoreSub.SETTINGS))
        }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("更多") })
        LazyColumn {
            items(items, key = { it.title }) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(item.desc) },
                    leadingContent = { Icon(item.icon, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier =
                        Modifier.clickable {
                            if (item.sub == AppViewModel.MoreSub.APPS) vm.refreshApps()
                            vm.navMore(item.sub)
                        },
                )
            }
        }
    }
}
```

注：CMP 1.8.0 的 material3 中 `TopAppBar` 仍标 `@ExperimentalMaterial3Api`，需 `@OptIn`（后续 Screen Task 同理）。

- [ ] **Step 4: 全量重写 `App.kt`（主题按 tab 切换 + BackHandler 新规则）**

```kotlin
package app.tvlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tvlink.ui.screens.DevicePickerScreen
import app.tvlink.ui.theme.TvTheme
import app.tvlink.ui.widgets.BackHandler

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun App() {
    val fallbackOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }
        }
    val owner = LocalViewModelStoreOwner.current ?: fallbackOwner
    DisposableEffect(owner) {
        onDispose { if (owner === fallbackOwner) owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: AppViewModel = viewModel { AppViewModel() }
        val screen = vm.screen
        // 遥控 tab 恒深（品类惯例）；其余跟随系统
        val remoteActive = screen is AppViewModel.Screen.Main && screen.tab == AppViewModel.MainTab.REMOTE
        TvTheme(dark = remoteActive || isSystemInDarkTheme()) {
            // Main 根（遥控 tab）不拦截返回键，交系统默认（退出/最小化）
            val canBack =
                screen is AppViewModel.Screen.Main &&
                    (screen.moreSub != null || screen.tab != AppViewModel.MainTab.REMOTE)
            BackHandler(enabled = canBack) { vm.navBack() }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(vm.notice) {
                if (vm.notice.isNotEmpty()) {
                    snackbar.showSnackbar(vm.notice)
                    vm.notice = ""
                }
            }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding),
                ) {
                    when (screen) {
                        AppViewModel.Screen.DevicePicker -> DevicePickerScreen(vm)
                        is AppViewModel.Screen.Main -> MainShell(vm, screen)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: 删除 `HomeScreen.kt`**

```bash
git rm shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/HomeScreen.kt
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`。若报 `nav(` 未解析——检查是否有遗漏的旧调用方（应已全部随 HomeScreen 删除）。

- [ ] **Step 7: 运行核对（第一个可运行里程碑）**

Run: `./gradlew :desktopApp:run`
核对清单：
- 未连接：DevicePicker（旧样式，正常）→ 手动 IP 连一台电视（或等待自动发现）
- 连接后：出现 3-tab 壳，默认落在遥控 tab（整 UI 深色）；切到投屏/更多（跟随系统色）
- 更多页：4 个（桌面端 3 个，无魔投）ListItem，可进入截屏/应用/设置（仍是旧样式，正常），系统返回键回更多
- 窗口拉宽 ≥600dp：底栏变为左侧 NavigationRail
- 遥控 tab 按返回：不拦截（桌面无系统返回键，跳过）

- [ ] **Step 8: Commit**

```bash
git add shared/src/jvmCommonMain/kotlin/app/tvlink/ui/
git commit -m "feat: M3 导航壳——3-tab MainShell + 导航模型重构,Home 宫格退役"
```

---

### Task 3: DevicePickerScreen 重构

**Files:**
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/DevicePickerScreen.kt`（全量重写）

**Interfaces:**
- Consumes: `Brand.accentBrush`；`vm.connState/foundDevices/startDiscovery()/connectTo(d)/connectToIp(ip)`（均现状不变）；`DeviceManager.ConnState`
- Produces: 无新接口（`DevicePickerScreen(vm)` 签名不变）

- [ ] **Step 1: 全量重写文件**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tvlink.device.DeviceManager
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.Brand

@Composable
fun DevicePickerScreen(vm: AppViewModel) {
    var manualIp by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    val searching = vm.connState == DeviceManager.ConnState.SEARCHING

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        BrandMark(active = searching)
        Spacer(Modifier.height(16.dp))
        Text("TVLink", style = MaterialTheme.typography.headlineLarge)
        Text(
            "用手机玩电视",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (vm.connState) {
            DeviceManager.ConnState.SEARCHING ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在搜索同一 Wi-Fi 下的电视设备…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

            DeviceManager.ConnState.CONNECTING ->
                Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)

            DeviceManager.ConnState.FAILED ->
                Text("连接失败，请确认设备在线后重试", color = MaterialTheme.colorScheme.error)

            else ->
                Text(
                    if (vm.foundDevices.isEmpty()) "点击下方按钮搜索设备" else "发现 ${vm.foundDevices.size} 个设备",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.foundDevices, key = { it.ip }) { d ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { vm.connectTo(d) }) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                d.name.ifEmpty {
                                    if (d.source == "ib-scan") "电视 (IB 通道)" else "未命名设备"
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    d.ip,
                                    d.model.takeIf { it.isNotEmpty() },
                                    d.mac.takeIf { it.isNotEmpty() },
                                    d.ibVer.takeIf { it.isNotEmpty() }?.let { "IB v$it" },
                                    d.ibSid.takeIf { it.isNotEmpty() }?.let { "sid=$it" },
                                ).joinToString("  ·  "),
                            )
                        },
                        leadingContent = {
                            Box(
                                Modifier.size(40.dp).background(Brand.accentBrush, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Tv, contentDescription = null, tint = Color.White) }
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.startDiscovery() }, enabled = !searching) {
                Text(if (searching) "搜索中…" else "重新扫描")
            }
            TextButton(onClick = { showManual = !showManual }) { Text("手动输入 IP") }
        }

        if (showManual) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("电视 IP 地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions =
                    KeyboardActions(
                        onGo = { if (manualIp.isNotBlank()) vm.connectToIp(manualIp.trim()) },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 品牌时刻：渐变圆 logo；搜索中脉冲呼吸。 */
@Composable
private fun BrandMark(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(72.dp)
            .alpha(if (active) a else 1f)
            .background(Brand.accentBrush, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Tv,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}
```

要点：emoji/「搜」字移除；`TvColors` 引用全部换成 `colorScheme` 角色；渐变仅留 BrandMark 与设备头像两处品牌时刻。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 运行核对**

Run: `./gradlew :desktopApp:run`
核对：渐变 logo（静态不脉冲）→ 点「重新扫描」→ logo 脉冲 + 状态行小转圈 → 设备卡片（渐变 TV 头像 + 名称 + 副信息 + chevron）→ 点卡片可连接。FAILED 态文案为 error 色。

- [ ] **Step 4: Commit**

```bash
git add shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/DevicePickerScreen.kt
git commit -m "feat: DevicePicker M3 化——BrandMark logo + ElevatedCard/ListItem 设备卡"
```

---

### Task 4: CastScreen 重构（含可拖动进度）

**Files:**
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/CastScreen.kt`（全量重写）

**Interfaces:**
- Consumes: `vm.castSeek(ms)`（Task 2 新增）；`vm.castState/castDuration/castPosition/castTitle/cast?.play()/pause()/stop()/volume(v)`；`pickMediaFile(type, onResult)`（expect composable）；`CastController.PlayState`
- Produces: 无新接口

- [ ] **Step 1: 全量重写文件**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tvlink.proto.cast.CastController
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.widgets.pickMediaFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(vm: AppViewModel) {
    var pendingType by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("本地投屏") })
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MediaTypeCard("照片", Icons.Filled.Photo) { pendingType = "image" }
                MediaTypeCard("视频", Icons.Filled.Movie) { pendingType = "video" }
                MediaTypeCard("音乐", Icons.Filled.MusicNote) { pendingType = "audio" }
            }

            pendingType?.let { type ->
                pickMediaFile(type) { path ->
                    pendingType = null
                    if (path != null) {
                        val title = path.substringAfterLast('/').substringAfterLast('\\')
                        vm.castFile(path, title, type)
                    }
                }
            }

            Spacer(Modifier.padding(12.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (vm.castTitle.isEmpty()) "未在投屏" else vm.castTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    AssistChip(onClick = {}, label = { Text("状态: ${vm.castState}") })

                    Spacer(Modifier.padding(8.dp))
                    SeekBar(vm)
                    Text(
                        "${fmtMs(vm.castPosition)} / ${fmtMs(vm.castDuration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.padding(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.castState == CastController.PlayState.PLAYING) {
                            Button(onClick = { vm.cast?.pause() }) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("暂停")
                            }
                        } else {
                            Button(onClick = { vm.cast?.play() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("播放")
                            }
                        }
                        OutlinedButton(onClick = { vm.cast?.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("退出")
                        }
                    }

                    Spacer(Modifier.padding(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "音量",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        var vol by remember { mutableStateOf(10f) }
                        Slider(
                            value = vol,
                            onValueChange = { vol = it },
                            onValueChangeFinished = { vm.cast?.volume(vol.toInt()) },
                            valueRange = 0f..30f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTypeCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 可拖动进度条：拖动中显示本地值，松手 seek。 */
@Composable
private fun SeekBar(vm: AppViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val duration = vm.castDuration.toFloat().coerceAtLeast(1f)
    Slider(
        value = if (dragging) dragPos else vm.castPosition.toFloat().coerceIn(0f, duration),
        onValueChange = {
            dragging = true
            dragPos = it
        },
        onValueChangeFinished = {
            dragging = false
            vm.castSeek(dragPos.toLong())
        },
        valueRange = 0f..duration,
        enabled = vm.castDuration > 0,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
```

注：`ElevatedCard(onClick=…)` 是 material3 的可点卡重载；`状态` chip 仅展示不响应（M3 允许 disabled 态，保持 onClick 空实现即可）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 运行核对**

Run: `./gradlew :desktopApp:run`
核对：投屏 tab → 三枚类型卡（图标 primary 色）→ 选一视频文件 → 播放卡出现状态 chip；播放中主按钮变「暂停」；拖动进度条松手后电视进度跳转（无真机时核对 UI 行为与日志）；音量 Slider 松手生效。

- [ ] **Step 4: Commit**

```bash
git add shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/CastScreen.kt
git commit -m "feat: Cast M3 化——类型卡/状态 chip/可拖动进度条"
```

---

### Task 5: More 子页三屏（Screenshot / Apps / Settings）

**Files:**
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/ScreenshotScreen.kt`（全量重写）
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/AppsScreen.kt`（全量重写）
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/SettingsScreen.kt`（全量重写）

**Interfaces:**
- Consumes: `vm.navBack()`（Task 2）；`vm.takeScreenshot/lastShot/shotBusy`；`saveShot(bytes)`（expect）；`vm.tvApps/refreshApps/rpm.openApp(pkg)/rpm.uninstall(pkg)/rpm.installByUrl(pkg,url)`；`vm.connectedName/connectedIp/connectedIbVer/connectedIbSid/mediaServerUrl/rc.ibReady.value`
- Produces: 无新接口

- [ ] **Step 1: 重写 `ScreenshotScreen.kt`**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tvlink.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("电视截屏") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ElevatedCard(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val shot = vm.lastShot
                    if (shot == null) {
                        Text(
                            "还没有截图，点击下方按钮截取电视画面",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        app.tvlink.ui.widgets.ByteArrayImage(
                            bytes = shot,
                            contentDescription = "电视截图",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { vm.takeScreenshot() }, enabled = !vm.shotBusy) {
                    Text(if (vm.shotBusy) "截取中…" else "截屏")
                }
                if (vm.lastShot != null) {
                    Button(
                        onClick = {
                            vm.notice =
                                app.tvlink.ui.widgets
                                    .saveShot(vm.lastShot!!) ?: "已保存"
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
```

- [ ] **Step 2: 重写 `AppsScreen.kt`**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvlink.device.RpmService
import app.tvlink.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: AppViewModel) {
    var installUrl by remember { mutableStateOf("") }
    var installPkg by remember { mutableStateOf("") }
    var showInstall by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<RpmService.TvApp?>(null) }

    // 进屏即拉列表:module 未就绪时该请求同时触发 R2 唤醒,模块上线后经挂起补发回填
    LaunchedEffect(Unit) { vm.refreshApps() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("应用管理") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { vm.refreshApps() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { showInstall = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "按 URL 安装")
                }
            },
        )

        Text(
            "共 ${vm.tvApps.size} 个应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (vm.tvApps.isEmpty()) {
            Text(
                "列表为空——电视的应用管理模块(com.yunos.idc.appstore)未就绪；部分固件不提供该模块。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        LazyColumn(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.tvApps, key = { it.packageName }) { app ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(app.appName.ifEmpty { app.packageName })
                        },
                        supportingContent = {
                            Text("${app.packageName}  ${app.versionName}")
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.rpm.openApp(app.packageName) }) {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = "打开")
                                }
                                IconButton(onClick = { uninstallTarget = app }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "卸载",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showInstall) {
        AlertDialog(
            onDismissRequest = { showInstall = false },
            title = { Text("按 URL 安装应用") },
            text = {
                Column {
                    OutlinedTextField(
                        value = installPkg,
                        onValueChange = { installPkg = it },
                        label = { Text("包名") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("APK 下载地址 (http/https)") },
                        singleLine = true,
                    )
                    Text(
                        "电视将自行下载并安装该 APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (installPkg.isNotBlank() && installUrl.isNotBlank()) {
                            vm.rpm.installByUrl(installPkg.trim(), installUrl.trim())
                        }
                        showInstall = false
                    },
                ) { Text("安装") }
            },
            dismissButton = { TextButton(onClick = { showInstall = false }) { Text("取消") } },
        )
    }

    uninstallTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载应用") },
            text = { Text("确定从电视上卸载 ${app.appName.ifEmpty { app.packageName }} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rpm.uninstall(app.packageName)
                        uninstallTarget = null
                    },
                ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text("取消") } },
        )
    }
}
```

- [ ] **Step 3: 重写 `SettingsScreen.kt`**

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvlink.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("当前连接")
                    SettingItem(Icons.Filled.Tv, "设备", vm.connectedName)
                    SettingItem(Icons.Filled.Wifi, "IP", vm.connectedIp)
                    SettingItem(Icons.Filled.Memory, "IB 版本", vm.connectedIbVer.ifEmpty { "未探测（手动连接）" })
                    SettingItem(Icons.Filled.Link, "IB sid", vm.connectedIbSid.ifEmpty { "未探测（手动连接）" })
                    SettingItem(Icons.Filled.Wifi, "媒体服务", vm.mediaServerUrl.ifEmpty { "未启动" })
                    SettingItem(
                        Icons.Filled.Link,
                        "IB 快速通道",
                        if (vm.rc.ibReady.value) "已连接" else "未连接（使用回退通道）",
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("关于")
                    SettingItem(Icons.Filled.Info, "TVLink 1.0", "开源的 YunOS 电视局域网控制客户端")
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
```

注：`Icons.Filled.Memory/Link/Wifi/Info` 均在 extended 集；`SettingItem` 的 `icon` 参数类型写成全限定可避免再引一行 import（二选一，保持 ktlint 导入排序通过即可）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 运行核对**

Run: `./gradlew :desktopApp:run`
核对：更多 → 截屏（TopAppBar 返回、FilledTonalButton 截屏）；更多 → 应用（actions 刷新/安装图标、卸载 Delete 图标为 error 色、两个确认弹窗正常）；更多 → 设置（两组卡片 ListItem、IB 通道状态显示正确）。

- [ ] **Step 6: Commit**

```bash
git add shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/
git commit -m "feat: 截屏/应用/设置三屏 M3 化——TopAppBar + ListItem + error 语义色"
```

---

### Task 6: RemoteScreen 重构（最复杂，纯视觉层）

**Files:**
- Modify: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/RemoteScreen.kt`（全量重写；手势/节流/发送逻辑逐字保留）

**Interfaces:**
- Consumes: `Brand.accentBrush`；`vm.rc.setMode/mouseMove/mouseClick/joystick/accel/gyro`、`vm.keyClick/imeActive/imeText/imeChanged/imeCommit/voiceText`（均现状不变）；`MotionSensor()`/`VoiceButton(onText)`（expect）；`RcMode`（文件内私有枚举，保留）
- Produces: 无新接口

- [ ] **Step 1: 全量重写文件**

变更映射（逻辑零改动，仅视觉）：
- 顶部手搓返回行 + 模式文字行 → `SingleChoiceSegmentedButtonRow`（连接名不再显示，设置页可查）
- `RcButton`:`Box+Color(0xFF34343A)` → `Surface(onClick)` + `surfaceContainerHighest` + `tonalElevation=2.dp`
- 面板底色 `Color(0xFF2E2E34)` → `colorScheme.surfaceContainerHigh`；`RoundedCornerShape(16.dp)` → `MaterialTheme.shapes.medium`
- 文字色 `TvColors.TextOnDark/TextOnDarkSecondary` → `colorScheme.onSurface/onSurfaceVariant`
- 选中态 `Color(0x3337E8FF)` → 由 SegmentedButton 接管（删除）
- 底部语音行 → `BottomAppBar` 包裹 `VoiceButton`
- 体感占位 🎯 → `Icon(Icons.Filled.SportsEsports, Modifier.size(64.dp), tint = colorScheme.primary)`
- 旋钮/摇杆 `TvColors.accentBrush` → `Brand.accentBrush`；电源确认/IME 两个 AlertDialog 原样保留（M3 默认即合规）

完整文件（面板代码与现状逐字相同的部分仅作上述颜色/形状替换）：

```kotlin
// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tvlink.proto.ib.IbConst
import app.tvlink.proto.ib.RcKey
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.Brand
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class RcMode(
    val label: String,
    val ibMode: Int,
) {
    KEYPAD("按键", IbConst.CHANGETYPE_DEFAULT),
    TOUCHPAD("触控板", IbConst.CHANGETYPE_DEFAULT),
    JOYSTICK("手柄", IbConst.CHANGETYPE_JOYSTICK),
    WHEEL("方向盘", IbConst.CHANGETYPE_DEFAULT),
    MOTION("体感", IbConst.CHANGETYPE_MOTIONPAD),
}

/** Rate-limits drag-driven TCP sends (~25/s); bypass for final/recenter events. */
private class SendThrottle(
    private val intervalMs: Long = 40,
) {
    private var last = 0L

    fun trySend(
        now: Long = System.currentTimeMillis(),
        block: () -> Unit,
    ) {
        if (now - last >= intervalMs) {
            last = now
            block()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: AppViewModel) {
    var mode by remember { mutableStateOf(RcMode.KEYPAD) }
    var powerConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RcMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = {
                        mode = m
                        vm.rc.setMode(m.ibMode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = RcMode.entries.size),
                ) {
                    Text(m.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                RcMode.KEYPAD ->
                    KeypadPanel(
                        onKey = { if (it == RcKey.POWER) powerConfirm = true else vm.keyClick(it) },
                    )

                RcMode.TOUCHPAD -> TouchpadPanel(vm) { if (it == RcKey.POWER) powerConfirm = true else vm.keyClick(it) }
                RcMode.JOYSTICK -> JoystickPanel(vm)
                RcMode.WHEEL -> WheelPanel(vm)
                RcMode.MOTION -> MotionPanel(vm)
            }
        }

        BottomAppBar {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                app.tvlink.ui.widgets
                    .VoiceButton(onText = { vm.voiceText(it) })
            }
        }
    }

    if (powerConfirm) {
        AlertDialog(
            onDismissRequest = { powerConfirm = false },
            title = { Text("待机") },
            text = { Text("确定要让电视待机吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.keyClick(RcKey.POWER)
                        powerConfirm = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { powerConfirm = false }) { Text("取消") } },
        )
    }

    if (vm.imeActive) {
        AlertDialog(
            onDismissRequest = { vm.imeCommit() },
            title = { Text("电视请求输入") },
            text = {
                OutlinedTextField(
                    value = vm.imeText,
                    onValueChange = { vm.imeChanged(it) },
                    label = { Text("在手机上输入，实时同步到电视") },
                )
            },
            confirmButton = { TextButton(onClick = { vm.imeCommit() }) { Text("完成") } },
        )
    }
}

// ---------- keypad ----------

@Composable
private fun RcButton(
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 64,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun KeypadPanel(onKey: (RcKey) -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RcButton("电源", size = 56) { onKey(RcKey.POWER) }
            RcButton("魔键", size = 56) { onKey(RcKey.MAGIC) }
        }
        // dpad
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RcButton("▲") { onKey(RcKey.UP) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RcButton("◀") { onKey(RcKey.LEFT) }
                Spacer(Modifier.width(12.dp))
                RcButton("OK", size = 76) { onKey(RcKey.OK) }
                Spacer(Modifier.width(12.dp))
                RcButton("▶") { onKey(RcKey.RIGHT) }
            }
            RcButton("▼") { onKey(RcKey.DOWN) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RcButton("音量−") { onKey(RcKey.VOL_DOWN) }
            RcButton("音量+") { onKey(RcKey.VOL_UP) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcButton("主页") { onKey(RcKey.HOME) }
            RcButton("返回", size = 72) { onKey(RcKey.BACK) }
            RcButton("菜单") { onKey(RcKey.MENU) }
        }
    }
}

// ---------- touchpad ----------

@Composable
private fun TouchpadPanel(
    vm: AppViewModel,
    onKey: (RcKey) -> Unit,
) {
    var cursor by remember { mutableStateOf(Offset(200f, 200f)) }
    val throttle = remember { SendThrottle() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        cursor += drag
                        throttle.trySend { vm.rc.mouseMove(drag.x.roundToInt(), drag.y.roundToInt()) }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onTap = { vm.rc.mouseClick() })
                },
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .offset { IntOffset(cursor.x.roundToInt() - 9, cursor.y.roundToInt() - 9) }
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcButton("◀", size = 56) { onKey(RcKey.LEFT) }
            RcButton("OK", size = 56) { onKey(RcKey.OK) }
            RcButton("▶", size = 56) { onKey(RcKey.RIGHT) }
            RcButton("返回", size = 56) { onKey(RcKey.BACK) }
        }
    }
}

// ---------- joystick ----------

@Composable
private fun Stick(
    modifier: Modifier = Modifier,
    onRelease: (() -> Unit)? = null,
    onAxis: (x: Int, y: Int) -> Unit,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .size(140.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        knob = Offset.Zero
                        onRelease?.invoke() ?: onAxis(128, 128)
                    },
                    onDragCancel = {
                        knob = Offset.Zero
                        onRelease?.invoke() ?: onAxis(128, 128)
                    },
                ) { change, drag ->
                    change.consume()
                    val max = 50.dp.toPx()
                    val nx = (knob.x + drag.x).coerceIn(-max, max)
                    val ny = (knob.y + drag.y).coerceIn(-max, max)
                    knob = Offset(nx, ny)

                    // map [-50,50]dp -> [0,255], center 128, deadzone -> 128
                    fun map(v: Float): Int {
                        val norm = v / max * 100 // [-100,100]
                        return if (abs(norm) < 5) 128 else ((norm / 100 + 1) * 255 / 2).roundToInt().coerceIn(0, 255)
                    }
                    onAxis(map(nx), map(ny))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .background(Brand.accentBrush, CircleShape),
    }
}

@Composable
private fun JoystickPanel(vm: AppViewModel) {
    var left by remember { mutableStateOf(128 to 128) }
    var right by remember { mutableStateOf(128 to 128) }
    val throttle = remember { SendThrottle() }

    fun send() = vm.rc.joystick(listOf(0 to left.first, 1 to left.second, 2 to right.first, 5 to right.second))

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcButton("LB", size = 52) { vm.keyClick(RcKey.PAD_LB) }
            RcButton("RB", size = 52) { vm.keyClick(RcKey.PAD_RB) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stick(
                onRelease = {
                    left = 128 to 128
                    send()
                },
            ) { x, y ->
                left = x to y
                throttle.trySend { send() }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RcButton("Y", size = 48) { vm.keyClick(RcKey.PAD_Y) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RcButton("X", size = 48) { vm.keyClick(RcKey.PAD_X) }
                    RcButton("B", size = 48) { vm.keyClick(RcKey.PAD_B) }
                }
                RcButton("A", size = 48) { vm.keyClick(RcKey.PAD_A) }
            }
            Stick(
                onRelease = {
                    right = 128 to 128
                    send()
                },
            ) { x, y ->
                right = x to y
                throttle.trySend { send() }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcButton("SELECT", size = 52) { vm.keyClick(RcKey.PAD_SELECT) }
            RcButton("START", size = 52) { vm.keyClick(RcKey.PAD_START) }
        }
    }
}

// ---------- steering wheel (real mouse mode) ----------

@Composable
private fun WheelPanel(vm: AppViewModel) {
    val throttle = remember { SendThrottle() }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text("拖动方向盘区域控制光标，单击为确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .size(220.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        throttle.trySend { vm.rc.mouseMove(-drag.x.roundToInt(), -drag.y.roundToInt()) }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onTap = { vm.rc.mouseClick() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(80.dp).background(Brand.accentBrush, CircleShape))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RcButton("返回") { vm.keyClick(RcKey.BACK) }
            RcButton("主页") { vm.keyClick(RcKey.HOME) }
        }
    }
}

// ---------- motion (placeholder unless platform feeds sensors) ----------

@Composable
private fun MotionPanel(vm: AppViewModel) {
    val sensor =
        remember {
            app.tvlink.ui.widgets
                .MotionSensor()
        }
    DisposableEffect(Unit) {
        sensor.start(
            onAccel = { x, y, z -> vm.rc.accel(x, y, z) },
            onGyro = { x, y, z -> vm.rc.gyro(x, y, z) },
        )
        onDispose { sensor.stop() }
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.SportsEsports,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("体感遥控", style = MaterialTheme.typography.titleLarge)
        Text(
            if (app.tvlink.ui.widgets.platformName == "android") {
                "传感器数据正实时发送到电视"
            } else {
                "桌面端无传感器，体感模式仅 Android 可用"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RcButton("OK") { vm.keyClick(RcKey.OK) }
            RcButton("返回") { vm.keyClick(RcKey.BACK) }
        }
    }
}
```

注：触控板光标圆点用 `colorScheme.primary`（深色下即浅青，比原 AccentStart 更贴主题）；摇杆/方向盘旋钮保留 `Brand.accentBrush` 品牌渐变。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :shared:compileKotlinDesktop`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 运行核对**

Run: `./gradlew :desktopApp:run`
核对：遥控 tab（整 UI 深色）→ SegmentedButton 五段切换（选中态为 M3 secondaryContainer）；按键面板圆形按钮有 tonal 层次；触控板拖动手感与光标正常（手势未动）；手柄双摇杆回中；方向盘拖动；体感占位图标；底部 BottomAppBar 语音按钮（桌面弹文本对话框）；电源键弹确认；颜色对比在深色下清晰可读。

- [ ] **Step 4: Commit**

```bash
git add shared/src/jvmCommonMain/kotlin/app/tvlink/ui/screens/RemoteScreen.kt
git commit -m "feat: Remote M3 化——SegmentedButton 模式切换 + Surface 按键 + BottomAppBar 语音条"
```

---

### Task 7: 终扫（删 TvColors、残留清零、全量验证）

**Files:**
- Delete: `shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/TvColors.kt`
- Modify: 任何 grep 命中的残留文件

**Interfaces:**
- Consumes: 全部前序 Task
- Produces: 完成标准（spec §8）：8 屏全部 M3 化；无 emoji 图标残留；`ui/` 下除 `theme/` 外无 `Color(0x…)` 字面量与 `TvColors` 引用；`./gradlew check` 通过

- [ ] **Step 1: 残留 grep（三发并行）**

```bash
grep -rn "TvColors" shared/src --include="*.kt" | grep -v "/theme/TvColors.kt"
grep -rln $'\xF0\x9F' shared/src/jvmCommonMain/kotlin/app/tvlink/ui --include="*.kt"
grep -rn "Color(0x" shared/src/jvmCommonMain/kotlin/app/tvlink/ui --include="*.kt" | grep -v "/theme/"
```

Expected: 三条均无输出。有命中则按前述 Task 的映射（`TvColors.X`→`colorScheme` 角色、emoji→Icon、hex→角色）逐一清除。

- [ ] **Step 2: 删除 TvColors.kt**

```bash
git rm shared/src/jvmCommonMain/kotlin/app/tvlink/ui/theme/TvColors.kt
./gradlew :shared:compileKotlinDesktop
```

Expected: `BUILD SUCCESSFUL`（Step 1 已保证无引用）

- [ ] **Step 3: 全量人工走查**

Run: `./gradlew :desktopApp:run`
按 spec §8 完成标准走查：DevicePicker → 连接 → 遥控（5 模式）→ 投屏 → 更多 → 截屏/应用/设置 → 宽窗 Rail / 窄窗 Bar 切换；浅色与深色（遥控 tab）两种主题下各屏对比度正常。

- [ ] **Step 4: Commit**

```bash
git add -A shared/src
git commit -m "refactor: TvColors 退役,M3 重设计收尾——emoji/硬编码色值清零"
```

---

## Self-Review 记录

- **Spec 覆盖**:spec §1 主题→Task 1；§2 导航壳→Task 2；§3 各屏→Task 3–6；§4 图标/依赖→Task 1+各屏；§5 数据流（castSeek 透传）→Task 2；§7 错误处理（三 AlertDialog 保留）→Task 4/6；§8 完成标准→Task 7。魔投配网入口（spec 遗漏，原 Home 有）已在 Task 2 MoreSub.DONGLE 补齐。
- **占位符扫描**:无 TBD/TODO；所有代码步骤含完整代码。
- **类型一致性**:`MoreSub`/`MainTab`/`Screen.Main`/`navTab`/`navMore`/`navBack`/`castSeek`/`Brand.accentBrush`/`MainShell(vm, main)`/`MoreScreen(vm)` 在 Task 间引用一致；`SegmentedButtonDefaults.itemShape(index, count)`、`Surface(onClick=)`、`ListItem(headlineContent=…)` 均为 material3 1.3.x 稳定签名。
