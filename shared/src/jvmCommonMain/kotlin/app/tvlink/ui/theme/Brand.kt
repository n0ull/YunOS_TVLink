package app.tvlink.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 品牌点缀色（青→紫渐变）。仅用于品牌时刻：logo、搜索脉冲、遥控旋钮/摇杆。 */
object Brand {
    val AccentStart = Color(0xFF37E8FF)
    val AccentEnd = Color(0xFFF586FF)
    val accentBrush = Brush.horizontalGradient(listOf(AccentStart, AccentEnd))
}
