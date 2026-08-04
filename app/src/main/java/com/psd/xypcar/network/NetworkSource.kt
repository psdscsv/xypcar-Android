package com.psd.xypcar.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.DataInputStream
import java.io.PrintWriter
import java.net.Socket

class NetworkSource(
    private val ip: String,
    private val port: Int = 8080
) {
    private var socket: Socket? = null
    private var receiveThread: Thread? = null
    @Volatile private var running = false
    private var onFrame: ((Bitmap) -> Unit)? = null

    fun start(onFrame: (Bitmap) -> Unit): Boolean {
        if (running) return false
        this.onFrame = onFrame
        receiveThread = Thread {
            try {
                socket = Socket(ip, port)
                val output = PrintWriter(socket!!.getOutputStream(), true)
                output.println("start")
                running = true
                receiveLoop(socket!!)
            } catch (e: Exception) {
                Log.e("NetworkSource", "Connection failed", e)
                running = false
            }
        }
        receiveThread?.start()
        return true
    }

    fun stop() {
        running = false
        try {
            socket?.let {
                val output = PrintWriter(it.getOutputStream(), true)
                output.println("stop")
                it.close()
            }
        } catch (e: Exception) {
            Log.e("NetworkSource", "Stop error", e)
        }
        socket = null
        receiveThread?.interrupt()
        receiveThread = null
        onFrame = null
    }

    val isRunning: Boolean get() = running

    private fun receiveLoop(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        while (running && !socket.isClosed) {
            try {
                val len = input.readInt()
                if (len <= 0 || len > 500_000) {
                    Log.w("NetworkSource", "Invalid frame length: $len")
                    continue
                }
                val data = ByteArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(data, read, len - read)
                    if (r < 0) break
                    read += r
                }
                if (read == len) {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, len)
                    bitmap?.let { onFrame?.invoke(it) }
                }
            } catch (e: Exception) {
                Log.e("NetworkSource", "Receive error", e)
                break
            }
        }
        try { socket.close() } catch (_: Exception) {}
        running = false
    }
}