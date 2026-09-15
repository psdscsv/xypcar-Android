// BLEController.kt
package com.psd.xypcar.control

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BLEController(private val context: Context) {
    companion object {
        private const val TAG = "BLEController"
        private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CHAR_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        // 标准 CCC 描述符 (0x2902)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        // 断开兜底超时
        private const val DISCONNECT_TIMEOUT_MS = 2000L
    }

    var targetDeviceName: String = "ESP32_Car"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false

    /** 只有 MTU → CCC 全部就绪后才为 true，此时才能写特征 */
    @Volatile
    private var notifyEnabled = false

    private val handler = Handler(Looper.getMainLooper())

    // ============ 监听器接口 ============
    interface ScanListener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFailed(message: String)
    }

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }

    /** 收到小车发来的字符串（如 "L:0.52,R:0.48"） */
    interface DataListener {
        fun onDataReceived(text: String)
    }

    private var scanListener: ScanListener? = null
    private var connectionListener: ConnectionListener? = null
    private var dataListener: DataListener? = null

    fun setScanListener(listener: ScanListener?) {
        this.scanListener = listener
    }

    fun setConnectionListener(listener: ConnectionListener?) {
        this.connectionListener = listener
    }

    fun setDataListener(listener: DataListener?) {
        this.dataListener = listener
    }

    // ============ 扫描回调 ============
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try {
                device.name
            } catch (e: SecurityException) {
                null
            }
            Log.d(TAG, "扫描到: ${device.address}  name=$name  rssi=${result.rssi}")

            if (name == targetDeviceName) {
                Log.i(TAG, "找到目标设备: ${device.address}")
                stopScan()
                scanListener?.onDeviceFound(device)
                connect(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败: $errorCode")
            isScanning = false
            scanListener?.onScanFailed("扫描错误: $errorCode")
        }
    }

    // ============ GATT 回调 ============
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: status=$status newState=$newState")

            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                Log.i(TAG, "连接成功，开始发现服务")
                notifyEnabled = false
                try {
                    gatt.discoverServices()
                } catch (e: Exception) {
                    Log.e(TAG, "discoverServices 异常", e)
                    gatt.close()
                    cleanup()
                    connectionListener?.onDisconnected()
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "连接断开 (status=$status)")
                try {
                    gatt.close()
                } catch (_: Exception) {}
                cleanup()
                connectionListener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "发现服务失败: status=$status")
                // 主动断，回调里会 close
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "找不到服务 $SERVICE_UUID")
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            controlCharacteristic = service.getCharacteristic(CHAR_UUID)
            if (controlCharacteristic == null) {
                Log.e(TAG, "找不到特征 $CHAR_UUID")
                try { gatt.disconnect() } catch (_: Exception) {}
                return
            }
            Log.i(TAG, "服务/特征已找到，请求 MTU")
            // ★ 只发一个 GATT 操作：MTU。后面的操作都在 onMtuChanged 里排队
            val ok = gatt.requestMtu(64)
            if (!ok) {
                // requestMtu 直接失败（极少），退回：直接启用通知
                Log.w(TAG, "requestMtu 立即失败，直接启用通知")
                enableNotification(gatt, controlCharacteristic!!)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status")
            val char = controlCharacteristic ?: return
            // ★ MTU 完成后再启用通知
            enableNotification(gatt, char)
        }

        // ---------- 旧 API：Android 12 及以下 ----------
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                val value = characteristic.value ?: return
                handleReceivedBytes(value)
            }
        }

        // ---------- 新 API：Android 13+ ----------
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                handleReceivedBytes(value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (descriptor.uuid != CCCD_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "通知订阅成功，GATT 就绪")
                notifyEnabled = true
                // ★ 只有到这里才算真正"可写"，通知上层
                connectionListener?.onConnected()
                // 请求连接优先级（不占 GATT 写队列，是连接参数更新）
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Exception) {}
            } else {
                Log.e(TAG, "通知订阅失败: status=$status")
                // 失败也断开，避免卡在不明状态
                try { gatt.disconnect() } catch (_: Exception) {}
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite 失败: status=$status")
            }
        }
    }

    // ============ 启用通知 ============
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        Log.i(TAG, "setCharacteristicNotification = $ok")

        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "找不到 CCCD 描述符（0x2902），跳过订阅，直接认为就绪")
            notifyEnabled = true
            connectionListener?.onConnected()
            return
        }

        val result: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        Log.i(TAG, "writeDescriptor = $result")
    }

    // ============ 收到字节 → 字符串 → 回调 ============
    private fun handleReceivedBytes(value: ByteArray) {
        if (value.isEmpty()) return
        val text = String(value, Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        Log.d(TAG, "收到数据: $text")
        handler.post { dataListener?.onDataReceived(text) }
    }

    // ============ 初始化 ============
    init {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "设备不支持蓝牙")
        } else {
            bluetoothAdapter = adapter
        }
    }

    fun isBleSupported(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // ============ 扫描 ============
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (isScanning) return
        if (!adapter.isEnabled) {
            scanListener?.onScanFailed("请先开启蓝牙")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scanListener?.onScanFailed("BLE 扫描器不可用")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            Log.i(TAG, "开始扫描，目标设备: $targetDeviceName")
        } catch (e: Exception) {
            Log.e(TAG, "startScan 异常", e)
            scanListener?.onScanFailed("扫描失败: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
        Log.i(TAG, "停止扫描")
    }

    // ============ 连接 ============
    fun connect(device: BluetoothDevice) {
        // 先清理旧连接
        if (bluetoothGatt != null) {
            Log.w(TAG, "已有连接，先清理")
            try { bluetoothGatt?.close() } catch (_: Exception) {}
            cleanup()
        }
        notifyEnabled = false
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.i(TAG, "正在连接: ${device.address}")
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt 异常", e)
            cleanup()
        }
    }

    /**
     * 主动断开：
     *  - 只发 disconnect，close 放到 onConnectionStateChange 回调
     *  - 2 秒内未回调则强制 close
     */
    fun disconnect() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            cleanup()
            return
        }
        try {
            gatt.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect 异常", e)
        }
        // 兜底：如果 2s 内没收到断连回调，强制清理
        handler.postDelayed({
            if (bluetoothGatt === gatt) {
                Log.w(TAG, "断开超时，强制 close")
                try { gatt.close() } catch (_: Exception) {}
                cleanup()
                connectionListener?.onDisconnected()
            }
        }, DISCONNECT_TIMEOUT_MS)
    }

    private fun cleanup() {
        bluetoothGatt = null
        controlCharacteristic = null
        notifyEnabled = false
    }

    /** 只有 GATT 完全就绪（MTU + CCC 订阅完成）才算真正可用 */
    fun isConnected(): Boolean = controlCharacteristic != null && notifyEnabled

    // ============ 发送控制数据包 ============
    /**
     * @param speed 目标线速度 (m/s)，范围 -2.0 ~ 2.0
     * @param turn  目标转向角速度 (°/s)
     * @param stop  是否紧急停止
     */
    fun sendControl(speed: Float, turn: Float, stop: Boolean = false) {
        val char = controlCharacteristic
        if (char == null) {
            Log.w(TAG, "未连接，无法发送")
            return
        }
        if (!notifyEnabled) {
            // GATT 还没就绪，直接丢弃，避免污染队列
            Log.w(TAG, "GATT 未就绪，丢弃本次发送")
            return
        }

        val speedClamped = speed.coerceIn(-2.0f, 2.0f)
        val speedInt = (speedClamped * 50).toInt().coerceIn(-32768, 32767)
        val turnInt = turn.toInt().coerceIn(-32768, 32767)
        val stopInt = if (stop) 1 else 0

        val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0xA5.toByte())               // [0] header
        buffer.putShort(speedInt.toShort())     // [1..2] speed
        buffer.putShort(turnInt.toShort())      // [3..4] turn
        buffer.putShort(stopInt.toShort())      // [5..6] stop
        buffer.putFloat(0f)                     // [7..10] turn_gain
        buffer.putFloat(0f)                     // [11..14] speed_kp
        buffer.putFloat(0f)                     // [15..18] speed_ki
        buffer.putFloat(0f)                     // [19..22] speed_kd

        val payload = buffer.array()
        var checksum = 0
        for (i in 1..22) checksum += payload[i].toInt() and 0xFF
        buffer.put(checksum.toByte())           // [23] checksum
        buffer.put(0x5A.toByte())               // [24] footer

        val data = buffer.array()

        val writeOk: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                char, data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(char) == true
        }

        if (!writeOk) {
            Log.w(TAG, "sendControl 写入失败")
        }
    }
}