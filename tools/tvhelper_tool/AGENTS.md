<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# tvhelper_tool

## Purpose

**参考工具(REFERENCE TOOLING)——不被本仓库 Gradle 构建编译**。源码亦不在本目录,
位于姊妹研究目录 `D:\n0ull\Desktop\1\Java\tvhelper\tvhelper_tool`(旧逆向仓库,
见根 `TODO.md`「工具」一节);本仓库此处仅为文档锚点。

逆向自 `阿里TV助手_5.2.2.apk` 的纯 Python 标准库实现(socket / struct / json / uuid,
零第三方依赖),局域网直连 YunOS 电视:遥控按键、鼠标/触摸板、手柄摇杆、投屏
均已真机实测可用,是 TVLink Kotlin 协议实现的行为对照基准。

## Key Files

以下文件均位于 `D:\n0ull\Desktop\1\Java\tvhelper\tvhelper_tool\`:

| File | Description |
|------|-------------|
| `tvremote.py` | CLI 入口:`python tvremote.py scan\|send <ip> HOME\|shell\|mouse\|click\|stick\|proj\|proj-info\|proj-video\|shot` |
| `tv_protocol.py` | IB 协议常量、KEYCODE 键表、IbPacket 封包(20B 大端头:magic/size/type/reserve/checksum,magic=287475865) |
| `discover.py` | mDNS 发现(`_alitv_remote_control._tcp.local`,224.0.0.251:5353)+ TCP 3988 端口探测兜底 |
| `remote.py` | IB 遥控客户端(TCP 3988;HELLO type=1 → rsp 268435457 取 ver/sid;按键 type=263、手柄 296、心跳 15s) |
| `projection.py` | 投屏 HTTP 客户端(TV 侧 13521 端口:`/server-info`、`POST /setmedia`、`PUT /image`、播放控制) |
| `idc.py` | IDC 私有通道(TCP 13510,16B 帧头 magic=130311):无加密登录成功;截屏命令被 TV 断开(工具内原始状态) |
| `extract_idc_key.js` | frida 脚本:hook 原 App 内存中的 AES 会话密钥/secguard 种子,注入 `idc.py` 可复现截屏 |
| `README.md` | 工具原理与用法(中文) |

## For AI Agents

### Working In This Directory

- **只读参考**:改 TVLink 协议实现时以此工具真机验证过的行为为基准;勿往本目录加文件,
  勿并入 Gradle 构建
- ⚠ `tv_protocol.py` 注释中的十六进制魔数 `0x11223359` 是**错的**,十进制
  `287475865`(= `0x11228899`)才对(与 TVLink 提交 `a116245` 一致;Go 版 tvhelper2
  有同类转写错误)——移植任何常量以十进制值为准
- 投屏端口经 IDC 登录下发(本机实测 13521,13520 关闭);截屏「加密墙」已在 TVLink
  证伪(无加密 + 正确 connKey + 正确帧格式即出图,见根 `TODO.md` 真机档案),
  `idc.py` 的失败提示仅代表该工具未更新

## Dependencies

### External

- Python 3 标准库(无第三方依赖);`extract_idc_key.js` 需 frida(仅逆向期一次性使用)

<!-- MANUAL: -->
