// BLEController.kt
package com.psd.xypcar.control

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BLEController(private val context: Context) {
    companion object {
        private const val TAG = "BLEController"
        private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CHAR_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
    }
    var targetDeviceName: String = "ESP32_Car"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false

    // 扫描回调
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == targetDeviceName) {
                Log.i(TAG, "找到目标设备: ${device.address}")
                stopScan()
                connect(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败: $errorCode")
            isScanning = false
            scanListener?.onScanFailed("扫描错误: $errorCode")
        }
    }

    // GATT 回调
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "连接成功，开始发现服务")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "连接断开")
                    bluetoothGatt = null
                    controlCharacteristic = null
                    connectionListener?.onDisconnected()
                }
            } else {
                Log.e(TAG, "连接状态错误: $status")
                connectionListener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)

                if (service != null) {
                    controlCharacteristic = service.getCharacteristic(CHAR_UUID)
                    if (controlCharacteristic != null) {
                        gatt.requestMtu(64)   // 请求 64 字节 MTU
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        Log.i(TAG, "已请求高优先级连接")
                        connectionListener?.onConnected()
                        return
                    }
                }
                Log.e(TAG, "未找到服务或特征")
                disconnect()
            } else {
                Log.e(TAG, "发现服务失败")
                disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "写入失败: $status")
            }
        }
    }

    // ========== 监听器接口 ==========
    interface ScanListener {
        fun onDeviceFound(device: BluetoothDevice)
        fun onScanFailed(message: String)
    }
    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }

    private var scanListener: ScanListener? = null
    private var connectionListener: ConnectionListener? = null

    fun setScanListener(listener: ScanListener?) { this.scanListener = listener }
    fun setConnectionListener(listener: ConnectionListener?) { this.connectionListener = listener }

    // ========== 初始化 ==========
    init {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "设备不支持蓝牙")
        } else {
            bluetoothAdapter = adapter
        }
    }

    fun isBleSupported(): Boolean = bluetoothAdapter != null

    // ========== 扫描 ==========
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (isScanning) return
        if (!adapter.isEnabled) {
            scanListener?.onScanFailed("请先开启蓝牙")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        scanner?.startScan(scanCallback)
        isScanning = true
        Log.i(TAG, "开始扫描，目标设备: $targetDeviceName")
    }

    fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.i(TAG, "停止扫描")
    }

    // ========== 连接 ==========
    fun connect(device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            Log.w(TAG, "已有连接，断开旧连接")
            disconnect()
        }
        // 参数 autoConnect = false 表示主动连接
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.i(TAG, "正在连接: ${device.address}")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    fun isConnected(): Boolean = controlCharacteristic != null

    // ========== 发送控制数据包 (核心) ==========
    /**
     * @param speed 目标线速度 (m/s)，范围 -2.0 ~ 2.0 (对应 int16 -100 ~ 100)
     * @param turn  目标转向角速度 (°/s)，范围 -32768 ~ 32767
     * @param stop  是否紧急停止 (true: 发送 stop=1)
     */
    fun sendControl(speed: Float, turn: Float, stop: Boolean = false) {
        val char = controlCharacteristic ?: run {
            Log.w(TAG, "未连接，无法发送")
            return
        }

        // 限制速度范围，并转换为 int (放大50倍)
        val speedClamped = speed.coerceIn(-2.0f, 2.0f)
        val speedInt = (speedClamped * 50).toInt().coerceIn(-32768, 32767)

        val turnInt = turn.toInt().coerceIn(-32768, 32767)
        val stopInt = if (stop) 1 else 0

        // 分配 25 字节，小端序
        val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(0xA5.toByte())          // 0: header
        buffer.putShort(speedInt.toShort()) // 1-2: speed
        buffer.putShort(turnInt.toShort())  // 3-4: turn
        buffer.putShort(stopInt.toShort())  // 5-6: stop
        buffer.putFloat(0f)                // 7-10: turn_gain (暂不变)
        buffer.putFloat(0f)                // 11-14: speed_kp (暂不变)
        buffer.putFloat(0f)                // 15-18: speed_ki
        buffer.putFloat(0f)                // 19-22: speed_kd

        val payload = buffer.array()
        // 计算校验和 (字节1 ~ 22)
        var checksum = 0
        for (i in 1..22) {
            checksum += payload[i].toInt() and 0xFF
        }
        buffer.put(checksum.toByte())      // 23: checksum
        buffer.put(0x5A.toByte())          // 24: footer

        val data = buffer.array()
        char.value = data
        bluetoothGatt?.writeCharacteristic(char)

    }
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}