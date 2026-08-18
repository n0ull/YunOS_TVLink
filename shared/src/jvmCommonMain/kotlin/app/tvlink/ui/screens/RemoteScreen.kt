// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.AlertDialog
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
import app.tvlink.ui.icons.AppIcons
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
                        onKey = { if (it == RcKey.POWER) powerConfirm = true else vm.remote.keyClick(it) },
                    )

                RcMode.TOUCHPAD ->
                    TouchpadPanel(vm) {
                        if (it ==
                            RcKey.POWER
                        ) {
                            powerConfirm = true
                        } else {
                            vm.remote.keyClick(it)
                        }
                    }
                RcMode.JOYSTICK -> JoystickPanel(vm)
                RcMode.WHEEL -> WheelPanel(vm)
                RcMode.MOTION -> MotionPanel(vm)
            }
        }

        Spacer(Modifier.height(8.dp))
        // 原 BottomAppBar 整栏仅放一个语音按钮,白占一截垂直空间;改为底部居中单行
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            app.tvlink.ui.widgets
                .VoiceButton(onText = { vm.remote.voiceText(it) })
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
                        vm.remote.keyClick(RcKey.POWER)
                        powerConfirm = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { powerConfirm = false }) { Text("取消") } },
        )
    }

    if (vm.remote.imeActive) {
        AlertDialog(
            onDismissRequest = { vm.remote.imeCommit() },
            title = { Text("电视请求输入") },
            text = {
                OutlinedTextField(
                    value = vm.remote.imeText,
                    onValueChange = { vm.remote.imeChanged(it) },
                    label = { Text(vm.remote.imeHint.ifEmpty { "在手机上输入，实时同步到电视" }) },
                )
            },
            confirmButton = { TextButton(onClick = { vm.remote.imeCommit() }) { Text("完成") } },
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
        onClick = {
            app.tvlink.ui.widgets
                .keyVibrate()
            onClick()
        },
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
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RcButton("电源", size = 56) { onKey(RcKey.POWER) }
            RcButton("魔键", size = 56) { onKey(RcKey.MAGIC) }
        }
        // 控制区作为整体在剩余空间居中:长窄屏不再出现「电源贴顶/dpad 孤岛/底排贴底」
        // 三处大空档;各组间距固定,任何屏高下视觉节奏一致
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RcButton("▲", size = 68) { onKey(RcKey.UP) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RcButton("◀", size = 68) { onKey(RcKey.LEFT) }
                    Spacer(Modifier.width(16.dp))
                    RcButton("OK", size = 88) { onKey(RcKey.OK) }
                    Spacer(Modifier.width(16.dp))
                    RcButton("▶", size = 68) { onKey(RcKey.RIGHT) }
                }
                RcButton("▼", size = 68) { onKey(RcKey.DOWN) }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RcButton("返回") { onKey(RcKey.BACK) }
                    RcButton("主页") { onKey(RcKey.HOME) }
                    RcButton("菜单") { onKey(RcKey.MENU) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RcButton("音量−") { onKey(RcKey.VOL_DOWN) }
                    RcButton("音量+") { onKey(RcKey.VOL_UP) }
                }
            }
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
            )
        }
        // ponytail: 相对鼠标板无法滚动（IB 接口无 scroll 事件），长列表依赖电视侧光标/按键
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcButton("▲", size = 48) { onKey(RcKey.UP) }
            RcButton("▼", size = 48) { onKey(RcKey.DOWN) }
            RcButton("◀", size = 48) { onKey(RcKey.LEFT) }
            RcButton("▶", size = 48) { onKey(RcKey.RIGHT) }
            RcButton("OK", size = 48) { onKey(RcKey.OK) }
            RcButton("返回", size = 48) { onKey(RcKey.BACK) }
        }
    }
}

// ---------- joystick ----------

@Composable
private fun Stick(
    size: Int = 140,
    onRelease: (() -> Unit)? = null,
    onAxis: (x: Int, y: Int) -> Unit,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val knobDp = (size * 0.37f).roundToInt()
    // 行程 = (盘面 − 摇杆帽)/2:帽沿始终不出界(旧值 50dp 硬编码,帽沿越界 6dp)
    val travelDp = (size - knobDp) / 2
    Box(
        Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .pointerInput(size) {
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
                    val max = travelDp.dp.toPx()
                    val nx = (knob.x + drag.x).coerceIn(-max, max)
                    val ny = (knob.y + drag.y).coerceIn(-max, max)
                    knob = Offset(nx, ny)

                    // map [-travel,travel] -> [0,255], center 128, deadzone -> 128
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
                .size(knobDp.dp)
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .background(Brand.accentBrush, CircleShape),
        )
    }
}

@Composable
private fun JoystickPanel(vm: AppViewModel) {
    var left by remember { mutableStateOf(128 to 128) }
    var right by remember { mutableStateOf(128 to 128) }
    val throttle = remember { SendThrottle() }

    fun send() = vm.rc.joystick(listOf(0 to left.first, 1 to left.second, 2 to right.first, 5 to right.second))

    // 窄屏适配:360dp 屏可用宽 ~328dp,默认 140dp 双摇杆 + ABXY 列 ~386dp 必溢出
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 400.dp
        val stickSize = if (compact) 104 else 140
        val padBtnSize = if (compact) 40 else 48
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RcButton("LT", size = 52) { vm.remote.keyClick(RcKey.PAD_LT) }
                RcButton("LB", size = 52) { vm.remote.keyClick(RcKey.PAD_LB) }
                RcButton("RB", size = 52) { vm.remote.keyClick(RcKey.PAD_RB) }
                RcButton("RT", size = 52) { vm.remote.keyClick(RcKey.PAD_RT) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stick(
                    size = stickSize,
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
                    RcButton("Y", size = padBtnSize) { vm.remote.keyClick(RcKey.PAD_Y) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RcButton("X", size = padBtnSize) { vm.remote.keyClick(RcKey.PAD_X) }
                        RcButton("B", size = padBtnSize) { vm.remote.keyClick(RcKey.PAD_B) }
                    }
                    RcButton("A", size = padBtnSize) { vm.remote.keyClick(RcKey.PAD_A) }
                }
                Stick(
                    size = stickSize,
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
                RcButton("SELECT", size = 52) { vm.remote.keyClick(RcKey.PAD_SELECT) }
                RcButton("START", size = 52) { vm.remote.keyClick(RcKey.PAD_START) }
            }
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
            RcButton("返回") { vm.remote.keyClick(RcKey.BACK) }
            RcButton("主页") { vm.remote.keyClick(RcKey.HOME) }
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
            AppIcons.SportsEsports,
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
            RcButton("OK") { vm.remote.keyClick(RcKey.OK) }
            RcButton("返回") { vm.remote.keyClick(RcKey.BACK) }
        }
    }
}
