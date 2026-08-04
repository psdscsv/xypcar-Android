package com.psd.xypcar

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.psd.xypcar.control.BLEController
import com.psd.xypcar.control.JoystickView
import com.psd.xypcar.network.UdpDeviceDiscovery
import com.psd.xypcar.network.NetworkSource
import java.util.Locale
import android.content.Intent
import android.widget.ImageButton
import android.view.View

class RemoteControlActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var maxSpeed = 2.2f
    private var maxTurn = 50f
    private lateinit var bleController: BLEController
    private lateinit var leftJoystick: JoystickView
    private lateinit var rightJoystick: JoystickView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var settingBtn: ImageButton
    private lateinit var valueDisplay: TextView

    // 图传相关
    private lateinit var ivVideoBackground: ImageView
    private var videoSource: NetworkSource? = null
    private var isVideoEnabled = false
    private var isVideoRunning = false
    private val videoHandler = Handler(Looper.getMainLooper())

    private val handler = Handler(Looper.getMainLooper())
    private var leftSpeed = 0f
    private var rightTurn = 0f
    private var lastSendTime = 0L
    private val sendIntervalMs = 20L
    private var isReadyToSend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_remote_control)

        ivVideoBackground = findViewById(R.id.iv_video_background)

        leftJoystick = findViewById(R.id.left_joystick)
        rightJoystick = findViewById(R.id.right_joystick)
        statusText = findViewById(R.id.status_text)
        connectBtn = findViewById(R.id.connect_btn)
        disconnectBtn = findViewById(R.id.disconnect_btn)
        valueDisplay = findViewById(R.id.value_display)
        resetBtn = findViewById(R.id.reset_btn)
        settingBtn = findViewById(R.id.btn_settings)
        bleController = BLEController(this)

        if (!checkPermissions()) {
            requestPermissions()
        }

        bleController.setConnectionListener(object : BLEController.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusText.text = "✅ 已连接"
                    statusText.setTextColor(ContextCompat.getColor(this@RemoteControlActivity, android.R.color.holo_green_light))
                    connectBtn.isEnabled = false
                    disconnectBtn.isEnabled = true
                    Toast.makeText(this@RemoteControlActivity, "连接成功", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ isReadyToSend = true }, 200)
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    isReadyToSend = false
                    statusText.text = "❌ 未连接"
                    statusText.setTextColor(ContextCompat.getColor(this@RemoteControlActivity, android.R.color.holo_red_light))
                    connectBtn.isEnabled = true
                    disconnectBtn.isEnabled = false
                    Toast.makeText(this@RemoteControlActivity, "连接断开", Toast.LENGTH_SHORT).show()
                    bleController.sendControl(0f, 0f, stop = true)
                    leftSpeed = 0f
                    rightTurn = 0f
                    valueDisplay.text = "速度: 0.0 m/s  转向: 0 °/s"
                }
            }
        })

        bleController.setScanListener(object : BLEController.ScanListener {
            override fun onDeviceFound(device: android.bluetooth.BluetoothDevice) {}
            override fun onScanFailed(message: String) {
                runOnUiThread {
                    Toast.makeText(this@RemoteControlActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        })

        resetBtn.setOnClickListener {
            leftJoystick.resetJoystick()
            rightJoystick.resetJoystick()
            leftSpeed = 0f
            rightTurn = 0f
            bleController.sendControl(0f, 0f, stop = true)
            valueDisplay.text = "速度: 0.0 m/s  转向: 0 °/s"
            Toast.makeText(this, "已归零", Toast.LENGTH_SHORT).show()
        }

        connectBtn.setOnClickListener {
            if (!bleController.isBleSupported()) {
                Toast.makeText(this, "设备不支持BLE", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            val deviceName = findViewById<EditText>(R.id.device_name_input).text.toString().trim()
            if (deviceName.isEmpty()) {
                Toast.makeText(this, "请输入设备名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bleController.targetDeviceName = deviceName

            statusText.text = "🔍 扫描中..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            connectBtn.isEnabled = false
            bleController.startScan()

            handler.postDelayed({
                bleController.stopScan()
                if (!bleController.isConnected()) {
                    statusText.text = "⏱ 扫描超时，未找到设备"
                    statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    connectBtn.isEnabled = true
                }
            }, 10000)
        }

        disconnectBtn.setOnClickListener {
            isReadyToSend = false
            statusText.text = "⏳ 正在断开..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            bleController.disconnect()
        }

        settingBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        leftJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                leftSpeed = -speed
                sendCombinedControl()
            }
        })

        rightJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                rightTurn = turn
                sendCombinedControl()
            }
        })

        prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        loadConfig()

        if (prefs.getBoolean("video_enabled", false)) {
            startVideoStream()
        }
    }

    private fun loadConfig() {
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
        val name = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"
        bleController.targetDeviceName = name
        findViewById<EditText>(R.id.device_name_input).setText(name)
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
        val enabled = prefs.getBoolean("video_enabled", false)
        if (enabled && !isVideoRunning) {
            startVideoStream()
        } else if (!enabled && isVideoRunning) {
            stopVideoStream()
        }
    }

    override fun onPause() {
        super.onPause()
        // 可根据需要决定是否暂停图传
    }

    override fun onDestroy() {
        super.onDestroy()
        bleController.disconnect()
        handler.removeCallbacksAndMessages(null)
        stopVideoStream()
    }

    // ---------- 图传功能 ----------
    private fun startVideoStream() {
        if (isVideoRunning) return
        ivVideoBackground.visibility = View.VISIBLE
        isVideoRunning = true

        Thread {
            // 使用独立的 UDP 发现工具
            val ip = UdpDeviceDiscovery.discover()
            if (ip != null) {
                runOnUiThread {
                    connectToNetworkSource(ip, 8080)
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "未发现设备，请手动输入 IP", Toast.LENGTH_LONG).show()
                    showIpInputDialog()
                }
            }
        }.start()
    }

    private fun stopVideoStream() {
        isVideoRunning = false
        videoSource?.stop()
        videoSource = null
        ivVideoBackground.visibility = View.GONE
        ivVideoBackground.setImageBitmap(null)
    }

    private fun showIpInputDialog() {
        val input = EditText(this)
        input.hint = "输入设备 IP（如 192.168.43.10）"
        AlertDialog.Builder(this)
            .setTitle("图传 IP 地址")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) connectToNetworkSource(ip, 8080)
                else {
                    Toast.makeText(this, "IP 不能为空", Toast.LENGTH_SHORT).show()
                    showIpInputDialog()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                stopVideoStream()
                ivVideoBackground.visibility = View.GONE
            }
            .show()
    }

    private fun connectToNetworkSource(ip: String, port: Int) {
        stopVideoStream()
        isVideoRunning = true
        ivVideoBackground.visibility = View.VISIBLE

        videoSource = NetworkSource(ip, port)
        val started = videoSource?.start { bitmap ->
            runOnUiThread {
                ivVideoBackground.setImageBitmap(bitmap)
            }
        } ?: false
        if (!started) {
            Toast.makeText(this, "图传连接失败", Toast.LENGTH_SHORT).show()
            stopVideoStream()
        } else {
            Toast.makeText(this, "图传已连接", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 控制发送 ----------
    private fun sendCombinedControl() {
        if (!isReadyToSend) return
        val targetSpeed = leftSpeed * maxSpeed
        val targetTurn = rightTurn * maxTurn
        val now = System.currentTimeMillis()
        if (now - lastSendTime >= sendIntervalMs) {
            bleController.sendControl(targetSpeed, targetTurn, stop = false)
            lastSendTime = now
            valueDisplay.text = String.format(Locale.US,"速度: %.1f m/s  转向: %.0f °/s", targetSpeed, targetTurn)
        }
    }

    // ---------- 权限 ----------
    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                connectBtn.performClick()
            } else {
                Toast.makeText(this, "需要权限才能使用蓝牙", Toast.LENGTH_LONG).show()
            }
        }
    }
}