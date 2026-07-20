package app.tvlink.proto.ib

/** IB (InputBoost) command constants and key map — pure data, shared across platforms. */
object IbConst {
    const val PORT = 3988

    /** IbPacket.IB_PACKET_MAGIC_NUMBER — 287475865, keep the decimal here so the hex can't drift again. */
    const val MAGIC = 0x11228899

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

/**
 * ibVal = IB wire keycode (IbPublic.IbKey.mIbVal; gamepad values resolved from SecExceptionCode
 * constants — NOT standard Linux input codes); androidVal = Android keycode used on the IDC fallback path.
 * needIb313 mirrors IbKey.mNeedCheckIbVer: key uses IB only when server ver >= 313, else IDC fallback.
 */
enum class RcKey(
    val ibVal: Int,
    val androidVal: Int,
    val needIb313: Boolean = false,
) {
    UP(103, 19),
    DOWN(108, 20),
    LEFT(105, 21),
    RIGHT(106, 22),
    OK(28, 23, needIb313 = true),
    BACK(1, 4, needIb313 = true),
    MENU(139, 82, needIb313 = true),
    HOME(172, 3, needIb313 = true),
    POWER(116, 26, needIb313 = true),
    VOL_DOWN(114, 25, needIb313 = true),
    VOL_UP(115, 24, needIb313 = true),
    PAD_A(306, 0),
    PAD_B(305, 0),
    PAD_X(307, 0),
    PAD_Y(304, 0),
    PAD_LT(310, 0),
    PAD_LB(308, 0),
    PAD_RT(311, 0),
    PAD_RB(309, 0),
    PAD_SELECT(296, 0),
    PAD_START(312, 0),
    MAGIC(193, 0),
}
