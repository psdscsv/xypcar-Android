package com.psd.xypcar.remote

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class RelayClient(
    private val serverHost: String,
    private val serverPort: Int = 9999,
    private val onMessageReceived: (String, String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isRunning = false
    private var deviceId: String = ""
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var registered = false

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 25000L  // 25秒
    }

    suspend fun connect(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            this@RelayClient.deviceId = deviceId
            socket = Socket(serverHost, serverPort)
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            isRunning = true
            registered = false

            // 启动接收协程
            readJob = CoroutineScope(Dispatchers.IO).launch {
                readLoop()
            }

            // 发送注册请求
            val registerCmd = JSONObject().apply {
                put("cmd", "register")
                put("id", deviceId)
            }
            sendCommand(registerCmd)

            // 等待注册确认（最多5秒）
            var attempts = 0
            while (!registered && attempts < 50) {
                delay(100)
                attempts++
            }

            if (registered) {
                // 注册成功后启动心跳
                startHeartbeat()
                withContext(Dispatchers.Main) {
                    onStatusChanged("注册成功")
                }
                true
            } else {
                withContext(Dispatchers.Main) {
                    onStatusChanged("注册超时")
                }
                disconnect()
                false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onStatusChanged("连接失败: ${e.message}")
            }
            Log.e("RelayClient", "连接异常", e)
            false
        }
    }

    // ---------- 新增心跳协程 ----------
    private fun startHeartbeat() {
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning && isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isRunning) {
                    try {
                        val pingCmd = JSONObject().apply {
                            put("cmd", "ping")
                        }
                        sendCommand(pingCmd)
                        Log.d("RelayClient", "发送心跳 ping")
                    } catch (e: Exception) {
                        Log.w("RelayClient", "心跳发送失败", e)
                        // 如果心跳失败，可能连接已断，主动断开
                        if (isRunning) {
                            withContext(Dispatchers.Main) {
                                onStatusChanged("心跳失败，连接断开")
                            }
                            disconnect()
                        }
                        break
                    }
                }
            }
        }
    }

    private suspend fun readLoop() {
        while (isRunning) {
            try {
                val line = reader?.readLine() ?: break
                if (line.isEmpty()) continue
                val json = JSONObject(line)
                Log.d("RelayClient", "收到: $line")

                if (json.has("from")) {
                    val from = json.getString("from")
                    val payload = json.getString("payload")
                    withContext(Dispatchers.Main) {
                        onMessageReceived(from, payload)
                    }
                } else if (json.has("status")) {
                    val status = json.getString("status")
                    val msg = json.optString("msg")
                    when (status) {
                        "pong" -> {
                            // 心跳响应，静默处理
                            Log.d("RelayClient", "收到 pong")
                        }
                        "ok" -> {
                            when (msg) {
                                "registered" -> {
                                    registered = true
                                    withContext(Dispatchers.Main) {
                                        onStatusChanged("注册成功")
                                    }
                                }
                                "forwarded" -> {
                                    withContext(Dispatchers.Main) {
                                        onStatusChanged("消息已转发")
                                    }
                                }
                            }
                        }
                        "error" -> {
                            withContext(Dispatchers.Main) {
                                onStatusChanged("服务器错误: $msg")
                            }
                        }
                    }
                } else {
                    Log.d("RelayClient", "收到未识别消息: $line")
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("RelayClient", "接收循环异常", e)
                    withContext(Dispatchers.Main) {
                        onStatusChanged("接收异常: ${e.message}")
                    }
                }
                break
            }
        }
        if (isRunning) {
            withContext(Dispatchers.Main) {
                onStatusChanged("与服务器断开连接")
            }
        }
    }

    suspend fun sendMessage(target: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = JSONObject().apply {
                put("cmd", "send")
                put("target", target)
                put("payload", payload)
            }
            sendCommand(cmd)
            Log.d("RelayClient", "发送: $cmd")
            true
        } catch (e: Exception) {
            Log.e("RelayClient", "发送异常", e)
            withContext(Dispatchers.Main) {
                onStatusChanged("发送失败: ${e.message}")
            }
            false
        }
    }

    private suspend fun sendCommand(cmd: JSONObject) = withContext(Dispatchers.IO) {
        try {
            writer?.println(cmd.toString())
            writer?.flush()
        } catch (e: Exception) {
            Log.e("RelayClient", "发送命令异常", e)
            // 如果写失败，说明连接断开了
            if (isRunning) {
                isRunning = false
                withContext(Dispatchers.Main) {
                    onStatusChanged("连接已断开")
                }
            }
            throw e
        }
    }

    fun disconnect() {
        isRunning = false
        readJob?.cancel()
        heartbeatJob?.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e("RelayClient", "关闭异常", e)
        }
        socket = null
        writer = null
        reader = null
        registered = false
        Log.d("RelayClient", "已完全断开")
    }

    fun isConnected(): Boolean = isRunning && registered
}