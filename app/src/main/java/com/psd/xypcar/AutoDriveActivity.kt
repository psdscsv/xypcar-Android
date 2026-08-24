package com.psd.xypcar

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.psd.xypcar.control.BLEController
import com.psd.xypcar.navigation.NavigationConfig
import com.psd.xypcar.navigation.NavigationEngine
import com.psd.xypcar.navigation.NavigationResult
import com.psd.xypcar.remote.RelayClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class AutoDriveActivity : AppCompatActivity(),
    AMapLocationListener,
    SensorEventListener {

    // ---------- UI 组件 ----------
    private lateinit var mapView: MapView
    private lateinit var aMap: AMap
    private lateinit var bleController: BLEController

    private lateinit var lvWaypoints: ListView
    private lateinit var btnAddCurrentPos: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnClearPoints: Button
    private lateinit var btnStartNav: Button
    private lateinit var btnStopNav: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentTarget: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTurn: TextView
    private lateinit var btnLocate: ImageButton
    private lateinit var btnRemoteControl: Button
    private lateinit var btnToggleBigMode: Button

    // 覆盖层控件
    private lateinit var overlayBigButtons: RelativeLayout
    private lateinit var overlayStatus: TextView
    private lateinit var overlayTarget: TextView
    private lateinit var overlaySpeed: TextView
    private lateinit var overlayBtnStart: Button
    private lateinit var overlayBtnStop: Button
    private lateinit var overlayClose: ImageButton

    // Tab 按钮
    private lateinit var tabControl: Button
    private lateinit var tabPoints: Button
    private lateinit var scrollControl: ScrollView
    private lateinit var pagePoints: LinearLayout

    // ---------- 多点导航数据 ----------
    private val waypoints = mutableListOf<LatLonPoint>()
    private val waypointMarkers = mutableListOf<Marker>()
    private val waypointCircles = mutableListOf<Circle>()
    private var selectedMarkerIndex = -1

    private lateinit var waypointAdapter: ArrayAdapter<String>
    private val waypointDisplayList = mutableListOf<String>()

    private val REQUEST_LOAD_FILE = 1001

    // ---------- 高德定位 ----------
    private lateinit var locationClient: AMapLocationClient
    private var currentLocation: AMapLocation? = null

    private var isFirstLocation = true

    // ---------- 传感器 ----------
    private lateinit var sensorManager: SensorManager
    private var deviceBearing = 0f
    private var rollVelocity = 0f

    // ---------- 导航循环 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var navRunnable: Runnable? = null
    private val navInterval = 100L

    // ---------- BLE ----------
    private var isBleConnected = false

    // ---------- 控制参数 ----------
    private lateinit var navConfig: NavigationConfig
    private lateinit var navEngine: NavigationEngine

    private var targetCircle: Circle? = null

    // ---------- 远程控制 ----------
    private var relayClient: RelayClient? = null
    private var remoteTargetId: String = ""
    private var remoteEnabled = false
    private var remoteConnecting = false
    private var statusSendRunnable: Runnable? = null
    private val statusInterval = 1000L
    private var remoteUsername: String = ""

    // ---------- 地图线条 ----------
    private var headingLine: Polyline? = null
    private var pathLine: Polyline? = null
    private var guideLine: Polyline? = null
    private val headingLineLength = 30.0

    // ---------- 权限请求标志 ----------
    private var isRequestingPermission = false

    // ---------- 跟随模式 ----------
    private var isFollowing = false

    // ---------- 路径点存储 ----------
    private lateinit var btnSaveWaypoints: Button
    private lateinit var btnLoadWaypoints: Button
    private lateinit var btnExportWaypoints: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_autodrive)

        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        mapView = findViewById(R.id.autodrive_map)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map
        aMap.setMapType(AMap.MAP_TYPE_SATELLITE)
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isCompassEnabled = true
        aMap.isMyLocationEnabled = true

        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
        aMap.myLocationStyle = myLocationStyle

        // 初始化 UI
        lvWaypoints = findViewById(R.id.lv_waypoints)
        btnAddCurrentPos = findViewById(R.id.btn_add_current_pos)
        btnDeleteSelected = findViewById(R.id.btn_delete_selected)
        btnClearPoints = findViewById(R.id.btn_clear_points)
        btnStartNav = findViewById(R.id.btn_start_nav)
        btnStopNav = findViewById(R.id.btn_stop_nav)
        tvStatus = findViewById(R.id.tv_status)
        tvCurrentTarget = findViewById(R.id.tv_current_target)
        tvInfo = findViewById(R.id.tv_info)
        tvBleStatus = findViewById(R.id.tv_ble_status)
        tvSpeed = findViewById(R.id.tv_speed)
        tvTurn = findViewById(R.id.tv_turn)
        btnLocate = findViewById(R.id.btn_locate)
        btnRemoteControl = findViewById(R.id.btn_remote_control)
        btnToggleBigMode = findViewById(R.id.btn_toggle_big_mode)

        // 覆盖层
        overlayBigButtons = findViewById(R.id.overlay_big_buttons)
        overlayStatus = findViewById(R.id.overlay_status)
        overlayTarget = findViewById(R.id.overlay_target)
        overlaySpeed = findViewById(R.id.overlay_speed)
        overlayBtnStart = findViewById(R.id.overlay_btn_start)
        overlayBtnStop = findViewById(R.id.overlay_btn_stop)
        overlayClose = findViewById(R.id.btn_close_overlay)

        tabControl = findViewById(R.id.tab_control)
        tabPoints = findViewById(R.id.tab_points)
        scrollControl = findViewById(R.id.scroll_control)
        pagePoints = findViewById(R.id.page_points)

        btnSaveWaypoints = findViewById(R.id.btn_save_waypoints)
        btnLoadWaypoints = findViewById(R.id.btn_load_waypoints)
        btnExportWaypoints = findViewById(R.id.btn_export_waypoints)

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
            selectWaypoint(position)
        }

        tabControl.setOnClickListener { switchTab(true) }
        tabPoints.setOnClickListener { switchTab(false) }

        // 读取配置
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        navConfig = NavigationConfig(
            maxSpeed = prefs.getFloat("nav_max_speed", 1.5f),
            maxTurn = prefs.getFloat("nav_max_turn", 50f),
            arrivalDistance = prefs.getFloat("arrival_distance", 10f),
            pathLookahead = 5f,
            turnDeadZone = prefs.getFloat("turn_dead_zone", 2f),
            rollThreshold = prefs.getFloat("roll_threshold", 15f),
            calibrationTime = prefs.getFloat("calibration_time", 2.0f),
            calibrationAngle = prefs.getFloat("calibration_angle", 5.0f)
        )
        navEngine = NavigationEngine(navConfig)

        val deviceName = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"
        remoteUsername = prefs.getString("remote_username", "") ?: ""

        // 传感器
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (orientationSensor != null) {
            sensorManager.registerListener(this, orientationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotSensor != null) {
                sensorManager.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                Toast.makeText(this, "设备无方向传感器，转向将依赖GPS", Toast.LENGTH_LONG).show()
            }
        }
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Toast.makeText(this, "设备无陀螺仪，翻滚检测不可用", Toast.LENGTH_LONG).show()
        }

        // BLE
        bleController = BLEController(this)
        bleController.targetDeviceName = deviceName
        bleController.setConnectionListener(object : BLEController.ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    isBleConnected = true
                    tvBleStatus.text = "BLE: 已连接"
                    tvBleStatus.setTextColor(ContextCompat.getColor(this@AutoDriveActivity, android.R.color.holo_green_light))
                    Toast.makeText(this@AutoDriveActivity, "BLE 连接成功", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    isBleConnected = false
                    tvBleStatus.text = "BLE: 未连接"
                    tvBleStatus.setTextColor(ContextCompat.getColor(this@AutoDriveActivity, android.R.color.holo_red_light))
                    // 如果导航中，自动停止
                    if (navEngine.update(currentLocation, deviceBearing, rollVelocity, false).isNavigating) {
                        navEngine.stop()
                        updateUIFromResult(NavigationResult(
                            speed = 0f, turn = 0f, stop = true,
                            statusMessage = "BLE 断开，导航停止",
                            isNavigating = false, isCalibrating = false,
                            currentTargetIndex = -1,
                            distanceToTarget = 0f, targetBearing = 0f
                        ))
                    }
                }
            }
        })

        // 高德定位
        locationClient = AMapLocationClient(applicationContext)
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.isOnceLocation = false
        option.interval = 1000
        option.isNeedAddress = false
        option.isSensorEnable = true
        locationClient.setLocationOption(option)
        locationClient.setLocationListener(this)

        if (checkLocationPermission()) {
            locationClient.startLocation()
        } else {
            if (!isRequestingPermission) {
                requestLocationPermissions()
            }
        }

        // ---------- 初始化远程控制 ----------
        initRemoteControl()

        // ---------- 地图长按添加点 ----------
        aMap.setOnMapLongClickListener { latLng ->
            val point = LatLonPoint(latLng.latitude, latLng.longitude)
            addWaypoint(point)
            Toast.makeText(this, "添加目标点 (${latLng.latitude}, ${latLng.longitude})", Toast.LENGTH_SHORT).show()
        }

        // ---------- 按钮事件 ----------
        btnAddCurrentPos.setOnClickListener {
            currentLocation?.let { loc ->
                val point = LatLonPoint(loc.latitude, loc.longitude)
                addWaypoint(point)
                Toast.makeText(this, "添加当前位置", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, "位置未获取", Toast.LENGTH_SHORT).show()
        }

        btnDeleteSelected.setOnClickListener {
            val pos = lvWaypoints.checkedItemPosition
            if (pos != ListView.INVALID_POSITION && pos < waypoints.size) {
                deleteWaypoint(pos)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先选中一个点", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearPoints.setOnClickListener {
            clearAllWaypoints()
            Toast.makeText(this, "已清空所有点", Toast.LENGTH_SHORT).show()
        }

        btnStartNav.setOnClickListener {
            if (waypoints.isEmpty()) {
                Toast.makeText(this, "请先添加目标点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isBleConnected) {
                Toast.makeText(this, "请先连接 BLE 设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (currentLocation == null) {
                Toast.makeText(this, "位置未获取", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 启动导航引擎
            navEngine.start(waypoints)
            updateUIFromResult(navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected))
            // 启动循环
            startNavLoop()
        }

        btnStopNav.setOnClickListener {
            navEngine.stop()
            updateUIFromResult(NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "已停止",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f
            ))
            // 停止循环由引擎状态决定
        }

        // 大按钮模式切换
        btnToggleBigMode.setOnClickListener {
            if (overlayBigButtons.visibility == View.VISIBLE) {
                overlayBigButtons.visibility = View.GONE
            } else {
                overlayBigButtons.visibility = View.VISIBLE
                syncOverlayUI()
            }
        }

        overlayClose.setOnClickListener {
            overlayBigButtons.visibility = View.GONE
        }

        overlayBtnStart.setOnClickListener {
            btnStartNav.performClick()
        }
        overlayBtnStop.setOnClickListener {
            btnStopNav.performClick()
        }

        btnLocate.setOnClickListener {
            val loc = currentLocation
            if (loc == null) {
                Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isFollowing = true
            val style = MyLocationStyle()
            style.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
            aMap.myLocationStyle = style

            aMap.setOnMapTouchListener { event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (isFollowing) {
                        cancelFollowing()
                    }
                }
            }

            Toast.makeText(this, "进入跟随模式，拖动地图退出", Toast.LENGTH_SHORT).show()
        }

        btnRemoteControl.setOnClickListener {
            toggleRemoteConnection()
        }
        btnSaveWaypoints.setOnClickListener {
            saveWaypointsToFile()
        }

        btnLoadWaypoints.setOnClickListener {
            loadWaypointsFromFile()
        }

        btnExportWaypoints.setOnClickListener {
            exportWaypoints()
        }

        handler.postDelayed({
            connectBle()
        }, 500)

        switchTab(true)
        btnRemoteControl.text = "📡 连接远程"
    }
    override fun onBackPressed() {
        if (overlayBigButtons.visibility == View.VISIBLE) {
            overlayBigButtons.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }
    // ---------- 导航循环 ----------
    private fun startNavLoop() {
        navRunnable = object : Runnable {
            override fun run() {
                val result = navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected)
                // 更新 UI
                updateUIFromResult(result)
                // 发送控制指令
                if (result.isNavigating || result.isCalibrating) {
                    bleController.sendControl(result.speed, result.turn, stop = result.stop)
                } else {
                    // 非导航状态，发送停止
                    bleController.sendControl(0f, 0f, stop = true)
                    // 如果引擎停止，则结束循环
                    if (!result.isNavigating) {
                        handler.removeCallbacks(navRunnable!!)
                        navRunnable = null
                        return
                    }
                }
                // 继续循环
                handler.postDelayed(this, navInterval)
            }
        }
        handler.post(navRunnable!!)
    }

    // ---------- 更新 UI ----------
    private fun updateUIFromResult(result: NavigationResult) {
        runOnUiThread {
            tvStatus.text = "状态: ${result.statusMessage}"
            tvCurrentTarget.text = if (result.isNavigating && result.currentTargetIndex >= 0 && result.currentTargetIndex < waypoints.size) {
                val p = waypoints[result.currentTargetIndex]
                "目标[${result.currentTargetIndex + 1}]: ${"%.4f".format(p.latitude)}, ${"%.4f".format(p.longitude)}"
            } else {
                "目标: 无"
            }
            tvSpeed.text = "速度: ${"%.2f".format(result.speed)} m/s"
            tvTurn.text = "转向: ${"%.1f".format(result.turn)} °/s"
            if (result.isNavigating && result.currentTargetIndex >= 0) {
                tvInfo.text = "距离: ${"%.1f".format(result.distanceToTarget)} m  方位: ${"%.1f".format(result.targetBearing)}°"
            } else {
                tvInfo.text = ""
            }

            btnStartNav.isEnabled = !result.isNavigating && !result.isCalibrating
            btnStopNav.isEnabled = result.isNavigating || result.isCalibrating
            overlayBtnStart.isEnabled = btnStartNav.isEnabled
            overlayBtnStop.isEnabled = btnStopNav.isEnabled

            syncOverlayUI()

            // 更新地图引导线、高亮等
            updateGuideLine()
            updateHighlightCircle()
        }
    }

    // ---------- 同步覆盖层 UI ----------
    private fun syncOverlayUI() {
        overlayStatus.text = tvStatus.text
        overlayTarget.text = tvCurrentTarget.text
        overlaySpeed.text = tvSpeed.text
        overlayBtnStop.isEnabled = btnStopNav.isEnabled
        overlayBtnStart.isEnabled = btnStartNav.isEnabled
    }

    // ==================== 原有功能保留（仅修改导航相关） ====================

    private fun cancelFollowing() {
        if (!isFollowing) return
        isFollowing = false
        aMap.setOnMapTouchListener(null)
        val style = MyLocationStyle()
        style.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
        aMap.myLocationStyle = style
        Toast.makeText(this, "已退出跟随模式", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRemoteConnection() {
        if (remoteConnecting) {
            Toast.makeText(this, "正在连接中...", Toast.LENGTH_SHORT).show()
            return
        }
        if (remoteEnabled) {
            disconnectRemote()
        } else {
            connectRemote()
        }
    }

    private fun connectRemote() {
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        val host = prefs.getString("remote_host", "") ?: ""
        val port = prefs.getString("remote_port", "9999")?.toIntOrNull() ?: 9999
        if (host.isEmpty()) {
            Toast.makeText(this, "请先在设置中配置服务器地址", Toast.LENGTH_LONG).show()
            return
        }
        if (remoteUsername.isEmpty()) {
            Toast.makeText(this, "请先在设置中配置用户名", Toast.LENGTH_LONG).show()
            return
        }
        if (remoteTargetId.isEmpty()) {
            showTargetIdDialog()
            return
        }

        remoteConnecting = true
        btnRemoteControl.text = "连接中..."
        btnRemoteControl.isEnabled = false

        relayClient = RelayClient(
            serverHost = host,
            serverPort = port,
            onMessageReceived = { from, payload ->
                if (from == remoteTargetId || remoteTargetId.isEmpty()) {
                    handleRemoteCommand(payload)
                }
            },
            onStatusChanged = { status ->
                runOnUiThread {
                    if (status.contains("注册成功")) {
                        remoteEnabled = true
                        remoteConnecting = false
                        btnRemoteControl.text = "📡 断开远程"
                        btnRemoteControl.isEnabled = true
                        RemoteControlHelper.relayClient = relayClient
                        RemoteControlHelper.targetId = remoteTargetId
                        startStatusSending()
                        Toast.makeText(this@AutoDriveActivity, "远程连接成功", Toast.LENGTH_SHORT).show()
                    } else if (status.contains("断开") || status.contains("错误")) {
                        remoteEnabled = false
                        remoteConnecting = false
                        btnRemoteControl.text = "📡 连接远程"
                        btnRemoteControl.isEnabled = true
                        stopStatusSending()
                        Toast.makeText(this@AutoDriveActivity, "远程断开", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        val deviceId = if (remoteUsername.isNotEmpty()) remoteUsername else Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        CoroutineScope(Dispatchers.IO).launch {
            val success = relayClient?.connect(deviceId) ?: false
            withContext(Dispatchers.Main) {
                if (!success) {
                    remoteConnecting = false
                    btnRemoteControl.text = "📡 连接远程"
                    btnRemoteControl.isEnabled = true
                    Toast.makeText(this@AutoDriveActivity, "连接失败", Toast.LENGTH_SHORT).show()
                } else {
                    if (!remoteEnabled) {
                        remoteEnabled = true
                        remoteConnecting = false
                        btnRemoteControl.text = "📡 断开远程"
                        btnRemoteControl.isEnabled = true
                        RemoteControlHelper.relayClient = relayClient
                        RemoteControlHelper.targetId = remoteTargetId
                        startStatusSending()
                        Toast.makeText(this@AutoDriveActivity, "远程连接成功", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        handler.postDelayed({
            if (remoteConnecting && !remoteEnabled) {
                remoteConnecting = false
                btnRemoteControl.text = "📡 连接远程"
                btnRemoteControl.isEnabled = true
                Toast.makeText(this, "连接超时", Toast.LENGTH_SHORT).show()
                relayClient?.disconnect()
                relayClient = null
            }
        }, 15000)
    }

    private fun disconnectRemote() {
        relayClient?.disconnect()
        relayClient = null
        remoteEnabled = false
        remoteConnecting = false
        btnRemoteControl.text = "📡 连接远程"
        btnRemoteControl.isEnabled = true
        stopStatusSending()
        Toast.makeText(this, "已断开远程", Toast.LENGTH_SHORT).show()
    }

    private fun showTargetIdDialog() {
        val input = EditText(this)
        input.hint = "输入目标设备ID"
        AlertDialog.Builder(this)
            .setTitle("输入远程目标ID")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    remoteTargetId = id
                    getSharedPreferences("car_config", Context.MODE_PRIVATE).edit().putString("remote_target_id", id).apply()
                    connectRemote()
                } else {
                    Toast.makeText(this, "ID不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun initRemoteControl() {
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        remoteUsername = prefs.getString("remote_username", "") ?: ""
        remoteTargetId = prefs.getString("remote_target_id", "") ?: ""

        if (RemoteControlHelper.relayClient != null && RemoteControlHelper.targetId.isNotEmpty()) {
            relayClient = RemoteControlHelper.relayClient
            remoteTargetId = RemoteControlHelper.targetId
            if (relayClient?.isConnected() == true) {
                remoteEnabled = true
                btnRemoteControl.text = "📡 断开远程"
                startStatusSending()
            }
        }
    }

    private fun switchTab(showControl: Boolean) {
        if (showControl) {
            scrollControl.visibility = View.VISIBLE
            pagePoints.visibility = View.GONE
            tabControl.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.text_primary))
            tabPoints.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.text_secondary))
        } else {
            scrollControl.visibility = View.GONE
            pagePoints.visibility = View.VISIBLE
            tabControl.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.text_secondary))
            tabPoints.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.text_primary))
        }
        scrollControl.smoothScrollTo(0, 0)
    }

    private fun selectWaypoint(position: Int) {
        if (position < 0 || position >= waypointMarkers.size) return
        if (selectedMarkerIndex == position) return
        clearSelectedMarker()
        val marker = waypointMarkers[position]
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        selectedMarkerIndex = position
        lvWaypoints.setItemChecked(position, true)

        if (remoteEnabled) {
            sendRemoteCommand("select_waypoint", mapOf("index" to position))
        }
    }

    private fun clearSelectedMarker() {
        if (selectedMarkerIndex != -1 && selectedMarkerIndex < waypointMarkers.size) {
            val marker = waypointMarkers[selectedMarkerIndex]
            marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        }
        selectedMarkerIndex = -1
        lvWaypoints.clearChoices()
    }

    private fun addWaypoint(point: LatLonPoint) {
        val marker = aMap.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title("目标点 ${waypoints.size + 1}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        waypointMarkers.add(marker)

        val circle = aMap.addCircle(
            CircleOptions()
                .center(LatLng(point.latitude, point.longitude))
                .radius(navConfig.arrivalDistance.toDouble())
                .strokeColor(Color.argb(180, 255, 0, 0))
                .strokeWidth(2f)
                .fillColor(Color.argb(30, 255, 0, 0))
        )
        waypointCircles.add(circle)

        waypoints.add(point)
        val display = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
        waypointDisplayList.add(display)
        waypointAdapter.notifyDataSetChanged()
        val newPos = waypoints.size - 1
        lvWaypoints.setItemChecked(newPos, true)
        selectWaypoint(newPos)

        updatePathLine()
        updateGuideLine()
    }

    private fun deleteWaypoint(position: Int) {
        if (position < 0 || position >= waypoints.size) return

        if (selectedMarkerIndex == position) {
            clearSelectedMarker()
        }
        waypointMarkers[position].remove()
        waypointMarkers.removeAt(position)
        waypointCircles[position].remove()
        waypointCircles.removeAt(position)
        waypoints.removeAt(position)
        waypointDisplayList.removeAt(position)
        waypointAdapter.notifyDataSetChanged()
        updatePathLine()
        updateGuideLine()

        // 如果导航中且删除的是当前目标点之后，需要更新引擎状态（但引擎内部维护索引，我们无法直接修改）
        // 建议：如果导航中，停止导航并清空引擎状态
        if (navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected).isNavigating) {
            navEngine.stop()
            updateUIFromResult(NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "路径点已修改，导航停止",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f
            ))
            handler.removeCallbacks(navRunnable!!)
            navRunnable = null
        }
        lvWaypoints.clearChoices()
    }

    private fun clearAllWaypoints() {
        clearSelectedMarker()
        waypointMarkers.forEach { it.remove() }
        waypointMarkers.clear()
        waypointCircles.forEach { it.remove() }
        waypointCircles.clear()
        waypoints.clear()
        waypointDisplayList.clear()
        waypointAdapter.notifyDataSetChanged()
        updatePathLine()
        updateGuideLine()
        // 停止导航
        if (navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected).isNavigating) {
            navEngine.stop()
            updateUIFromResult(NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "已清空路径点",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f
            ))
            handler.removeCallbacks(navRunnable!!)
            navRunnable = null
        }
        lvWaypoints.clearChoices()
        targetCircle?.remove()
        targetCircle = null
    }

    private fun updatePathLine() {
        pathLine?.remove()
        pathLine = null
        if (waypoints.size < 2) return
        val latLngs = waypoints.map { LatLng(it.latitude, it.longitude) }
        pathLine = aMap.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .color(Color.RED)
                .width(6f)
                .setDottedLine(true)
                .geodesic(true)
        )
    }

    private fun updateGuideLine() {
        guideLine?.remove()
        guideLine = null
        val loc = currentLocation ?: return
        if (waypoints.isEmpty()) return

        val currentLatLng = LatLng(loc.latitude, loc.longitude)

        // 获取引擎当前目标索引
        val result = navEngine.update(loc, deviceBearing, rollVelocity, isBleConnected)
        val goal = if (result.isNavigating && result.currentTargetIndex >= 0 && result.currentTargetIndex < waypoints.size) {
            // 使用引擎计算引导点（但引擎未提供接口，我们直接使用引擎内部的 computePathGoal？但它是私有的）
            // 简单做法：直接显示到当前目标点的连线
            waypoints[result.currentTargetIndex]
        } else {
            if (waypoints.isEmpty()) return
            waypoints[0]
        }

        val targetLatLng = LatLng(goal.latitude, goal.longitude)

        guideLine = aMap.addPolyline(
            PolylineOptions()
                .add(currentLatLng, targetLatLng)
                .color(Color.RED)
                .width(6f)
                .setDottedLine(!result.isNavigating)
                .geodesic(true)
        )
    }

    private fun updateHighlightCircle() {
        targetCircle?.remove()
        targetCircle = null
        val result = navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected)
        if (!result.isNavigating || result.currentTargetIndex < 0 || result.currentTargetIndex >= waypoints.size) return
        val target = waypoints[result.currentTargetIndex]
        val latLng = LatLng(target.latitude, target.longitude)
        targetCircle = aMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(navConfig.arrivalDistance.toDouble())
                .strokeColor(Color.YELLOW)
                .strokeWidth(6f)
                .fillColor(Color.argb(0, 255, 255, 0))
        )
    }

    private fun updateAllCirclesRadius() {
        waypointCircles.forEach { it.radius = navConfig.arrivalDistance.toDouble() }
        targetCircle?.radius = navConfig.arrivalDistance.toDouble()
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location != null && location.errorCode == 0) {
            currentLocation = location
            updateAllLines()

            if (isFirstLocation) {
                isFirstLocation = false
                val latLng = LatLng(location.latitude, location.longitude)
                handler.postDelayed({
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
                }, 100)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ORIENTATION -> {
                    deviceBearing = it.values[0]
                    if (deviceBearing < 0) deviceBearing += 360f
                    updateHeadingLine()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    deviceBearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    if (deviceBearing < 0) deviceBearing += 360f
                    updateHeadingLine()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    rollVelocity = Math.toDegrees(it.values[0].toDouble()).toFloat()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateAllLines() {
        updateHeadingLine()
        updatePathLine()
        updateGuideLine()
    }

    private fun updateHeadingLine() {
        headingLine?.remove()
        val loc = currentLocation ?: return
        val heading = if (deviceBearing != 0f) deviceBearing else loc.bearing
        val endPoint = calculateDestination(loc.latitude, loc.longitude, heading, headingLineLength)
        headingLine = aMap.addPolyline(
            PolylineOptions()
                .add(LatLng(loc.latitude, loc.longitude), endPoint)
                .color(Color.GREEN)
                .width(6f)
                .geodesic(true)
        )
    }

    private fun connectBle() {
        if (!bleController.isBleSupported()) {
            tvBleStatus.text = "BLE: 不支持"
            return
        }
        if (!checkBlePermissions()) {
            if (!isRequestingPermission) {
                requestBlePermissions()
            }
            return
        }
        if (!bleController.isBluetoothEnabled()) {
            tvBleStatus.text = "BLE: 请开启蓝牙"
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        tvBleStatus.text = "BLE: 扫描中..."
        bleController.startScan()
        handler.postDelayed({
            if (!isBleConnected) {
                bleController.stopScan()
                tvBleStatus.text = "BLE: 连接超时"
            }
        }, 10000)
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        if (isRequestingPermission) return
        isRequestingPermission = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1002)
    }

    private fun checkBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkLocationPermission()
        } else {
            return checkLocationPermission()
        }
    }

    private fun requestBlePermissions() {
        if (isRequestingPermission) return
        isRequestingPermission = true
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, perms, 1003)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> {
                isRequestingPermission = false
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    locationClient.startLocation()
                } else {
                    Toast.makeText(this, "需要位置权限", Toast.LENGTH_SHORT).show()
                }
            }
            1003 -> {
                isRequestingPermission = false
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    connectBle()
                } else {
                    Toast.makeText(this, "需要蓝牙权限", Toast.LENGTH_SHORT).show()
                    tvBleStatus.text = "BLE: 权限被拒绝"
                }
            }
        }
    }

    // ---------- 远程指令处理 ----------
    private fun startStatusSending() {
        statusSendRunnable = object : Runnable {
            override fun run() {
                if (!remoteEnabled || relayClient == null || relayClient?.isConnected() != true) {
                    return
                }
                sendCurrentStatus()
                handler.postDelayed(this, statusInterval)
            }
        }
        handler.post(statusSendRunnable!!)
    }

    private fun stopStatusSending() {
        statusSendRunnable?.let { handler.removeCallbacks(it) }
        statusSendRunnable = null
    }

    private fun sendCurrentStatus() {
        val loc = currentLocation ?: return
        try {
            val json = JSONObject().apply {
                put("type", "status")
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("bearing", if (deviceBearing != 0f) deviceBearing else loc.bearing)
                val result = navEngine.update(loc, deviceBearing, rollVelocity, isBleConnected)
                put("nav_status", when {
                    result.isNavigating -> "navigating"
                    result.isCalibrating -> "calibrating"
                    else -> "idle"
                })
                put("is_navigating", result.isNavigating)
                put("is_calibrating", result.isCalibrating)

                val pointsArray = JSONArray()
                waypoints.forEach { pt ->
                    val ptObj = JSONObject().apply {
                        put("lat", pt.latitude)
                        put("lng", pt.longitude)
                    }
                    pointsArray.put(ptObj)
                }
                put("waypoints", pointsArray)
                put("current_target", result.currentTargetIndex)
                put("total_waypoints", waypoints.size)
            }
            val payload = json.toString()
            CoroutineScope(Dispatchers.IO).launch {
                relayClient?.sendMessage(remoteTargetId, payload)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun sendRemoteCommand(type: String, params: Map<String, Any> = emptyMap()) {
        if (!remoteEnabled || relayClient?.isConnected() != true) return
        if (remoteTargetId.isEmpty()) return

        try {
            val json = JSONObject().apply {
                put("type", type)
                params.forEach { put(it.key, it.value) }
            }
            CoroutineScope(Dispatchers.IO).launch {
                relayClient?.sendMessage(remoteTargetId, json.toString())
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun handleRemoteCommand(payload: String) {
        try {
            val json = JSONObject(payload)
            val type = json.optString("type")
            when (type) {
                "remote" -> {
                    val speed = json.optDouble("speed", 0.0).toFloat()
                    val turn = json.optDouble("turn", 0.0).toFloat()
                    runOnUiThread {
                        bleController.sendControl(speed, turn, stop = false)
                        tvSpeed.text = String.format(Locale.US, "速度: %.2f m/s", speed)
                        tvTurn.text = String.format(Locale.US, "转向: %.1f °/s", turn)
                        tvInfo.text = "远程控制中..."
                        syncOverlayUI()
                        handler.postDelayed({
                            if (!navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected).isNavigating) {
                                bleController.sendControl(0f, 0f, stop = true)
                            }
                        }, 5000)
                    }
                }
                "start_auto" -> {
                    runOnUiThread {
                        if (waypoints.isNotEmpty()) {
                            navEngine.start(waypoints)
                            updateUIFromResult(navEngine.update(currentLocation, deviceBearing, rollVelocity, isBleConnected))
                            startNavLoop()
                            Toast.makeText(this, "远程启动导航", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "stop_auto" -> {
                    runOnUiThread {
                        navEngine.stop()
                        updateUIFromResult(NavigationResult(
                            speed = 0f, turn = 0f, stop = true,
                            statusMessage = "远程停止",
                            isNavigating = false, isCalibrating = false,
                            currentTargetIndex = -1,
                            distanceToTarget = 0f, targetBearing = 0f
                        ))
                        handler.removeCallbacks(navRunnable!!)
                        navRunnable = null
                        Toast.makeText(this, "远程停止导航", Toast.LENGTH_SHORT).show()
                    }
                }
                "add_waypoint" -> {
                    val lat = json.optDouble("lat", Double.NaN)
                    val lng = json.optDouble("lng", Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        runOnUiThread {
                            val point = LatLonPoint(lat, lng)
                            addWaypoint(point)
                            Toast.makeText(this, "远程添加目标点", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "delete_waypoint" -> {
                    val index = json.optInt("index", -1)
                    if (index in waypoints.indices) {
                        runOnUiThread {
                            deleteWaypoint(index)
                            Toast.makeText(this, "远程删除目标点", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "clear_waypoints" -> {
                    runOnUiThread {
                        clearAllWaypoints()
                        Toast.makeText(this, "远程清空所有目标点", Toast.LENGTH_SHORT).show()
                    }
                }
                "select_waypoint" -> {
                    val index = json.optInt("index", -1)
                    if (index in waypoints.indices) {
                        runOnUiThread {
                            selectWaypoint(index)
                            lvWaypoints.setItemChecked(index, true)
                            Toast.makeText(this, "远程选择目标点 ${index + 1}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    // 未知指令
                }
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    // ---------- 工具函数 ----------
    private fun calculateDestination(lat: Double, lng: Double, bearing: Float, distanceMeters: Double): LatLng {
        val R = 6371000.0
        val br = Math.toRadians(bearing.toDouble())
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lng)
        val d = distanceMeters / R

        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(br))
        val lon2 = lon1 + Math.atan2(Math.sin(br) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2))

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    // ---------- 保存/加载/导出 ----------
    private fun saveWaypointsToFile() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, "没有路径点可保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val jsonArray = JSONArray()
            waypoints.forEach { point ->
                val obj = JSONObject().apply {
                    put("lat", point.latitude)
                    put("lng", point.longitude)
                }
                jsonArray.put(obj)
            }
            val fileName = "waypoints.json"
            val file = File(filesDir, fileName)
            file.writeText(jsonArray.toString())
            Toast.makeText(this, "已保存 ${waypoints.size} 个点到 $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun loadWaypointsFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        startActivityForResult(intent, REQUEST_LOAD_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOAD_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val jsonString = inputStream.bufferedReader().readText()
                        val jsonArray = JSONArray(jsonString)
                        if (jsonArray.length() == 0) {
                            Toast.makeText(this, "文件为空", Toast.LENGTH_SHORT).show()
                            return
                        }
                        // 停止导航
                        navEngine.stop()
                        handler.removeCallbacks(navRunnable!!)
                        navRunnable = null
                        clearAllWaypoints()

                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val lat = obj.getDouble("lat")
                            val lng = obj.getDouble("lng")
                            addWaypoint(LatLonPoint(lat, lng))
                        }
                        Toast.makeText(this, "已加载 ${waypoints.size} 个路径点", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                }
            } ?: Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportWaypoints() {
        if (waypoints.isEmpty()) {
            Toast.makeText(this, "没有路径点可导出", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val jsonArray = JSONArray()
            waypoints.forEach { point ->
                val obj = JSONObject().apply {
                    put("lat", point.latitude)
                    put("lng", point.longitude)
                }
                jsonArray.put(obj)
            }
            val jsonString = jsonArray.toString()

            val tempFile = File(cacheDir, "waypoints_export.json")
            tempFile.writeText(jsonString)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                tempFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "导出路径点"))
        } catch (e: Exception) {
            try {
                val fallbackJson = JSONArray().apply {
                    waypoints.forEach { point ->
                        put(JSONObject().apply {
                            put("lat", point.latitude)
                            put("lng", point.longitude)
                        })
                    }
                }.toString()
                val textIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fallbackJson)
                    putExtra(Intent.EXTRA_SUBJECT, "路径点数据")
                }
                startActivity(Intent.createChooser(textIntent, "导出路径点（文本）"))
            } catch (e2: Exception) {
                Toast.makeText(this, "导出失败: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 生命周期 ----------
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        val newConfig = NavigationConfig(
            maxSpeed = prefs.getFloat("nav_max_speed", 1.5f),
            maxTurn = prefs.getFloat("nav_max_turn", 50f),
            arrivalDistance = prefs.getFloat("arrival_distance", 10f),
            pathLookahead = 5f,
            turnDeadZone = prefs.getFloat("turn_dead_zone", 2f),
            rollThreshold = prefs.getFloat("roll_threshold", 15f),
            calibrationTime = prefs.getFloat("calibration_time", 2.0f),
            calibrationAngle = prefs.getFloat("calibration_angle", 5.0f)
        )
        if (newConfig != navConfig) {
            navConfig = newConfig
            navEngine.updateConfig(navConfig)
            updateAllCirclesRadius()
        }
        if (remoteEnabled) {
            btnRemoteControl.text = "📡 断开远程"
        } else {
            btnRemoteControl.text = "📡 连接远程"
        }
        if (relayClient?.isConnected() == true && !remoteEnabled) {
            remoteEnabled = true
            btnRemoteControl.text = "📡 断开远程"
            startStatusSending()
        }
        syncOverlayUI()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        locationClient.stopLocation()
        locationClient.onDestroy()
        bleController.disconnect()
        stopStatusSending()
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        aMap.setOnMapTouchListener(null)
    }
}

object RemoteControlHelper {
    var relayClient: RelayClient? = null
    var targetId: String = ""
}