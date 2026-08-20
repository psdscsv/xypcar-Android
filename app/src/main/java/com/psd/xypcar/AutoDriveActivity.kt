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
import android.util.Log
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

    // Tab 按钮
    private lateinit var tabControl: Button
    private lateinit var tabPoints: Button
    private lateinit var scrollControl: ScrollView
    private lateinit var pagePoints: LinearLayout

    // ---------- 多点导航数据 ----------
    private val waypoints = mutableListOf<LatLonPoint>()
    private val waypointMarkers = mutableListOf<Marker>()
    private val waypointCircles = mutableListOf<Circle>()
    private var currentTargetIndex = 0
    private var isNavigating = false

    private var selectedMarkerIndex = -1

    private lateinit var waypointAdapter: ArrayAdapter<String>
    private val waypointDisplayList = mutableListOf<String>()

    private val REQUEST_LOAD_FILE = 1001

    // ---------- 高德定位 ----------
    private lateinit var locationClient: AMapLocationClient
    private var currentLocation: AMapLocation? = null

    private var isFirstLocation = true  // 用于标记是否首次定位

    // ---------- 传感器 ----------
    private lateinit var sensorManager: SensorManager
    private var deviceBearing = 0f

    // ---------- 导航循环 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var navRunnable: Runnable? = null
    private val navInterval = 100L

    // ---------- BLE ----------
    private var isBleConnected = false

    // ---------- 控制参数 ----------
    private var navMaxSpeed = 1.5f
    private var navMaxTurn = 50f
    private var targetArrivalDistance = 10f
    private var calibrationTime = 2.0f
    private var calibrationAngle = 5.0f
    // 沿"当前点→下一点"连线导航时的前瞻距离（米）
    private var pathLookahead = 5f

    private var targetCircle: Circle? = null

    // ---------- 校准状态 ----------
    private var isCalibrating = false
    private val angleHistory = mutableListOf<Float>()
    private val sampleInterval = 200L
    private var calibrationRunnable: Runnable? = null

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
        // 替换为新的全屏方式
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
        navMaxSpeed = prefs.getFloat("nav_max_speed", 1.5f)
        navMaxTurn = prefs.getFloat("nav_max_turn", 50f)
        targetArrivalDistance = prefs.getFloat("arrival_distance", 10f)
        calibrationTime = prefs.getFloat("calibration_time", 2.0f)
        calibrationAngle = prefs.getFloat("calibration_angle", 5.0f)
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
                    if (isNavigating || isCalibrating) stopNavigation()
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
            startCalibration()
        }

        btnStopNav.setOnClickListener {
            if (isCalibrating) {
                cancelCalibration()
            } else if (isNavigating) {
                stopNavigation()
            }
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

        // 创建新的 RelayClient（旧对象已在 disconnect 时置 null）
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
                    // 保留回调更新，作为补充
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
                    // 连接失败
                    remoteConnecting = false
                    btnRemoteControl.text = "📡 连接远程"
                    btnRemoteControl.isEnabled = true
                    Toast.makeText(this@AutoDriveActivity, "连接失败", Toast.LENGTH_SHORT).show()
                } else {
                    // 连接成功，确保 UI 更新（防止回调未触发）
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

        // 超时保护（15秒）
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
        relayClient = null          // 释放引用，下次连接重新创建
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
                .radius(targetArrivalDistance.toDouble())
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

        if (isNavigating && position == currentTargetIndex) {
            stopNavigation()
        } else if (isNavigating && position < currentTargetIndex) {
            currentTargetIndex--
        }

        if (selectedMarkerIndex >= waypoints.size) {
            selectedMarkerIndex = -1
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
        if (isNavigating) stopNavigation()
        lvWaypoints.clearChoices()
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

        // 导航中显示路径连线上应逼近的引导点；未导航时显示首个目标点
        val goal = if (isNavigating) {
            computePathGoal()
        } else {
            if (waypoints.isEmpty()) return
            waypoints[0]
        } ?: return

        val targetLatLng = LatLng(goal.latitude, goal.longitude)

        guideLine = aMap.addPolyline(
            PolylineOptions()
                .add(currentLatLng, targetLatLng)
                .color(Color.RED)
                .width(6f)
                .setDottedLine(!isNavigating)
                .geodesic(true)
        )
    }

    private fun updateHighlightCircle() {
        targetCircle?.remove()
        targetCircle = null
        if (!isNavigating) return
        if (currentTargetIndex >= waypoints.size) return
        val target = waypoints[currentTargetIndex]
        val latLng = LatLng(target.latitude, target.longitude)
        targetCircle = aMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(targetArrivalDistance.toDouble())
                .strokeColor(Color.YELLOW)
                .strokeWidth(6f)
                .fillColor(Color.argb(0, 255, 255, 0))
        )
    }

    private fun updateAllCirclesRadius() {
        waypointCircles.forEach { it.radius = targetArrivalDistance.toDouble() }
        targetCircle?.radius = targetArrivalDistance.toDouble()
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location != null && location.errorCode == 0) {
            currentLocation = location
            updateAllLines()

            //首次获取位置时自动移动地图并设定缩放
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

    private fun startCalibration() {
        if (isCalibrating || isNavigating) return
        isCalibrating = true
        angleHistory.clear()
        tvStatus.text = "状态: 校准中..."
        btnStartNav.isEnabled = false
        btnStopNav.isEnabled = true
        btnStopNav.text = "取消校准"

        calibrationRunnable = object : Runnable {
            override fun run() {
                if (!isCalibrating) return
                val currentAngle = deviceBearing
                angleHistory.add(currentAngle)

                val requiredSamples = (calibrationTime * 1000 / sampleInterval).toInt()
                while (angleHistory.size > requiredSamples) {
                    angleHistory.removeAt(0)
                }

                if (angleHistory.size >= requiredSamples) {
                    val min = angleHistory.minOrNull() ?: 0f
                    val max = angleHistory.maxOrNull() ?: 0f
                    val range = max - min
                    if (range <= calibrationAngle) {
                        runOnUiThread {
                            tvStatus.text = "状态: 校准通过，启动导航..."
                            isCalibrating = false
                            btnStopNav.text = "停止导航"
                            startNavigation()
                        }
                        return
                    } else {
                        runOnUiThread {
                            tvStatus.text = String.format(Locale.US, "状态: 校准中 (波动 %.1f°)", range)
                        }
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "状态: 校准中... 请保持设备稳定"
                    }
                }
                handler.postDelayed(this, sampleInterval)
            }
        }
        handler.post(calibrationRunnable!!)
    }

    private fun cancelCalibration() {
        isCalibrating = false
        calibrationRunnable?.let { handler.removeCallbacks(it) }
        calibrationRunnable = null
        angleHistory.clear()
        tvStatus.text = "状态: 已取消校准"
        btnStartNav.isEnabled = true
        btnStopNav.isEnabled = false
        btnStopNav.text = "停止导航"
    }

    private fun startNavigation() {
        if (waypoints.isEmpty()) return
        if (!isBleConnected) return

        currentTargetIndex = 0
        isNavigating = true
        isCalibrating = false

        tvStatus.text = "状态: 导航中..."
        btnStartNav.isEnabled = false
        btnStopNav.isEnabled = true
        btnStopNav.text = "停止导航"
        updateTargetDisplay()
        updateGuideLine()
        updateHighlightCircle()
        startNavLoop()
    }

    private fun startNavLoop() {
        navRunnable = object : Runnable {
            override fun run() {
                if (!isNavigating || !isBleConnected) {
                    stopNavigation()
                    return
                }
                performControl()
                handler.postDelayed(this, navInterval)
            }
        }
        handler.post(navRunnable!!)
    }

    private fun performControl() {
        if (currentTargetIndex >= waypoints.size) {
            stopNavigation()
            tvStatus.text = "状态: 所有目标点已到达"
            return
        }

        val target = waypoints[currentTargetIndex]
        val currentLoc = currentLocation ?: return

        val distance = distanceBetween(currentLoc.latitude, currentLoc.longitude,
            target.latitude, target.longitude)

        if (distance < targetArrivalDistance) {
            currentTargetIndex++
            updateTargetDisplay()
            updateHighlightCircle()
            if (currentTargetIndex >= waypoints.size) {
                stopNavigation()
                tvStatus.text = "状态: 所有目标点已到达"
                return
            }
            updateGuideLine()
            return
        }

        // 计算目标航向：
        // - 若存在下一个目标点，则沿"当前点→下一点"的连线导航：使车辆逼近该连线，
        //   前进方向指向下一个目标点（避免直接冲向目标点导致偏离路径/切角）
        // - 若为最后一个目标点，则直接导航向该点
        val targetBearing = if (currentTargetIndex + 1 < waypoints.size) {
            val next = waypoints[currentTargetIndex + 1]
            val goal = pathGoalOnSegment(currentLoc, target, next)
            bearingBetween(currentLoc.latitude, currentLoc.longitude, goal.latitude, goal.longitude)
        } else {
            bearingBetween(currentLoc.latitude, currentLoc.longitude,
                target.latitude, target.longitude)
        }

        val currentBearing = if (deviceBearing != 0f) {
            deviceBearing
        } else {
            if (currentLoc.bearing != 0f) currentLoc.bearing else 0f
        }

        var turnDiff = targetBearing - currentBearing
        if (turnDiff > 180) turnDiff -= 360
        if (turnDiff < -180) turnDiff += 360

        val maxTurnDiff = 45f
        val turnValue = (turnDiff / maxTurnDiff).coerceIn(-1f, 1f)

        val speedFactor = if (distance > 5f) 1f else (distance / 5f).coerceIn(0.2f, 1f)
        val speed = navMaxSpeed * speedFactor
        val turn = turnValue * navMaxTurn

        bleController.sendControl(speed, turn, stop = false)

        runOnUiThread {
            tvSpeed.text = String.format(Locale.US, "速度: %.2f m/s", speed)
            tvTurn.text = String.format(Locale.US, "转向: %.1f °/s", turn)
            tvInfo.text = String.format(Locale.US, "距离: %.1f m  方位: %.1f°", distance, targetBearing)
        }

        updateGuideLine()
    }

    private fun updateTargetDisplay() {
        if (currentTargetIndex < waypoints.size) {
            val p = waypoints[currentTargetIndex]
            tvCurrentTarget.text = String.format(Locale.US, "目标[%d]: %.4f, %.4f",
                currentTargetIndex + 1, p.latitude, p.longitude)
        } else {
            tvCurrentTarget.text = "目标: 已完成"
        }
    }

    private fun stopNavigation() {
        if (isCalibrating) {
            cancelCalibration()
            return
        }
        isNavigating = false
        navRunnable?.let { handler.removeCallbacks(it) }
        navRunnable = null
        bleController.sendControl(0f, 0f, stop = true)
        tvStatus.text = "状态: 已停止"
        tvCurrentTarget.text = "目标: 无"
        btnStartNav.isEnabled = true
        btnStopNav.isEnabled = false
        btnStopNav.text = "停止导航"
        runOnUiThread {
            tvSpeed.text = "速度: 0.0 m/s"
            tvTurn.text = "转向: 0.0 °/s"
        }
        targetCircle?.remove()
        targetCircle = null
        updateGuideLine()
    }

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
                put("nav_status", if (isNavigating) "navigating" else if (isCalibrating) "calibrating" else "idle")
                put("is_navigating", isNavigating)
                put("is_calibrating", isCalibrating)

                val pointsArray = JSONArray()
                waypoints.forEach { pt ->
                    val ptObj = JSONObject().apply {
                        put("lat", pt.latitude)
                        put("lng", pt.longitude)
                    }
                    pointsArray.put(ptObj)
                }
                put("waypoints", pointsArray)
                put("current_target", currentTargetIndex)
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
                        handler.postDelayed({
                            if (!isNavigating) {
                                bleController.sendControl(0f, 0f, stop = true)
                            }
                        }, 5000)
                    }
                }
                "start_auto" -> {
                    runOnUiThread {
                        if (!isNavigating && !isCalibrating && waypoints.isNotEmpty()) {
                            startNavigation()
                            Toast.makeText(this, "远程启动导航", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                "stop_auto" -> {
                    runOnUiThread {
                        if (isNavigating || isCalibrating) {
                            stopNavigation()
                            Toast.makeText(this, "远程停止导航", Toast.LENGTH_SHORT).show()
                        }
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

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(3)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[1]
    }

    /**
     * 沿路径连线导航的引导点计算。
     * 将车辆位置投影到"segStart→segEnd"连线段上，得到连线上离车辆最近的投影点 P；
     * 再从 P 沿线段方向（指向下一个目标点）前看 [pathLookahead] 米，得到引导目标点。
     * 这样车辆会先逼近最近目标点的连线，再沿连线驶向下一个目标点，避免偏离路径。
     */
    private fun pathGoalOnSegment(pos: AMapLocation, segStart: LatLonPoint, segEnd: LatLonPoint): LatLonPoint {
        val lookahead = pathLookahead.toDouble()

        val lat0 = pos.latitude
        val lng0 = pos.longitude

        // 以车辆当前位置为原点建立局部平面坐标（米），避免直接用经纬度投影产生畸变
        val R = 6371000.0
        val cosLat = Math.cos(Math.toRadians(lat0))
        fun toX(lat: Double, lng: Double): Double = R * Math.toRadians(lng - lng0) * cosLat
        fun toY(lat: Double, lng: Double): Double = R * Math.toRadians(lat - lat0)
        fun toLat(localY: Double): Double = lat0 + Math.toDegrees(localY / R)
        fun toLng(localX: Double): Double = lng0 + Math.toDegrees(localX / (R * cosLat))

        // 线段两端点的局部坐标
        val ax = toX(segStart.latitude, segStart.longitude)
        val ay = toY(segStart.latitude, segStart.longitude)
        val bx = toX(segEnd.latitude, segEnd.longitude)
        val by = toY(segEnd.latitude, segEnd.longitude)

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy

        if (segLenSq < 1e-9) {
            // 线段退化为点，直接返回终点
            return segEnd
        }

        // 车辆位于原点 (0,0)，起点→车辆 向量即 (-ax, -ay)
        // 投影参数 t = dot(起点→车辆, 段方向) / |段方向|^2，并夹取到 [0,1] 保证投影点在线段内
        var t = (-ax * dx - ay * dy) / segLenSq
        t = t.coerceIn(0.0, 1.0)

        // 连线上离车辆最近的投影点 P
        val px = ax + t * dx
        val py = ay + t * dy

        // 从 P 沿线段方向（指向下一个目标点）前看，但不超过本段长度
        val segLen = Math.sqrt(segLenSq)
        val goalDist = Math.min(lookahead, segLen)
        val ux = dx / segLen
        val uy = dy / segLen
        val gx = px + ux * goalDist
        val gy = py + uy * goalDist

        return LatLonPoint(toLat(gy), toLng(gx))
    }

    /**
     * 返回当前导航应逼近/前进的引导目标点：
     * - 若有下一个目标点，返回当前段连线上的引导点（方向指向下一个目标点）；
     * - 否则（最后一个目标点）直接返回该目标点。
     */
    private fun computePathGoal(): LatLonPoint? {
        val loc = currentLocation ?: return null
        val idx = currentTargetIndex
        if (idx >= waypoints.size) return null
        val target = waypoints[idx]
        if (idx + 1 < waypoints.size) {
            return pathGoalOnSegment(loc, target, waypoints[idx + 1])
        }
        return target
    }

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

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        val newSpeed = prefs.getFloat("nav_max_speed", 1.5f)
        val newTurn = prefs.getFloat("nav_max_turn", 50f)
        val newDist = prefs.getFloat("arrival_distance", 10f)
        val newTime = prefs.getFloat("calibration_time", 2.0f)
        val newAngle = prefs.getFloat("calibration_angle", 5.0f)
        if (newSpeed != navMaxSpeed || newTurn != navMaxTurn || newDist != targetArrivalDistance ||
            newTime != calibrationTime || newAngle != calibrationAngle) {
            navMaxSpeed = newSpeed
            navMaxTurn = newTurn
            targetArrivalDistance = newDist
            calibrationTime = newTime
            calibrationAngle = newAngle
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
    /**
     * 保存当前路径点到内部存储文件（waypoints.json）
     */
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

    /**
     * 从用户选择的文件加载路径点（覆盖当前列表）
     */
    private fun loadWaypointsFromFile() {
        // 使用系统文件选择器选择 JSON 文件
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            // 如果部分文件管理器不支持 application/json，可降级为 */*
            // type = "*/*"
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
                        // 停止导航，清空当前所有点
                        if (isNavigating || isCalibrating) stopNavigation()
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
    /**
     * 导出路径点（通过 Intent 分享）
     * 优先使用 FileProvider 分享 JSON 文件，若未配置则降级为纯文本分享
     */
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

            // 尝试通过文件分享（需要 FileProvider）
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
            // 如果 FileProvider 未配置，降级为纯文本分享
            try {
                // 重新获取 jsonString（因作用域问题，需重新生成）
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
}

object RemoteControlHelper {
    var relayClient: RelayClient? = null
    var targetId: String = ""
}