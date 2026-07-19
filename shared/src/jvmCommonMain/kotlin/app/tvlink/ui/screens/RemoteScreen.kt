// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tvlink.proto.ib.IbConst
import app.tvlink.proto.ib.RcKey
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.TvColors
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class RcMode(
    val label: String,
    val ibMode: Int,
) {
    KEYPAD("按键", IbConst.CHANGETYPE_DEFAULT),
    TOUCHPAD("触屏", IbConst.CHANGETYPE_DEFAULT),
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

@Composable
fun RemoteScreen(vm: AppViewModel) {
    var mode by remember { mutableStateOf(RcMode.KEYPAD) }
    var powerConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(TvColors.RcDark).padding(16.dp)) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navBack() }) { Text("‹ 返回", color = TvColors.TextOnDark) }
            Spacer(Modifier.weight(1f))
            Text(vm.connectedName, color = TvColors.TextOnDarkSecondary, style = MaterialTheme.typography.bodySmall)
        }

        // mode switcher
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RcMode.entries.forEach { m ->
                val sel = m == mode
                Text(
                    m.label,
                    color = if (sel) TvColors.AccentStart else TvColors.TextOnDarkSecondary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .background(
                                if (sel) Color(0x3337E8FF) else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            ).clickable {
                                mode = m
                                vm.rc.setMode(m.ibMode)
                            }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
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

        // bottom voice bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            app.tvlink.ui.widgets
                .VoiceButton(onText = { vm.voiceText(it) })
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
    Box(
        modifier.size(size.dp).background(Color(0xFF34343A), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TvColors.TextOnDark, style = MaterialTheme.typography.titleMedium)
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
                .background(Color(0xFF2E2E34), RoundedCornerShape(16.dp))
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
                    .background(TvColors.AccentStart, CircleShape),
            )
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
            .background(Color(0xFF2E2E34), CircleShape)
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
                .background(TvColors.accentBrush, CircleShape),
        )
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
        Text("拖动方向盘区域控制光标，单击为确认", color = TvColors.TextOnDarkSecondary)
        Box(
            Modifier
                .size(220.dp)
                .background(Color(0xFF2E2E34), CircleShape)
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
            Box(Modifier.size(80.dp).background(TvColors.accentBrush, CircleShape))
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
    androidx.compose.runtime.DisposableEffect(Unit) {
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
        Text("🎯", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("体感遥控", style = MaterialTheme.typography.titleLarge, color = TvColors.TextOnDark)
        Text(
            if (app.tvlink.ui.widgets.platformName == "android") {
                "传感器数据正实时发送到电视"
            } else {
                "桌面端无传感器，体感模式仅 Android 可用"
            },
            color = TvColors.TextOnDarkSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RcButton("OK") { vm.keyClick(RcKey.OK) }
            RcButton("返回") { vm.keyClick(RcKey.BACK) }
        }
    }
}
