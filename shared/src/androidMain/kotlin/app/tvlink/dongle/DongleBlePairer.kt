package app.tvlink.dongle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * MagicCast dongle BLE provisioning — service/characteristic UUIDs and write
 * format per docs/re/03. Credentials are written in 18-byte chunks, plaintext,
 * exactly as the protocol requires (device has no auth by design).
 */
@SuppressLint("MissingPermission")
class DongleBlePairer(private val context: Context) {

    companion object {
        val SVC_UUID: UUID = UUID.fromString("00006287-3c17-d293-8e48-14fe2e4da212")
        val CHR_PASSWORD: UUID = UUID.fromString("f000aa21-0451-4000-b000-000000000000")
        val CHR_SSID: UUID = UUID.fromString("f000aa22-0451-4000-b000-000000000000")
        val CHR_SECURITY: UUID = UUID.fromString("f000aa23-0451-4000-b000-000000000000")
        val ADV_UUID: UUID = UUID.fromString("0000b81d-0000-1000-8000-00805f9b34fb")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME_PREFIX = "MagicCast"
        const val SCAN_TIMEOUT_MS = 40_000L
        const val CHUNK = 18
    }

    enum class Phase { IDLE, SCANNING, CONNECTING, WRITING, SUCCESS, FAILED }

    var onPhase: ((Phase, String) -> Unit)? = null
    var onFound: ((BluetoothDevice) -> Unit)? = null

    /** Runtime permissions required for BLE scan + connect on this Android version. */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionNames(): String = requiredPermissions().joinToString(", ") { it.substringAfterLast('.') }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val writeQueue = ArrayDeque<Pair<UUID, ByteArray>>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: return
            if (name.startsWith(NAME_PREFIX)) onFound?.invoke(dev)
        }
        override fun onScanFailed(errorCode: Int) = phase(Phase.FAILED, "扫描失败 ($errorCode)")
    }

    fun startScan() {
        if (!hasPermissions()) {
            phase(Phase.FAILED, "缺少权限: ${permissionNames()}")
            return
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = mgr.adapter?.bluetoothLeScanner
        if (scanner == null) {
            phase(Phase.FAILED, "蓝牙不可用")
            return
        }
        phase(Phase.SCANNING, "正在扫描 MagicCast 设备…")
        scanner?.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (gatt == null) phase(Phase.IDLE, "扫描结束")
    }

    /** security: 0=OPEN 1=WEP 2=PSK 3=EAP; hotelMode: 0/1 */
    fun pair(device: BluetoothDevice, ssid: String, password: String, security: Int = 2, hotelMode: Int = 0) {
        if (!hasPermissions()) {
            phase(Phase.FAILED, "缺少权限: ${permissionNames()}")
            return
        }
        stopScan()
        phase(Phase.CONNECTING, "连接 ${device.name ?: device.address}…")
        // flatten all writes into 18-byte chunks; onCharacteristicWrite drives the queue
        writeQueue.clear()
        for ((uuid, data) in listOf(
            CHR_PASSWORD to password.toByteArray(Charsets.UTF_8),
            CHR_SSID to ssid.toByteArray(Charsets.UTF_8),
            CHR_SECURITY to "$security;$hotelMode".toByteArray(Charsets.UTF_8),
        )) {
            if (data.isEmpty()) {
                writeQueue.add(uuid to ByteArray(0))
            } else {
                var offset = 0
                while (offset < data.size) {
                    val end = minOf(offset + CHUNK, data.size)
                    writeQueue.add(uuid to data.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                phase(Phase.FAILED, "连接失败 ($status)")
                close()
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                handler.postDelayed({ g.discoverServices() }, 500)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                phase(Phase.FAILED, "连接断开")
                close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC_UUID)
            if (svc == null) {
                phase(Phase.FAILED, "未找到配网服务")
                close()
                return
            }
            // enable notify on security characteristic for the result
            val chr = svc.getCharacteristic(CHR_SECURITY)
            if (chr != null) {
                g.setCharacteristicNotification(chr, true)
                chr.getDescriptor(CCC_UUID)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }
            phase(Phase.WRITING, "写入配网信息…")
            writeNext(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeNext(g)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val text = characteristic.value?.let { String(it, Charsets.UTF_8) } ?: return
            if (text == "success") {
                phase(Phase.SUCCESS, "配网成功")
                close()
            } else {
                phase(Phase.FAILED, "设备返回: $text")
                close()
            }
        }
    }

    private fun writeNext(g: BluetoothGatt) {
        val next = writeQueue.poll()
        if (next == null) {
            phase(Phase.WRITING, "等待设备确认…")
            return
        }
        val chr = g.getService(SVC_UUID)?.getCharacteristic(next.first) ?: run {
            phase(Phase.FAILED, "配网特征不可用")
            close()
            return
        }
        chr.value = next.second
        chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (!g.writeCharacteristic(chr)) {
            phase(Phase.FAILED, "写入失败")
            close()
        }
    }

    fun close() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun phase(p: Phase, msg: String) {
        handler.post { onPhase?.invoke(p, msg) }
    }
}

@SuppressLint("MissingPermission")
fun currentSsid(context: Context): String {
    return try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
    } catch (e: Exception) {
        ""
    }
}
