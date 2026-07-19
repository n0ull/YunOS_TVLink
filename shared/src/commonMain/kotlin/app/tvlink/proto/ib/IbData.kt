package app.tvlink.proto.ib

/** IB (InputBoost) command constants and key map — pure data, shared across platforms. */
object IbConst {
    const val PORT = 3988
    const val MAGIC = 0x11223399

    const val REQ_KEEPALIVE = 0
    const val REQ_HELLO = 1
    const val REQ_MODULEINFO = 8
    const val PROTO_G_SENSOR = 257
    const val PROTO_GYRO_SENSOR = 260
    const val PROTO_MOUSE = 263
    const val PROTO_MULTITOUCH = 272
    const val PROTO_CURRENTAPP = 274
    const val REQ_CHANGETYPE = 280
    const val PROTO_JOYSTICK = 296

    const val RSP_MASK = 0x10000000

    const val CHANGETYPE_MOTIONPAD = 0
    const val CHANGETYPE_JOYSTICK = 1
    const val CHANGETYPE_DEFAULT = 32

    const val EV_KEY = 1
    const val EV_REL = 2
    const val BTN_LEFT = 272
}

/** ibVal = Linux input-event code; androidVal = Android keycode used on the IDC fallback path. */
enum class RcKey(val ibVal: Int, val androidVal: Int, val needIb313: Boolean = false) {
    UP(103, 19),
    DOWN(108, 20),
    LEFT(105, 21),
    RIGHT(106, 22),
    OK(28, 23),
    BACK(1, 4),
    MENU(139, 82),
    HOME(172, 3),
    POWER(116, 26, needIb313 = true),
    VOL_DOWN(114, 25),
    VOL_UP(115, 24),
    PAD_A(305, 0),
    PAD_B(304, 0),
    PAD_X(307, 0),
    PAD_Y(308, 0),
    PAD_LT(303, 0),
    PAD_LB(310, 0),
    PAD_RT(311, 0),
    PAD_RB(309, 0),
    PAD_SELECT(296, 0),
    PAD_START(306, 0),
    MAGIC(193, 0),
}
