package com.psd.xypcar

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.psd.xypcar.control.JoystickView
import com.psd.xypcar.remote.RelayClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class RemoteRelayActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var relayClient: RelayClient
    private var deviceId: String = ""
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private var isConnecting = false

    // UI组件
    private lateinit var tvMyId: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRemoteStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etTargetId: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    // 地图
    private lateinit var mapView: MapView
    private lateinit var aMap: AMap
    private lateinit var rightPanel: View   // 右侧面板（ScrollView）

    // 目标点列表
    private lateinit var lvWaypoints: ListView
    private lateinit var btnAddRemotePos: Button
    private lateinit var btnDeleteRemotePoint: Button
    private lateinit var btnClearRemotePoints: Button

    // 远程数据
    private val remoteWaypoints = mutableListOf<LatLonPoint>()
    private val remoteMarkers = mutableListOf<Marker>()
    private val remoteCircles = mutableListOf<Circle>()
    private var remoteCurrentTarget = 0
    private var remoteLocationMarker: Marker? = null

    private lateinit var waypointAdapter: ArrayAdapter<String>
    private val waypointDisplayList = mutableListOf<String>()
    private var selectedWaypointIndex = -1

    // 驾驶模式相关
    private lateinit var joystickLeft: JoystickView
    private lateinit var joystickRight: JoystickView
    private lateinit var btnToggleDriveMode: ImageButton
    private lateinit var btnExitDriveMode: ImageButton
    private lateinit var tvDriveInfo: TextView
    private lateinit var joystickOverlay: RelativeLayout
    private var isDriveMode = false
    private var driveSpeed = 0f
    private var driveTurn = 0f
    private val driveSendInterval = 50L
    private var lastDriveSendTime = 0L

    // 从设置读取的最大速度和转向
    private var maxSpeed = 2.2f
    private var maxTurn = 50f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_remote_relay)

        // 初始化高德地图
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        prefs = getSharedPreferences("car_config", MODE_PRIVATE)
        loadConfig()

        // 初始化UI
        tvMyId = findViewById(R.id.tv_my_id)
        tvStatus = findViewById(R.id.tv_status)
        tvRemoteStatus = findViewById(R.id.tv_remote_status)
        tvLog = findViewById(R.id.tv_log)
        etTargetId = findViewById(R.id.et_target_id)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)

        // 地图
        mapView = findViewById(R.id.remote_map)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isCompassEnabled = true
        aMap.isMyLocationEnabled = false

        // 右侧面板（ScrollView）
        rightPanel = findViewById(R.id.right_panel)

        // 目标点列表
        lvWaypoints = findViewById(R.id.lv_remote_waypoints)
        btnAddRemotePos = findViewById(R.id.btn_add_remote_pos)
        btnDeleteRemotePoint = findViewById(R.id.btn_delete_remote_point)
        btnClearRemotePoints = findViewById(R.id.btn_clear_remote_points)

        // 驾驶模式控件
        joystickOverlay = findViewById(R.id.joystick_overlay)
        btnToggleDriveMode = findViewById(R.id.btn_toggle_drive_mode)
        btnExitDriveMode = findViewById(R.id.btn_exit_drive_mode)
        joystickLeft = findViewById(R.id.joystick_left)
        joystickRight = findViewById(R.id.joystick_right)
        tvDriveInfo = findViewById(R.id.tv_drive_info)

        // 初始化目标点适配器
        waypointAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, waypointDisplayList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.WHITE)
                return view
            }
        }
        lvWaypoints.adapter = waypointAdapter
        lvWaypoints.choiceMode = ListView.CHOICE_MODE_SINGLE

        lvWaypoints.setOnItemClickListener { _, _, position, _ ->
            selectedWaypointIndex = position
            if (isConnected) {
                sendRemoteCommand("select_waypoint", mapOf("index" to position))
            }
        }

        // 获取用户名
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
                    appendLog("【收到】 $from: ${payload.take(100)}")
                    handleReceivedMessage(from, payload)
                }
            },
            onStatusChanged = { status ->
                runOnUiThread {
                    tvStatus.text = "状态: $status"
                    appendLog("【状态】 $status")
                    when {
                        status.contains("注册成功") -> {
                            isConnected = true
                            isConnecting = false
                            btnConnect.text = "已连接"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = true
                            Toast.makeText(this@RemoteRelayActivity, "连接成功", Toast.LENGTH_SHORT).show()
                        }
                        status.contains("断开") || status.contains("错误") -> {
                            isConnected = false
                            isConnecting = false
                            btnConnect.text = "连接"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                            clearRemoteData()
                            if (isDriveMode) {
                                toggleDriveMode(false)
                            }
                        }
                        status.contains("连接失败") -> {
                            isConnecting = false
                            btnConnect.text = "连接"
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                    }
                }
            }
        )

        // 地图长按添加目标点
        aMap.setOnMapLongClickListener { latLng ->
            if (!isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnMapLongClickListener
            }
            val target = etTargetId.text.toString().trim()
            if (target.isEmpty()) {
                Toast.makeText(this, "请输入目标设备ID", Toast.LENGTH_SHORT).show()
                return@setOnMapLongClickListener
            }
            sendRemoteCommand("add_waypoint", mapOf(
                "lat" to latLng.latitude,
                "lng" to latLng.longitude
            ))
            Toast.makeText(this, "远程添加目标点", Toast.LENGTH_SHORT).show()
        }

        // 连接按钮
        btnConnect.setOnClickListener {
            if (isConnected) {
                Toast.makeText(this, "已连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isConnecting) {
                Toast.makeText(this, "正在连接中...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (host.isEmpty()) {
                Toast.makeText(this, "请先在设置中配置服务器地址", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            isConnecting = true
            btnConnect.isEnabled = false
            btnConnect.text = "连接中..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val success = relayClient.connect(deviceId)
                    withContext(Dispatchers.Main) {
                        if (!success) {
                            isConnecting = false
                            btnConnect.text = "连接"
                            btnConnect.isEnabled = true
                            Toast.makeText(this@RemoteRelayActivity, "连接失败，请检查网络和服务器", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isConnecting = false
                        btnConnect.text = "连接"
                        btnConnect.isEnabled = true
                        Toast.makeText(this@RemoteRelayActivity, "连接异常: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // 超时处理
            handler.postDelayed({
                if (isConnecting && !isConnected) {
                    isConnecting = false
                    btnConnect.text = "连接"
                    btnConnect.isEnabled = true
                    Toast.makeText(this, "连接超时", Toast.LENGTH_SHORT).show()
                }
            }, 15000)
        }

        btnDisconnect.setOnClickListener {
            if (isConnected) {
                relayClient.disconnect()
                isConnected = false
                isConnecting = false
                btnConnect.text = "连接"
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                tvStatus.text = "状态: 已断开"
                appendLog("【系统】 已断开")
                clearRemoteData()
                if (isDriveMode) {
                    toggleDriveMode(false)
                }
                Toast.makeText(this, "已断开", Toast.LENGTH_SHORT).show()
            }
        }

        // 目标点操作按钮
        btnAddRemotePos.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val locationText = tvRemoteStatus.text.toString()
            val latLng = extractLatLngFromStatus(locationText)
            if (latLng != null) {
                sendRemoteCommand("add_waypoint", mapOf(
                    "lat" to latLng.first,
                    "lng" to latLng.second
                ))
                Toast.makeText(this, "已请求添加远程位置为目标点", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "无法获取远程位置", Toast.LENGTH_SHORT).show()
            }
        }

        btnDeleteRemotePoint.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pos = lvWaypoints.checkedItemPosition
            if (pos != ListView.INVALID_POSITION && pos < remoteWaypoints.size) {
                sendRemoteCommand("delete_waypoint", mapOf("index" to pos))
                Toast.makeText(this, "已请求删除目标点", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先选中一个点", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearRemotePoints.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCommand("clear_waypoints", emptyMap())
            Toast.makeText(this, "已请求清空所有目标点", Toast.LENGTH_SHORT).show()
        }

        // 遥控按钮
        findViewById<Button>(R.id.btn_forward).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("remote", mapOf("speed" to maxSpeed * 0.7f, "turn" to 0f))
        }
        findViewById<Button>(R.id.btn_backward).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("remote", mapOf("speed" to -maxSpeed * 0.7f, "turn" to 0f))
        }
        findViewById<Button>(R.id.btn_left).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("remote", mapOf("speed" to 0f, "turn" to -maxTurn * 0.6f))
        }
        findViewById<Button>(R.id.btn_right).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("remote", mapOf("speed" to 0f, "turn" to maxTurn * 0.6f))
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("remote", mapOf("speed" to 0f, "turn" to 0f))
        }
        findViewById<Button>(R.id.btn_start_auto).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("start_auto", emptyMap())
        }
        findViewById<Button>(R.id.btn_stop_auto).setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendRemoteCmd("stop_auto", emptyMap())
        }

        // 驾驶模式切换
        btnToggleDriveMode.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            toggleDriveMode(true)
        }
        btnExitDriveMode.setOnClickListener {
            toggleDriveMode(false)
            if (isConnected) {
                sendRemoteCommand("remote", mapOf("speed" to 0f, "turn" to 0f))
            }
        }

        // 设置摇杆监听
        joystickLeft.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                driveSpeed = -speed
                sendDriveControl()
            }
        })

        joystickRight.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                driveTurn = turn
                sendDriveControl()
            }
        })

        // 初始不进入驾驶模式
        toggleDriveMode(false)

        // 如果有目标设备ID，自动填充
        val savedTarget = prefs.getString("remote_target_id", "")
        if (savedTarget?.isNotEmpty() == true) {
            etTargetId.setText(savedTarget)
        }
    }

    // 加载配置
    private fun loadConfig() {
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
    }

    // 驾驶模式切换
    private fun toggleDriveMode(enter: Boolean) {
        if (enter && !isConnected) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
            return
        }

        isDriveMode = enter
        if (enter) {
            // 隐藏右侧面板
            rightPanel.visibility = View.GONE
            // 地图扩展至全屏
            val mapLayoutParams = mapView.layoutParams as LinearLayout.LayoutParams
            mapLayoutParams.weight = 0f
            mapLayoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            mapView.layoutParams = mapLayoutParams

            // 显示摇杆覆盖层
            joystickOverlay.visibility = View.VISIBLE

            // 重置摇杆
            joystickLeft.resetJoystick()
            joystickRight.resetJoystick()
            driveSpeed = 0f
            driveTurn = 0f
            tvDriveInfo.text = "速度: 0.0  转向: 0.0"
        } else {
            // 恢复右侧面板
            rightPanel.visibility = View.VISIBLE
            // 恢复地图布局权重
            val mapLayoutParams = mapView.layoutParams as LinearLayout.LayoutParams
            mapLayoutParams.weight = 1f
            mapLayoutParams.width = 0
            mapView.layoutParams = mapLayoutParams

            // 隐藏摇杆覆盖层
            joystickOverlay.visibility = View.GONE

            // 重置摇杆
            joystickLeft.resetJoystick()
            joystickRight.resetJoystick()
            driveSpeed = 0f
            driveTurn = 0f

            // 发送停止指令
            if (isConnected) {
                sendRemoteCommand("remote", mapOf("speed" to 0f, "turn" to 0f))
            }
        }
    }

    // 驾驶模式下发送控制指令
    private fun sendDriveControl() {
        if (!isDriveMode || !isConnected) return
        val now = System.currentTimeMillis()
        if (now - lastDriveSendTime < driveSendInterval) return
        lastDriveSendTime = now

        val speed = (driveSpeed * maxSpeed).coerceIn(-maxSpeed, maxSpeed)
        val turn = (driveTurn * maxTurn).coerceIn(-maxTurn, maxTurn)
        sendRemoteCommand("remote", mapOf("speed" to speed, "turn" to turn))

        runOnUiThread {
            tvDriveInfo.text = String.format(Locale.US, "速度: %.1f  转向: %.0f", speed, turn)
        }
    }

    // 处理接收到的消息
    private fun handleReceivedMessage(from: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val type = json.optString("type")
            when (type) {
                "status" -> updateRemoteStatus(json)
                else -> appendLog("【数据】 $payload")
            }
        } catch (e: Exception) {
            appendLog("【原始】 $payload")
        }
    }

    private fun updateRemoteStatus(json: JSONObject) {
        val lat = json.optDouble("lat", Double.NaN)
        val lng = json.optDouble("lng", Double.NaN)
        val bearing = json.optDouble("bearing", 0.0).toFloat()
        val isNavigating = json.optBoolean("is_navigating", false)
        val isCalibrating = json.optBoolean("is_calibrating", false)

        if (!lat.isNaN() && !lng.isNaN()) {
            updateRemoteLocation(lat, lng, bearing)
            val statusText = when {
                isNavigating -> "导航中"
                isCalibrating -> "校准中"
                else -> "空闲"
            }
            tvRemoteStatus.text = String.format(
                Locale.US, "📍 位置: %.6f, %.6f  方向: %.1f°  状态: %s",
                lat, lng, bearing, statusText
            )
        }

        val waypointsArray = json.optJSONArray("waypoints")
        val currentIdx = json.optInt("current_target", 0)
        if (waypointsArray != null) {
            updateRemoteWaypoints(waypointsArray, currentIdx)
        }
    }

    private fun updateRemoteLocation(lat: Double, lng: Double, bearing: Float) {
        try {
            val latLng = LatLng(lat, lng)
            if (remoteLocationMarker == null) {
                remoteLocationMarker = aMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("远程设备")
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_car_blue))
                        .anchor(0.5f, 0.5f)
                )
            } else {
                remoteLocationMarker?.position = latLng
            }
            remoteLocationMarker?.setRotateAngle(bearing)
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
        } catch (e: Exception) {
            appendLog("【错误】 更新位置失败: ${e.message}")
        }
    }

    private fun updateRemoteWaypoints(waypointsArray: JSONArray, currentTarget: Int) {
        try {
            remoteMarkers.forEach { it.remove() }
            remoteMarkers.clear()
            remoteCircles.forEach { it.remove() }
            remoteCircles.clear()
            remoteWaypoints.clear()
            waypointDisplayList.clear()

            remoteCurrentTarget = currentTarget
            val arrivalDist = prefs.getFloat("arrival_distance", 10f)

            for (i in 0 until waypointsArray.length()) {
                val pt = waypointsArray.getJSONObject(i)
                val lat = pt.optDouble("lat")
                val lng = pt.optDouble("lng")
                if (!lat.isNaN() && !lng.isNaN()) {
                    val point = LatLonPoint(lat, lng)
                    remoteWaypoints.add(point)

                    val isCurrent = (i == currentTarget)
                    val hue = if (isCurrent) BitmapDescriptorFactory.HUE_BLUE else BitmapDescriptorFactory.HUE_RED

                    val marker = aMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(lat, lng))
                            .title("目标点 ${i + 1}")
                            .icon(BitmapDescriptorFactory.defaultMarker(hue))
                    )
                    remoteMarkers.add(marker)

                    val circle = aMap.addCircle(
                        CircleOptions()
                            .center(LatLng(lat, lng))
                            .radius(arrivalDist.toDouble())
                            .strokeColor(if (isCurrent) Color.YELLOW else Color.argb(180, 255, 0, 0))
                            .strokeWidth(if (isCurrent) 6f else 2f)
                            .fillColor(Color.argb(30, 255, 0, 0))
                    )
                    remoteCircles.add(circle)

                    val display = String.format(Locale.US, "%d: %.6f, %.6f", i + 1, lat, lng)
                    waypointDisplayList.add(display)
                }
            }

            waypointAdapter.notifyDataSetChanged()
            if (currentTarget >= 0 && currentTarget < remoteWaypoints.size) {
                lvWaypoints.setItemChecked(currentTarget, true)
                selectedWaypointIndex = currentTarget
            }
        } catch (e: Exception) {
            appendLog("【错误】 更新目标点失败: ${e.message}")
        }
    }

    private fun clearRemoteData() {
        try {
            remoteMarkers.forEach { it.remove() }
            remoteMarkers.clear()
            remoteCircles.forEach { it.remove() }
            remoteCircles.clear()
            remoteWaypoints.clear()
            waypointDisplayList.clear()
            waypointAdapter.notifyDataSetChanged()
            remoteLocationMarker?.remove()
            remoteLocationMarker = null
            tvRemoteStatus.text = "等待数据..."
        } catch (e: Exception) {
            appendLog("【错误】 清除数据失败: ${e.message}")
        }
    }

    private fun extractLatLngFromStatus(status: String): Pair<Double, Double>? {
        val pattern = Regex("位置: (\\d+\\.\\d+), (\\d+\\.\\d+)")
        val match = pattern.find(status)
        return if (match != null) {
            val lat = match.groupValues[1].toDoubleOrNull()
            val lng = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) Pair(lat, lng) else null
        } else null
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
        prefs.edit().putString("remote_target_id", target).apply()
        sendRemoteCommand(cmd, params)
    }

    private fun sendRemoteCommand(type: String, params: Map<String, Any>) {
        val target = etTargetId.text.toString().trim()
        if (target.isEmpty()) return

        val payload = JSONObject().apply {
            put("type", type)
            params.forEach { put(it.key, it.value) }
        }.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = relayClient.sendMessage(target, payload)
                withContext(Dispatchers.Main) {
                    if (success) {
                        appendLog("【发送】 到 $target: $type")
                    } else {
                        Toast.makeText(this@RemoteRelayActivity, "发送失败，连接可能已断开", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RemoteRelayActivity, "发送异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun appendLog(text: String) {
        tvLog.append("$text\n")
        val lines = tvLog.text.split("\n")
        if (lines.size > 200) {
            tvLog.text = lines.takeLast(150).joinToString("\n") + "\n"
        }
        (tvLog.parent as? View)?.post {
            (tvLog.parent as? View)?.scrollTo(0, (tvLog.parent as? View)?.height ?: 0)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            mapView.onResume()
            loadConfig()
        } catch (e: Exception) {
            // 忽略地图恢复异常
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            mapView.onPause()
        } catch (e: Exception) {
            // 忽略地图暂停异常
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mapView.onDestroy()
        } catch (e: Exception) {
            // 忽略地图销毁异常
        }
        try {
            relayClient.disconnect()
        } catch (e: Exception) {
            // 忽略断开连接异常
        }
        handler.removeCallbacksAndMessages(null)
        try {
            joystickLeft.resetJoystick()
            joystickRight.resetJoystick()
        } catch (e: Exception) {
            // 忽略摇杆重置异常
        }
    }
}