package com.psd.xypcar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.*
import com.psd.xypcar.control.BLEController
import java.util.*

class AutoDriveActivity : AppCompatActivity(), RouteSearch.OnRouteSearchListener, LocationListener {

    private lateinit var mapView: MapView
    private lateinit var aMap: AMap
    private lateinit var routeSearch: RouteSearch
    private lateinit var bleController: BLEController

    // UI 组件
    private lateinit var etTargetLat: EditText
    private lateinit var etTargetLng: EditText
    private lateinit var btnSetTargetByMap: Button
    private lateinit var btnStartNav: Button
    private lateinit var btnStopNav: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var btnLocate: Button

    // 目标点
    private var targetLatLon: LatLonPoint? = null
    // 路径点列表 (LatLng)
    private var routePoints: List<LatLng>? = null
    // 当前路径索引
    private var currentPointIndex = 0
    // 是否导航中
    private var isNavigating = false

    // 定位
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private var isFirstLocation = true  // 首次定位标记

    // 导航循环
    private val handler = Handler(Looper.getMainLooper())
    private var navRunnable: Runnable? = null
    private val navInterval = 100L

    // BLE 连接状态
    private var isBleConnected = false

    // 控制参数
    private var maxSpeed = 2.2f
    private var maxTurn = 50f
    private val targetSpeed = 1.5f
    private val stopDistance = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
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

        // ==== 启用定位图层 ====
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
        btnLocate = findViewById(R.id.btn_locate)

        // 读取配置
        val prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)
        maxSpeed = prefs.getFloat("max_speed", 2.2f)
        maxTurn = prefs.getFloat("max_turn", 50f)
        val deviceName = prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car"

        // 初始化 BLE
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

        // 初始化路线搜索
        routeSearch = RouteSearch(this)
        routeSearch.setRouteSearchListener(this)

        // 初始化定位
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (checkLocationPermission()) {
            requestLocationUpdates()
        } else {
            requestLocationPermissions()
        }

        // 地图长按选点
        aMap.setOnMapLongClickListener { latLng ->
            targetLatLon = LatLonPoint(latLng.latitude, latLng.longitude)
            etTargetLat.setText(latLng.latitude.toString())
            etTargetLng.setText(latLng.longitude.toString())
            aMap.clear()
            addTargetMarker()
            Toast.makeText(this, "目标点已设置", Toast.LENGTH_SHORT).show()
        }

        // 按钮点击事件
        btnSetTargetByMap.setOnClickListener {
            val lat = etTargetLat.text.toString().toDoubleOrNull()
            val lng = etTargetLng.text.toString().toDoubleOrNull()
            if (lat != null && lng != null) {
                targetLatLon = LatLonPoint(lat, lng)
                aMap.clear()
                addTargetMarker()
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

        // ==== 定位按钮点击事件 ====
        btnLocate.setOnClickListener {
            moveToMyLocation()
        }

        // 自动连接 BLE
        connectBle()
    }

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

    // ---------- 权限检查 ----------
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

    private fun requestLocationUpdates() {
        if (checkLocationPermission()) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, this)
        }
    }

    // ---------- LocationListener ----------
    override fun onLocationChanged(location: Location) {
        currentLocation = location
        // 首次定位时自动移动到当前位置
        if (isFirstLocation) {
            isFirstLocation = false
            moveToMyLocation()
        }
        // 可在此处更新车辆标记等其他逻辑
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- 移动到我的位置 ----------
    private fun moveToMyLocation() {
        val loc = currentLocation ?: run {
            Toast.makeText(this, "正在获取位置...", Toast.LENGTH_SHORT).show()
            return
        }
        val latLng = LatLng(loc.latitude, loc.longitude)
        aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
    }

    // ---------- 路线规划回调 ----------
    override fun onDriveRouteSearched(result: DriveRouteResult?, rCode: Int) {
        if (rCode == 1000 && result != null && result.paths != null && result.paths.isNotEmpty()) {
            val drivePath = result.paths[0]
            val points = mutableListOf<LatLng>()
            for (step in drivePath.steps) {
                for (point in step.polyline) {
                    points.add(LatLng(point.latitude, point.longitude))
                }
            }
            routePoints = points
            aMap.addPolyline(PolylineOptions()
                .addAll(points)
                .width(15f)
                .color(Color.argb(255, 0, 200, 255))
                .geodesic(true))
            val distance = drivePath.distance
            val duration = drivePath.duration
            tvInfo.text = "路线: 距离 ${distance/1000f} km, 耗时 ${duration/60} 分钟"

            if (isNavigating) {
                currentPointIndex = 0
                startNavLoop()
            }
        } else {
            Toast.makeText(this, "路线规划失败，错误码：$rCode", Toast.LENGTH_SHORT).show()
            tvStatus.text = "状态: 路线规划失败"
            isNavigating = false
            btnStartNav.isEnabled = true
            btnStopNav.isEnabled = false
        }
    }

    override fun onWalkRouteSearched(result: WalkRouteResult?, rCode: Int) {}
    override fun onBusRouteSearched(result: BusRouteResult?, rCode: Int) {}
    override fun onRideRouteSearched(result: RideRouteResult?, rCode: Int) {}

    // ---------- 导航控制 ----------
    private fun startNavigation() {
        if (targetLatLon == null || !isBleConnected) return
        if (currentLocation == null) {
            Toast.makeText(this, "正在获取位置，请稍后", Toast.LENGTH_SHORT).show()
            return
        }
        val startLatLon = LatLonPoint(currentLocation!!.latitude, currentLocation!!.longitude)
        val query = RouteSearch.DriveRouteQuery(
            RouteSearch.FromAndTo(startLatLon, targetLatLon),
            0,
            null,
            null,
            ""
        )
        routeSearch.calculateDriveRouteAsyn(query)
        tvStatus.text = "状态: 规划路线中..."
        btnStartNav.isEnabled = false
        btnStopNav.isEnabled = true
        isNavigating = true
    }

    private fun startNavLoop() {
        if (routePoints == null || routePoints!!.isEmpty()) {
            stopNavigation()
            return
        }
        currentPointIndex = 0
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
        val points = routePoints ?: return
        if (currentPointIndex >= points.size - 1) {
            bleController.sendControl(0f, 0f, stop = true)
            tvStatus.text = "状态: 到达目标点"
            isNavigating = false
            btnStartNav.isEnabled = true
            btnStopNav.isEnabled = false
            return
        }

        val currentLoc = currentLocation ?: return
        val targetPoint = points[currentPointIndex]
        val nextPoint = points[Math.min(currentPointIndex + 1, points.size - 1)]

        val distance = distanceBetween(currentLoc.latitude, currentLoc.longitude,
            targetPoint.latitude, targetPoint.longitude)

        if (distance < stopDistance) {
            currentPointIndex++
            if (currentPointIndex >= points.size - 1) {
                bleController.sendControl(0f, 0f, stop = true)
                tvStatus.text = "状态: 到达目标点"
                isNavigating = false
                btnStartNav.isEnabled = true
                btnStopNav.isEnabled = false
                return
            }
            return
        }

        val targetBearing = bearingBetween(currentLoc.latitude, currentLoc.longitude,
            targetPoint.latitude, targetPoint.longitude)
        val currentBearing = if (currentLoc.hasBearing()) currentLoc.bearing else 0f

        var turnDiff = targetBearing - currentBearing
        if (turnDiff > 180) turnDiff -= 360
        if (turnDiff < -180) turnDiff += 360

        val maxTurnDiff = 45f
        val turnValue = (turnDiff / maxTurnDiff).coerceIn(-1f, 1f)

        val speedFactor = if (distance > 5f) 1f else (distance / 5f).coerceIn(0.2f, 1f)
        val speed = targetSpeed * speedFactor

        bleController.sendControl(speed, turnValue * maxTurn, stop = false)
        tvInfo.text = "目标点 ${currentPointIndex+1}/${points.size-1}  距离 ${"%.1f".format(distance)}m"
    }

    private fun stopNavigation() {
        isNavigating = false
        navRunnable?.let { handler.removeCallbacks(it) }
        navRunnable = null
        bleController.sendControl(0f, 0f, stop = true)
        tvStatus.text = "状态: 已停止"
        btnStartNav.isEnabled = true
        btnStopNav.isEnabled = false
    }

    // ---------- 辅助工具 ----------
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

    private fun addTargetMarker() {
        targetLatLon?.let {
            aMap.addMarker(MarkerOptions()
                .position(LatLng(it.latitude, it.longitude))
                .title("目标点")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15f))
        }
    }

    // ---------- 生命周期 ----------
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
        bleController.disconnect()
        handler.removeCallbacksAndMessages(null)
        if (checkLocationPermission()) {
            locationManager.removeUpdates(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1002 -> if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestLocationUpdates()
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
}