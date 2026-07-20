package com.psd.xypcar

import android.Manifest
import android.content.Context
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.*

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
    private lateinit var btnLocate: Button

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

    // ---------- 新增：列表选中高亮索引 ----------
    private var selectedMarkerIndex = -1   // -1 表示无高亮

    private lateinit var waypointAdapter: ArrayAdapter<String>
    private val waypointDisplayList = mutableListOf<String>()

    // ---------- 高德定位 ----------
    private lateinit var locationClient: AMapLocationClient
    private var currentLocation: AMapLocation? = null
    private var isFirstLocation = true

    // ---------- 传感器 ----------
    private lateinit var sensorManager: SensorManager
    private var deviceBearing = 0f

    // ---------- 导航循环 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var navRunnable: Runnable? = null
    private val navInterval = 100L

    // ---------- BLE ----------
    private var isBleConnected = false

    // ---------- 控制参数（导航独立） ----------
    private var navMaxSpeed = 1.5f
    private var navMaxTurn = 50f
    private var targetArrivalDistance = 10f

    // ---------- 当前目标高亮圆圈 ----------
    private var targetCircle: Circle? = null

    // ---------- 地图绘制元素 ----------
    private var headingLine: Polyline? = null
    private var pathLine: Polyline? = null
    private var guideLine: Polyline? = null
    private val headingLineLength = 30.0

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_autodrive)

        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        mapView = findViewById(R.id.autodrive_map)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isCompassEnabled = true
        aMap.isMyLocationEnabled = true
        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
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

        tabControl = findViewById(R.id.tab_control)
        tabPoints = findViewById(R.id.tab_points)
        scrollControl = findViewById(R.id.scroll_control)
        pagePoints = findViewById(R.id.page_points)

        waypointAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, waypointDisplayList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.setTextColor(Color.WHITE)
                return view
            }
        }
        lvWaypoints.adapter = waypointAdapter
        lvWaypoints.choiceMode = ListView.CHOICE_MODE_SINGLE

        // ========== 新增：列表点击事件，高亮地图 Marker ==========
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
        val deviceName = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"

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
                    if (isNavigating) stopNavigation()
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
            requestLocationPermissions()
        }

        // 地图长按添加点
        aMap.setOnMapLongClickListener { latLng ->
            val point = LatLonPoint(latLng.latitude, latLng.longitude)
            addWaypoint(point)
            Toast.makeText(this, "添加目标点 (${latLng.latitude}, ${latLng.longitude})", Toast.LENGTH_SHORT).show()
        }

        // 按钮事件
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
                // 删除前，如果该点被高亮，清除高亮
                if (selectedMarkerIndex == pos) {
                    clearSelectedMarker()
                }
                waypointMarkers[pos].remove()
                waypointMarkers.removeAt(pos)
                waypointCircles[pos].remove()
                waypointCircles.removeAt(pos)
                waypoints.removeAt(pos)
                waypointDisplayList.removeAt(pos)
                waypointAdapter.notifyDataSetChanged()
                updatePathLine()
                updateGuideLine()
                if (isNavigating && pos == currentTargetIndex) {
                    stopNavigation()
                } else if (isNavigating && pos < currentTargetIndex) {
                    currentTargetIndex--
                }
                // 如果删除后列表还有数据，且被删除的是最后一个且之前高亮，重置高亮
                if (selectedMarkerIndex >= waypoints.size) {
                    selectedMarkerIndex = -1
                }
                // 清除ListView选中状态
                lvWaypoints.clearChoices()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先选中一个点", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearPoints.setOnClickListener {
            // 清空所有，重置高亮
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
            startNavigation()
        }

        btnStopNav.setOnClickListener {
            stopNavigation()
        }

        btnLocate.setOnClickListener {
            moveToMyLocation()
        }

        connectBle()
        switchTab(true)
    }

    // ---------- Tab 切换 ----------
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

    // ========== 新增：选择目标点高亮 ==========
    private fun selectWaypoint(position: Int) {
        if (position < 0 || position >= waypointMarkers.size) return
        // 如果已经选中该点，不做任何事（也可以取消选中，但这里保持选中）
        if (selectedMarkerIndex == position) return
        // 清除之前的高亮
        clearSelectedMarker()
        // 设置新高亮
        val marker = waypointMarkers[position]
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)) // 蓝色高亮
        selectedMarkerIndex = position
        // 同步 ListView 选中项
        lvWaypoints.setItemChecked(position, true)
    }

    private fun clearSelectedMarker() {
        if (selectedMarkerIndex != -1 && selectedMarkerIndex < waypointMarkers.size) {
            val marker = waypointMarkers[selectedMarkerIndex]
            marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)) // 恢复红色
        }
        selectedMarkerIndex = -1
        lvWaypoints.clearChoices()
    }

    // ---------- 添加点 ----------
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
        // 添加后自动选中最新点（可选）
        val newPos = waypoints.size - 1
        lvWaypoints.setItemChecked(newPos, true)
        selectWaypoint(newPos) // 高亮地图

        updatePathLine()
        updateGuideLine()
    }

    // ---------- 更新路径线 ----------
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

    // ---------- 更新引导线 ----------
    private fun updateGuideLine() {
        guideLine?.remove()
        guideLine = null
        val loc = currentLocation ?: return
        if (waypoints.isEmpty()) return

        val currentLatLng = LatLng(loc.latitude, loc.longitude)
        val targetIdx = if (isNavigating) currentTargetIndex else 0
        if (targetIdx >= waypoints.size) return

        val target = waypoints[targetIdx]
        val targetLatLng = LatLng(target.latitude, target.longitude)

        guideLine = aMap.addPolyline(
            PolylineOptions()
                .add(currentLatLng, targetLatLng)
                .color(Color.RED)
                .width(6f)
                .setDottedLine(!isNavigating)
                .geodesic(true)
        )
    }

    // ---------- 更新高亮圆圈 ----------
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

    // ---------- 更新所有圆圈半径 ----------
    private fun updateAllCirclesRadius() {
        waypointCircles.forEach { it.radius = targetArrivalDistance.toDouble() }
        targetCircle?.radius = targetArrivalDistance.toDouble()
    }

    // ---------- 定位回调 ----------
    override fun onLocationChanged(location: AMapLocation?) {
        if (location != null && location.errorCode == 0) {
            currentLocation = location
            if (isFirstLocation) {
                isFirstLocation = false
                moveToMyLocation()
            }
            updateAllLines()
        }
    }

    // ---------- 传感器 ----------
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

    // ---------- BLE ----------
    private fun connectBle() {
        if (!bleController.isBleSupported()) {
            tvBleStatus.text = "BLE: 不支持"
            return
        }
        if (!checkBlePermissions()) {
            requestBlePermissions()
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

    // ---------- 权限 ----------
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
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
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, perms, 1003)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                locationClient.startLocation()
            } else {
                Toast.makeText(this, "需要位置权限", Toast.LENGTH_SHORT).show()
            }
            1003 -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectBle()
            } else {
                Toast.makeText(this, "需要蓝牙权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun moveToMyLocation() {
        val loc = currentLocation ?: run {
            Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
            return
        }
        val latLng = LatLng(loc.latitude, loc.longitude)
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
    }

    // ---------- 导航控制 ----------
    private fun startNavigation() {
        if (waypoints.isEmpty()) return
        if (!isBleConnected) return
        currentTargetIndex = 0
        isNavigating = true
        tvStatus.text = "状态: 导航中..."
        btnStartNav.isEnabled = false
        btnStopNav.isEnabled = true
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

    // ---------- 核心控制循环 ----------
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

        val targetBearing = bearingBetween(currentLoc.latitude, currentLoc.longitude,
            target.latitude, target.longitude)

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

    // ---------- 停止导航 ----------
    private fun stopNavigation() {
        isNavigating = false
        navRunnable?.let { handler.removeCallbacks(it) }
        navRunnable = null
        bleController.sendControl(0f, 0f, stop = true)
        tvStatus.text = "状态: 已停止"
        tvCurrentTarget.text = "目标: 无"
        btnStartNav.isEnabled = true
        btnStopNav.isEnabled = false
        runOnUiThread {
            tvSpeed.text = "速度: 0.0 m/s"
            tvTurn.text = "转向: 0.0 °/s"
        }
        targetCircle?.remove()
        targetCircle = null
        updateGuideLine()
    }

    // ---------- 辅助计算 ----------
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

    // ---------- 生命周期 ----------
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        val newSpeed = prefs.getFloat("nav_max_speed", 1.5f)
        val newTurn = prefs.getFloat("nav_max_turn", 50f)
        val newDist = prefs.getFloat("arrival_distance", 10f)
        if (newSpeed != navMaxSpeed || newTurn != navMaxTurn || newDist != targetArrivalDistance) {
            navMaxSpeed = newSpeed
            navMaxTurn = newTurn
            targetArrivalDistance = newDist
            updateAllCirclesRadius()
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
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
    }
}