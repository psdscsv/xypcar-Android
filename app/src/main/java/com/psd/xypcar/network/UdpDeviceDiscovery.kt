package com.psd.xypcar.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * UDP 广播发现工具，用于在局域网内搜索发送广播的设备（如 ESP32）
 */
object UdpDeviceDiscovery {

    private const val TAG = "UdpDiscovery"

    /**
     * 同步发现设备 IP，阻塞当前线程直到发现或超时
     * @param port 监听端口，应与设备广播端口一致，默认 8888
     * @param timeoutMs 超时时间（毫秒），默认 5000
     * @return 发现设备的 IP 地址字符串，若超时或出错返回 null
     */
    fun discover(port: Int = 8888, timeoutMs: Long = 5000): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(port).apply {
                soTimeout = 1000  // 每次接收超时 1 秒，用于循环检查总超时
            }
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    Log.d(TAG, "收到广播：$msg")
                    val ip = extractIpFromMessage(msg)
                    if (ip != null) {
                        Log.i(TAG, "发现设备 IP：$ip")
                        return ip
                    }
                } catch (e: SocketTimeoutException) {
                    // 单次接收超时，继续循环
                }
            }
            Log.w(TAG, "UDP 发现超时")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "UDP 发现异常", e)
            return null
        } finally {
            socket?.close()
        }
    }

    /**
     * 从广播消息中提取 IP 地址
     * 支持格式："ESP32_CAM IP=192.168.43.10 TCP=8080"
     */
    private fun extractIpFromMessage(message: String): String? {
        val pattern = Regex("""IP=(\d+\.\d+\.\d+\.\d+)""")
        return pattern.find(message)?.groupValues?.get(1)
    }
}