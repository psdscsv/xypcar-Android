// RemoteControlActivity.kt
package com.psd.xypcar

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.psd.xypcar.control.BLEController
import com.psd.xypcar.control.JoystickView
import java.util.Locale
import android.content.Intent
import android.widget.ImageButton

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

    private val handler = Handler(Looper.getMainLooper())

    // 存储摇杆当前值（归一化 -1 ~ 1）
    private var leftSpeed = 0f   // 左摇杆速度（Y轴，向上为正）
    private var rightTurn = 0f   // 右摇杆转向（X轴，向右为正）

    // 限流发送
    private var lastSendTime = 0L
    private val sendIntervalMs = 20L
    private var isReadyToSend = false  // 连接稳定后才允许发送

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 隐藏状态栏和标题栏（如果主题已处理标题栏，这里只处理状态栏）
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_remote_control)

        leftJoystick = findViewById(R.id.left_joystick)
        rightJoystick = findViewById(R.id.right_joystick)
        statusText = findViewById(R.id.status_text)
        connectBtn = findViewById(R.id.connect_btn)
        disconnectBtn = findViewById(R.id.disconnect_btn)
        valueDisplay = findViewById(R.id.value_display)
        resetBtn = findViewById(R.id.reset_btn)
        settingBtn = findViewById(R.id.btn_settings)
        bleController = BLEController(this)

        // 检查权限
        if (!checkPermissions()) {
            requestPermissions()
        }

        // BLE 监听器
        bleController.setConnectionListener(object : BLEController.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusText.text = "✅ 已连接"
                    statusText.setTextColor(ContextCompat.getColor(this@RemoteControlActivity, android.R.color.holo_green_light))
                    connectBtn.isEnabled = false      // 连接成功后禁用连接按钮
                    disconnectBtn.isEnabled = true    // 启用断开按钮
                    Toast.makeText(this@RemoteControlActivity, "连接成功", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ isReadyToSend = true }, 200)
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    isReadyToSend = false
                    statusText.text = "❌ 未连接"
                    statusText.setTextColor(ContextCompat.getColor(this@RemoteControlActivity, android.R.color.holo_red_light))
                    connectBtn.isEnabled = true       // 恢复连接按钮
                    disconnectBtn.isEnabled = false   // 禁用断开按钮
                    Toast.makeText(this@RemoteControlActivity, "连接断开", Toast.LENGTH_SHORT).show()
                    bleController.sendControl(0f, 0f, stop = true)
                    leftSpeed = 0f
                    rightTurn = 0f
                    valueDisplay.text = "速度: 0.0 m/s  转向: 0 °/s"
                }
            }
        })

        bleController.setScanListener(object : BLEController.ScanListener {
            override fun onDeviceFound(device: android.bluetooth.BluetoothDevice) {
                // 自动连接，无需操作
            }

            override fun onScanFailed(message: String) {
                runOnUiThread {
                    Toast.makeText(this@RemoteControlActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        })


        resetBtn.setOnClickListener {
            // 强制摇杆归零
            leftJoystick.resetJoystick()   // 需要添加方法
            rightJoystick.resetJoystick()
            // 清空变量
            leftSpeed = 0f
            rightTurn = 0f
            // 发送停止指令
            bleController.sendControl(0f, 0f, stop = true)
            valueDisplay.text = "速度: 0.0 m/s  转向: 0 °/s"
            // 可选：显示提示
            Toast.makeText(this, "已归零", Toast.LENGTH_SHORT).show()
        }
        // 连接按钮
        connectBtn.setOnClickListener {
            if (!bleController.isBleSupported()) {
                Toast.makeText(this, "设备不支持BLE", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!checkPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            // 获取设备名称（假设您有 EditText 控件）
            val deviceName = findViewById<EditText>(R.id.device_name_input).text.toString().trim()
            if (deviceName.isEmpty()) {
                Toast.makeText(this, "请输入设备名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bleController.targetDeviceName = deviceName

            statusText.text = "🔍 扫描中..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            connectBtn.isEnabled = false   // 防止重复点击
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
            // 显示断开中的状态（可选）
            statusText.text = "⏳ 正在断开..."
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            bleController.disconnect()
        }

        settingBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // ========== 左摇杆：控制速度（垂直方向） ==========
        leftJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                // 只取 Y 轴（速度），向上为正（前进）
                leftSpeed = -speed   // 因为摇杆Y轴向上为负，取反
                sendCombinedControl()
            }

        })

        // ========== 右摇杆：控制转向（水平方向） ==========
        rightJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                // 只取 X 轴（转向），向右为正
                rightTurn = turn
                sendCombinedControl()
            }

        })

        // 启动时自动连接
        if (checkPermissions()) {
            connectBtn.performClick()
        }
        prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        loadConfig()
    }
    private fun loadConfig() {
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
        // 如果有设备名称，可以更新 BLEController 的目标名称
        val name = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"
        bleController.targetDeviceName = name
        // 更新 UI 上的设备名称输入框（如果有）
        findViewById<EditText>(R.id.device_name_input).setText(name)
    }
    override fun onResume() {
        super.onResume()
        loadConfig()
    }
    // ========== 合并两个摇杆值发送 ==========
    private fun sendCombinedControl() {
        if (!isReadyToSend) return

        // 映射速度：-1 ~ 1 -> -2.0 ~ 2.0 m/s
        val targetSpeed = leftSpeed * maxSpeed

        // 映射转向：-1 ~ 1 -> -50 ~ 50 °/s
        val targetTurn = rightTurn * maxTurn

        // 限流发送
        val now = System.currentTimeMillis()
        if (now - lastSendTime >= sendIntervalMs) {
            bleController.sendControl(targetSpeed, targetTurn, stop = false)
            lastSendTime = now
            // 更新UI显示
            valueDisplay.text = String.format(Locale.US,"速度: %.1f m/s  转向: %.0f °/s", targetSpeed, targetTurn)
        }
    }

    // ========== 权限处理 ==========
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

    override fun onDestroy() {
        super.onDestroy()
        bleController.disconnect()
        handler.removeCallbacksAndMessages(null)
    }
}