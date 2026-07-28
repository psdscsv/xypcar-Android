package com.psd.xypcar

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.psd.xypcar.remote.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteRelayActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var relayClient: RelayClient
    private var deviceId: String = ""
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvMyId: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRemoteStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etTargetId: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_relay)

        prefs = getSharedPreferences("car_config", MODE_PRIVATE)

        tvMyId = findViewById(R.id.tv_my_id)
        tvStatus = findViewById(R.id.tv_status)
        tvRemoteStatus = findViewById(R.id.tv_remote_status)
        tvLog = findViewById(R.id.tv_log)
        etTargetId = findViewById(R.id.et_target_id)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)

        // 获取用户名（如果设置中填了则使用，否则用 Android ID）
        val username = prefs.getString("remote_username", "")
        deviceId = if (username.isNullOrEmpty()) {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } else {
            username
        }
        tvMyId.text = "本机ID: $deviceId"

        // 读取服务器配置
        val host = prefs.getString("remote_host", "") ?: ""
        val port = prefs.getString("remote_port", "9999")?.toIntOrNull() ?: 9999

        relayClient = RelayClient(
            serverHost = host,
            serverPort = port,
            onMessageReceived = { from, payload ->
                runOnUiThread {
                    appendLog("【收到】 $from: $payload")
                    // 解析 payload 尝试提取位置信息
                    try {
                        val json = JSONObject(payload)
                        when (json.optString("type")) {
                            "status" -> {
                                val lat = json.optDouble("lat")
                                val lng = json.optDouble("lng")
                                val bearing = json.optDouble("bearing")
                                tvRemoteStatus.text = String.format(
                                    "📍 位置: %.6f, %.6f  方向: %.1f°",
                                    lat, lng, bearing
                                )
                            }

                            else -> {
                                // 其他信息显示在日志
                            }
                        }
                    } catch (e: Exception) {
                        // 非JSON或格式不符
                    }
                }
            },
            onStatusChanged = { status ->
                runOnUiThread {
                    tvStatus.text = "状态: $status"
                    appendLog("【状态】 $status")
                    when {
                        status.contains("注册成功") -> {
                            isConnected = true
                            btnConnect.text = "已连接"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = true
                            Toast.makeText(this@RemoteRelayActivity, "连接成功", Toast.LENGTH_SHORT)
                                .show()
                        }

                        status.contains("断开") || status.contains("错误") -> {
                            isConnected = false
                            btnConnect.text = "连接"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                    }
                }
            }
        )

        // 连接按钮
        btnConnect.setOnClickListener {
            if (isConnected) {
                Toast.makeText(this, "已连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (host.isEmpty()) {
                Toast.makeText(this, "请先在设置中配置服务器地址", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnConnect.isEnabled = false
            btnConnect.text = "连接中..."
            CoroutineScope(Dispatchers.IO).launch {
                val success = relayClient.connect(deviceId)
                withContext(Dispatchers.Main) {
                    if (!success) {
                        btnConnect.text = "连接"
                        btnConnect.isEnabled = true
                        Toast.makeText(this@RemoteRelayActivity, "连接失败", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }

        btnDisconnect.setOnClickListener {
            if (isConnected) {
                relayClient.disconnect()
                isConnected = false
                btnConnect.text = "连接"
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                tvStatus.text = "状态: 已断开"
                appendLog("【系统】 已断开")
                Toast.makeText(this, "已断开", Toast.LENGTH_SHORT).show()
            }
        }

        // 遥控按钮
        findViewById<Button>(R.id.btn_forward).setOnClickListener { sendRemoteCmd("remote", mapOf("speed" to 1.5, "turn" to 0f)) }
        findViewById<Button>(R.id.btn_backward).setOnClickListener { sendRemoteCmd("remote", mapOf("speed" to -1.5, "turn" to 0f)) }
        findViewById<Button>(R.id.btn_left).setOnClickListener { sendRemoteCmd("remote", mapOf("speed" to -1.5f, "turn" to -0.5)) }
        findViewById<Button>(R.id.btn_right).setOnClickListener { sendRemoteCmd("remote", mapOf("speed" to 0f, "turn" to 0.5)) }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { sendRemoteCmd("remote", mapOf("speed" to 0f, "turn" to 0f)) }
        findViewById<Button>(R.id.btn_start_auto).setOnClickListener { sendRemoteCmd("start_auto", emptyMap()) }
        findViewById<Button>(R.id.btn_stop_auto).setOnClickListener { sendRemoteCmd("stop_auto", emptyMap()) }
    }

    private fun sendRemoteCmd(cmd: String, params: Map<String, Any>) {
        if (!isConnected) {
            Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
            return
        }
        val target = etTargetId.text.toString().trim()
        if (target.isEmpty()) {
            Toast.makeText(this, "请输入目标设备ID", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = JSONObject().apply {
            put("type", cmd)
            params.forEach { put(it.key, it.value) }
        }.toString()
        CoroutineScope(Dispatchers.IO).launch {
            val success = relayClient.sendMessage(target, payload)
            withContext(Dispatchers.Main) {
                if (success) {
                    appendLog("【发送】 到 $target: $cmd")
                } else {
                    Toast.makeText(this@RemoteRelayActivity, "发送失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun appendLog(text: String) {
        tvLog.append("$text\n")
        // 自动滚动到底部
        (tvLog.parent as? View)?.post {
            (tvLog.parent as? View)?.scrollTo(0, (tvLog.parent as? View)?.height ?: 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        relayClient.disconnect()
        handler.removeCallbacksAndMessages(null)
    }
}