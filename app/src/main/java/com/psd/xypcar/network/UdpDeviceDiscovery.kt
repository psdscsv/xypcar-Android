package com.psd.xypcar.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

object UdpDeviceDiscovery {
    private const val TAG = "UdpDiscovery"

    fun discover(port: Int = 8888, timeoutMs: Long = 5000): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(port).apply {
                soTimeout = 1000
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
                } catch (_: SocketTimeoutException) {
                    // continue
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

    private fun extractIpFromMessage(message: String): String? {
        val pattern = Regex("""IP=(\d+\.\d+\.\d+\.\d+)""")
        return pattern.find(message)?.groupValues?.get(1)
    }
}