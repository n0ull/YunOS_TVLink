<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-07-22 | Updated: 2026-07-22 -->

# dongle

## Purpose

MagicCast dongle BLE provisioning (Android-only). Scans for the dongle, opens a GATT connection, and writes the
target WiFi SSID/password to GATT characteristics so the dongle can join the network. Wire format and UUIDs
reverse-engineered in `docs/re/03-dongle-ble-asr.md`.

## Key Files

| File                  | Description                                                                                          |
|-----------------------|------------------------------------------------------------------------------------------------------|
| `DongleBlePairer.kt`  | GATT provisioning — scan/connect, write SSID(`CHR_SSID`)/password(`CHR_PASSWORD`) **明文**,18 字节分片(`CHUNK=18`),监听安全特征(`CHR_SECURITY`)通知拿配网结果; 顶层 `currentSsid(context)` 读取当前 WiFi SSID |

## For AI Agents

### Working In This Directory

- minSdk 21:4 参 `connectGatt`(指定 `TRANSPORT_LE`)需 API 23+,低版本退化为 3 参重载(见 `Build.VERSION` 分支,lint NewApi 强制)
- Credentials 写入为明文 UTF-8,按 18 字节分片,`onCharacteristicWrite` 回调驱动写入队列
- 需要定位/蓝牙权限,声明在 androidApp manifest; UI 层用 `@SuppressLint("MissingPermission")`
- GATT UUIDs 与写入格式见 `docs/re/03-dongle-ble-asr.md`,改动前先对照
- 由 `ui/widgets/DongleScreen.android.kt` 调用,本目录不含 UI

### Testing Requirements

- BLE 无法单元测试 — 用 `./gradlew :androidApp:assembleDebug` 验证编译,真机验证配网

## Dependencies

### Internal

- `app.tvlink.ui.widgets.DongleScreen`(androidMain)— 唯一调用方

### External

- android.bluetooth (BluetoothGatt, BluetoothGattCharacteristic, BluetoothDevice)
- android.net.wifi (WifiManager, currentSsid)

<!-- MANUAL: -->
