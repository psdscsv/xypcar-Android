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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var etTargetLat: EditText
    private lateinit var etTargetLng: EditText
    private lateinit var btnSetTargetByMap: Button
    private lateinit var btnStartNav: Button
    private lateinit var btnStopNav: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTurn: TextView
    private lateinit var btnLocate: Button

    // ---------- 导航数据 ----------
    private var targetLatLon: LatLonPoint? = null
    private var isNavigating = false

    // ---------- 高德定位 ----------
    private lateinit var locationClient: AMapLocationClient
    private var currentLocation: AMapLocation? = null
    private var isFirstLocation = true

    // ---------- 传感器 ----------
    private lateinit var sensorManager: SensorManager
    private var deviceBearing = 0f   // 设备朝向（0~360°）

    // ---------- 导航循环 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var navRunnable: Runnable? = null
    private val navInterval = 100L

    // ---------- BLE ----------
    private var isBleConnected = false

    // ---------- 控制参数 ----------
    private var maxSpeed = 2.2f
    private var maxTurn = 50f
    private val targetSpeed = 1.5f
    private val stopDistance = 0.5f

    // ---------- 地图方向线 ----------
    private var targetDirectionLine: Polyline? = null
    private var headingLine: Polyline? = null
    private val headingLineLength = 30.0

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_autodrive)

        // 高德地图隐私合规
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        // 初始化地图
        mapView = findViewById(R.id.autodrive_map)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isCompassEnabled = true

        // 启用定位图层
        aMap.isMyLocationEnabled = true
        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
        aMap.myLocationStyle = myLocationStyle

        // 初始化 UI
        etTargetLat = findViewById(R.id.et_target_lat)
        etTargetLng = findViewById(R.id.et_target_lng)
        btnSetTargetByMap = findViewById(R.id.btn_set_target_by_map)
        btnStartNav = findViewById(R.id.btn_start_nav)
        btnStopNav = findViewById(R.id.btn_stop_nav)
        tvStatus = findViewById(R.id.tv_status)
        tvInfo = findViewById(R.id.tv_info)
        tvBleStatus = findViewById(R.id.tv_ble_status)
        tvSpeed = findViewById(R.id.tv_speed)
        tvTurn = findViewById(R.id.tv_turn)
        btnLocate = findViewById(R.id.btn_locate)

        // 读取配置
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
        val deviceName = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"

        // ---------- 初始化传感器 ----------
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

        // ---------- 初始化 BLE ----------
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

        // ---------- 初始化高德定位 ----------
        locationClient = AMapLocationClient(applicationContext)
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.isOnceLocation = false
        option.interval = 1000
        option.isNeedAddress = false
        option.isSensorEnable = true
        locationClient.setLocationOption(option)
        locationClient.setLocationListener(this)

        // 检查权限并启动定位
        if (checkLocationPermission()) {
            locationClient.startLocation()
        } else {
            requestLocationPermissions()
        }

        // ---------- 地图长按选点 ----------
        aMap.setOnMapLongClickListener { latLng ->
            targetLatLon = LatLonPoint(latLng.latitude, latLng.longitude)
            etTargetLat.setText(latLng.latitude.toString())
            etTargetLng.setText(latLng.longitude.toString())
            aMap.clear()
            addTargetMarker()
            updateDirectionLinesFromCurrentLocation()
            Toast.makeText(this, "目标点已设置", Toast.LENGTH_SHORT).show()
        }

        // ---------- 按钮事件 ----------
        btnSetTargetByMap.setOnClickListener {
            val lat = etTargetLat.text.toString().toDoubleOrNull()
            val lng = etTargetLng.text.toString().toDoubleOrNull()
            if (lat != null && lng != null) {
                targetLatLon = LatLonPoint(lat, lng)
                aMap.clear()
                addTargetMarker()
                updateDirectionLinesFromCurrentLocation()
                Toast.makeText(this, "目标点已设置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请输入有效坐标", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartNav.setOnClickListener {
            if (targetLatLon == null) {
                Toast.makeText(this, "请先设置目标点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isBleConnected) {
                Toast.makeText(this, "请先连接 BLE 设备", Toast.LENGTH_SHORT).show()
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

        // 自动连接 BLE
        connectBle()
    }

    // ---------- 高德定位回调 ----------
    override fun onLocationChanged(location: AMapLocation?) {
        if (location != null && location.errorCode == 0) {
            currentLocation = location
            if (isFirstLocation) {
                isFirstLocation = false
                moveToMyLocation()
            }
            // 定位更新时刷新方向线
            updateDirectionLinesFromCurrentLocation()
        } else {
            // 定位失败
            val errCode = location?.errorCode ?: -1
            val errInfo = location?.errorInfo ?: "未知错误"
            if (location != null && errCode != 0) {
                // 只提示一次，避免频繁弹窗
            }
        }
    }

    // ---------- 传感器回调 ----------
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ORIENTATION -> {
                    deviceBearing = it.values[0]
                    if (deviceBearing < 0) deviceBearing += 360f
                    updateDirectionLinesFromCurrentLocation()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    deviceBearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    if (deviceBearing < 0) deviceBearing += 360f
                    updateDirectionLinesFromCurrentLocation()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- BLE 连接 ----------
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

    // ---------- 移动到我的位置 ----------
    private fun moveToMyLocation() {
        val loc = currentLocation ?: run {
            Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
            return
        }
        val latLng = LatLng(loc.latitude, loc.longitude)
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
    }

    // ---------- 添加目标点标记 ----------
    private fun addTargetMarker() {
        targetLatLon?.let {
            aMap.addMarker(MarkerOptions()
                .position(LatLng(it.latitude, it.longitude))
                .title("目标点")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15f))
        }
    }

    // ---------- 刷新方向线 ----------
    private fun updateDirectionLinesFromCurrentLocation() {
        val target = targetLatLon ?: return
        val currentLoc = currentLocation ?: return

        val currentLatLng = LatLng(currentLoc.latitude, currentLoc.longitude)
        val targetLatLng = LatLng(target.latitude, target.longitude)
        val bearing = if (deviceBearing != 0f) deviceBearing else currentLoc.bearing

        updateDirectionLines(currentLatLng, targetLatLng, bearing)
    }

    // ---------- 导航控制 ----------
    private fun startNavigation() {
        if (targetLatLon == null || !isBleConnected) return
        if (currentLocation == null) {
            Toast.makeText(this, "正在获取位置，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        tvStatus.text = "状态: 导航中..."
        btnStartNav.isEnabled = false
        btnStopNav.isEnabled = true
        isNavigating = true
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
        val target = targetLatLon ?: run {
            stopNavigation()
            return
        }
        val currentLoc = currentLocation ?: return

        val targetLatLng = LatLng(target.latitude, target.longitude)
        val currentLatLng = LatLng(currentLoc.latitude, currentLoc.longitude)

        val distance = distanceBetween(currentLoc.latitude, currentLoc.longitude,
            target.latitude, target.longitude)

        if (distance < stopDistance) {
            bleController.sendControl(0f, 0f, stop = true)
            tvStatus.text = "状态: 到达目标点"
            isNavigating = false
            btnStartNav.isEnabled = true
            btnStopNav.isEnabled = false
            runOnUiThread {
                tvSpeed.text = "速度: 0.0 m/s"
                tvTurn.text = "转向: 0.0 °/s"
            }
            clearDirectionLines()
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
        val speed = targetSpeed * speedFactor

        bleController.sendControl(speed, turnValue * maxTurn, stop = false)

        runOnUiThread {
            tvSpeed.text = String.format(Locale.US, "速度: %.2f m/s", speed)
            tvTurn.text = String.format(Locale.US, "转向: %.1f °/s", turnValue * maxTurn)
            tvInfo.text = String.format(Locale.US, "距离: %.1f m  方位: %.1f°", distance, targetBearing)
        }

        updateDirectionLines(currentLatLng, targetLatLng, currentBearing)
    }

    // ---------- 更新方向线 ----------
    private fun updateDirectionLines(currentPos: LatLng, targetPos: LatLng, heading: Float) {
        targetDirectionLine?.remove()
        headingLine?.remove()

        targetDirectionLine = aMap.addPolyline(
            PolylineOptions()
                .add(currentPos, targetPos)
                .color(Color.argb(255, 255, 0, 0))
                .width(6f)
                .setDottedLine(true)
                .geodesic(true)
        )

        val endPoint = calculateDestination(currentPos.latitude, currentPos.longitude,
            heading, headingLineLength)
        headingLine = aMap.addPolyline(
            PolylineOptions()
                .add(currentPos, endPoint)
                .color(Color.argb(255, 0, 255, 0))
                .width(6f)
                .geodesic(true)
        )
    }

    private fun clearDirectionLines() {
        targetDirectionLine?.remove()
        targetDirectionLine = null
        headingLine?.remove()
        headingLine = null
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

    private fun stopNavigation() {
        isNavigating = false
        navRunnable?.let { handler.removeCallbacks(it) }
        navRunnable = null
        bleController.sendControl(0f, 0f, stop = true)
        tvStatus.text = "状态: 已停止"
        btnStartNav.isEnabled = true
        btnStopNav.isEnabled = false
        runOnUiThread {
            tvSpeed.text = "速度: 0.0 m/s"
            tvTurn.text = "转向: 0.0 °/s"
        }
        clearDirectionLines()
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

    override fun onResume() {
        super.onResume()
        mapView.onResume()
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