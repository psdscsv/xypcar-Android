package com.psd.xypcar

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.psd.xypcar.control.BLEController
import com.psd.xypcar.control.DirectionButtonFragment
import com.psd.xypcar.control.JoystickControlFragment
import com.psd.xypcar.network.NetworkSource
import com.psd.xypcar.network.UdpDeviceDiscovery
import java.util.Locale

class RemoteControlActivity : AppCompatActivity(),
    JoystickControlFragment.OnControlListener,   // 双摇杆/单摇杆的回调
    DirectionButtonFragment.OnControlListener {  // 方向按键的回调

    // ---------- 控件 ----------
    private lateinit var prefs: SharedPreferences
    private lateinit var bleController: BLEController
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var settingBtn: ImageButton
    private lateinit var deviceNameInput: EditText

    // 图传
    private lateinit var ivVideoBackground: ImageView
    private var videoSource: NetworkSource? = null
    private var isVideoRunning = false

    // 控制 Fragment（当前显示的）
    private var currentFragment: Fragment? = null
    private var joystickMode = 0

    // BLE 控制
    private var maxSpeed = 2.2f
    private var maxTurn = 50f
    private var isReadyToSend = false
    private var lastSendTime = 0L
    private val sendIntervalMs = 20L

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_remote_control)

        // 初始化控件
        ivVideoBackground = findViewById(R.id.iv_video_background)
        statusText = findViewById(R.id.status_text)
        connectBtn = findViewById(R.id.connect_btn)
        disconnectBtn = findViewById(R.id.disconnect_btn)
        resetBtn = findViewById(R.id.reset_btn)
        settingBtn = findViewById(R.id.btn_settings)
        deviceNameInput = findViewById(R.id.device_name_input)

        prefs = getSharedPreferences("car_config", MODE_PRIVATE)
        loadConfig()

        // 读取摇杆模式并加载对应的 Fragment
        joystickMode = prefs.getInt("joystick_mode", 0)
        loadControlFragment(joystickMode)

        // 初始化 BLE
        bleController = BLEController(this)
        setupBLE()

        // 按钮事件
        connectBtn.setOnClickListener { onConnect() }
        disconnectBtn.setOnClickListener { onDisconnect() }
        resetBtn.setOnClickListener {
            // 重置当前 Fragment 的控件
            when (currentFragment) {
                is JoystickControlFragment -> (currentFragment as JoystickControlFragment).resetJoysticks()
                is DirectionButtonFragment -> (currentFragment as DirectionButtonFragment).resetControls()
            }
            bleController.sendControl(0f, 0f, stop = true)
            Toast.makeText(this, "已归零", Toast.LENGTH_SHORT).show()
        }
        settingBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 图传自动启动（如果配置开启）
        if (prefs.getBoolean("video_enabled", false)) {
            startVideoStream()
        }

        // 权限检查
        if (!checkPermissions()) {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        loadConfig() // 更新参数
        // 检查摇杆模式是否变化
        val newMode = prefs.getInt("joystick_mode", 0)
        if (newMode != joystickMode) {
            joystickMode = newMode
            loadControlFragment(joystickMode)
        }
        // 图传状态
        val enabled = prefs.getBoolean("video_enabled", false)
        if (enabled && !isVideoRunning) {
            startVideoStream()
        } else if (!enabled && isVideoRunning) {
            stopVideoStream()
        }
    }

    override fun onPause() {
        super.onPause()
        // 可以暂停图传节省资源，但不关闭连接
    }

    override fun onDestroy() {
        super.onDestroy()
        bleController.disconnect()
        handler.removeCallbacksAndMessages(null)
        stopVideoStream()
    }
    private fun onReset() {
        when (currentFragment) {
            is JoystickControlFragment -> (currentFragment as JoystickControlFragment).resetJoysticks()
            is DirectionButtonFragment -> (currentFragment as DirectionButtonFragment).resetControls()
        }
        bleController.sendControl(0f, 0f, stop = true)
        Toast.makeText(this, "已归零", Toast.LENGTH_SHORT).show()
    }
    // ========== 加载控制 Fragment ==========
    private fun loadControlFragment(mode: Int) {
        val fragment: Fragment = when (mode) {
            0 -> JoystickControlFragment.newInstance(0)   // 双摇杆
            1 -> JoystickControlFragment.newInstance(1)   // 单摇杆
            2 -> DirectionButtonFragment()                // 方向按键
            else -> JoystickControlFragment.newInstance(0)
        }
        currentFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.joystick_container, fragment)
            .commit()
    }

    // ========== 摇杆回调（两个接口共用此实现） ==========
    override fun onControl(speed: Float, turn: Float) {
        handleControl(speed, turn)
    }

    private fun handleControl(speed: Float, turn: Float) {
        if (isReadyToSend) {
            val targetSpeed = speed * maxSpeed
            val targetTurn = turn * maxTurn
            val now = System.currentTimeMillis()
            // 归零强制发送
            if (targetSpeed == 0f && targetTurn == 0f) {
                bleController.sendControl(0f, 0f, stop = false)
                lastSendTime = now
            } else if (now - lastSendTime >= sendIntervalMs) {
                bleController.sendControl(targetSpeed, targetTurn, stop = false)
                lastSendTime = now
            }
        }
    }

    // ========== BLE 相关 ==========
    private fun setupBLE() {
        bleController.setConnectionListener(object : BLEController.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusText.text = "✅ 已连接"
                    statusText.setTextColor(
                        ContextCompat.getColor(
                            this@RemoteControlActivity,
                            android.R.color.holo_green_light
                        )
                    )
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
                    statusText.setTextColor(
                        ContextCompat.getColor(
                            this@RemoteControlActivity,
                            android.R.color.holo_red_light
                        )
                    )
                    connectBtn.isEnabled = true
                    disconnectBtn.isEnabled = false
                    Toast.makeText(this@RemoteControlActivity, "连接断开", Toast.LENGTH_SHORT).show()
                    bleController.sendControl(0f, 0f, stop = true)
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
    }

    private fun onConnect() {
        if (!bleController.isBleSupported()) {
            Toast.makeText(this, "设备不支持BLE", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        val deviceName = deviceNameInput.text.toString().trim()
        if (deviceName.isEmpty()) {
            Toast.makeText(this, "请输入设备名称", Toast.LENGTH_SHORT).show()
            return
        }
        bleController.targetDeviceName = deviceName

        statusText.text = "🔍 扫描中..."
        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                android.R.color.holo_blue_light
            )
        )
        connectBtn.isEnabled = false
        bleController.startScan()

        handler.postDelayed({
            bleController.stopScan()
            if (!bleController.isConnected()) {
                statusText.text = "⏱ 扫描超时，未找到设备"
                statusText.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_red_light
                    )
                )
                connectBtn.isEnabled = true
            }
        }, 10000)
    }

    private fun onDisconnect() {
        isReadyToSend = false
        statusText.text = "⏳ 正在断开..."
        statusText.setTextColor(
            ContextCompat.getColor(
                this,
                android.R.color.holo_orange_light
            )
        )
        bleController.disconnect()
    }

    // ========== 图传 ==========
    private fun startVideoStream() {
        if (isVideoRunning) return
        ivVideoBackground.visibility = View.VISIBLE
        isVideoRunning = true

        Thread {
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

    // ========== 配置 ==========
    private fun loadConfig() {
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
        val name = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"
        deviceNameInput.setText(name)
    }

    // ========== 权限 ==========
    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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