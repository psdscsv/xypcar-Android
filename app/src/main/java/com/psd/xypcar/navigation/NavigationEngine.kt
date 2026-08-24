package com.psd.xypcar.navigation

import android.location.Location
import com.amap.api.services.core.LatLonPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * 导航引擎配置参数
 */
data class NavigationConfig(
    var maxSpeed: Float = 1.5f,          // m/s
    var maxTurn: Float = 50f,            // °/s
    var arrivalDistance: Float = 10f,    // 米
    var pathLookahead: Float = 5f,       // 米
    var turnDeadZone: Float = 2f,        // 度
    var rollThreshold: Float = 15f,      // °/s
    var calibrationTime: Float = 2.0f,   // 秒
    var calibrationAngle: Float = 5.0f   // 度
)

/**
 * 引擎返回结果
 */
data class NavigationResult(
    val speed: Float,              // 目标速度 (m/s)，0 表示停止
    val turn: Float,               // 目标转向 (°/s)
    val stop: Boolean,             // 是否紧急停止（立即刹车）
    val statusMessage: String,     // 状态描述，用于 UI 显示
    val isNavigating: Boolean,     // 是否正在导航
    val isCalibrating: Boolean,    // 是否正在校准
    val currentTargetIndex: Int,   // 当前目标点索引（-1 表示无）
    val distanceToTarget: Float,   // 到当前目标点的距离（米）
    val targetBearing: Float,      // 到目标点的方位角（度）
    val needUpdateUI: Boolean = true // 是否需要刷新 UI（通常 true）
)

/**
 * 导航引擎：封装所有导航决策逻辑，内部维护状态
 */
class NavigationEngine(
    private var config: NavigationConfig
) {
    // ================== 内部状态 ==================
    private var currentTargetIndex = -1
    private var isNavigating = false
    private var isCalibrating = false
    private val angleHistory = mutableListOf<Float>()
    private var lastSampleTime = 0L
    private val sampleIntervalMs = 200L

    // 当前目标点列表
    private var waypoints: List<LatLonPoint> = emptyList()

    // ================== 公开 API ==================

    /**
     * 启动导航（传入路径点列表）
     */
    fun start(waypoints: List<LatLonPoint>) {
        this.waypoints = waypoints
        currentTargetIndex = 0
        isNavigating = true
        isCalibrating = true
        angleHistory.clear()
        lastSampleTime = System.currentTimeMillis()
    }

    /**
     * 停止导航（重置所有状态）
     */
    fun stop() {
        isNavigating = false
        isCalibrating = false
        currentTargetIndex = -1
        angleHistory.clear()
        waypoints = emptyList()
    }

    /**
     * 更新配置参数（可在运行时调用）
     */
    fun updateConfig(newConfig: NavigationConfig) {
        config = newConfig
    }

    /**
     * 核心更新方法：每帧调用，传入当前状态，返回控制指令
     *
     * @param location 当前 GPS 位置（经纬度、航向、速度等）
     * @param deviceBearing 设备方向传感器得到的方位角（度，0~360）
     * @param rollVelocity 陀螺仪横滚角速度（度/秒），用于翻滚检测
     * @param isBleConnected BLE 是否连接（用于判断能否发送指令）
     * @return NavigationResult
     */
    fun update(
        location: Location?,
        deviceBearing: Float,
        rollVelocity: Float,
        isBleConnected: Boolean
    ): NavigationResult {
        // 1. 如果未在导航状态，返回空指令
        if (!isNavigating) {
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = true,
                statusMessage = "未导航",
                isNavigating = false,
                isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = false
            )
        }

        // 2. 检查 BLE 连接
        if (!isBleConnected) {
            // 自动停止导航
            stop()
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = true,
                statusMessage = "BLE 断开，导航停止",
                isNavigating = false,
                isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }

        // 3. 检查是否有有效位置
        if (location == null) {
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = false,
                statusMessage = "定位中...",
                isNavigating = true,
                isCalibrating = isCalibrating,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }

        // 4. 翻滚检测（紧急停止）
        if (abs(rollVelocity) > config.rollThreshold) {
            // 触发紧急停止，并终止导航
            stop()
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = true,
                statusMessage = "⚠️ 翻滚！紧急停止",
                isNavigating = false,
                isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }

        // 5. 如果在校准状态，执行校准
        if (isCalibrating) {
            return handleCalibration(deviceBearing)
        }

        // 6. 导航主循环
        return performNavigation(location, deviceBearing)
    }

    // ================== 内部私有方法 ==================

    /**
     * 校准处理
     */
    private fun handleCalibration(deviceBearing: Float): NavigationResult {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < sampleIntervalMs) {
            // 未到采样时间，返回等待状态
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = false,
                statusMessage = "校准中... 请保持稳定",
                isNavigating = true,
                isCalibrating = true,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }
        lastSampleTime = now

        // 记录角度
        angleHistory.add(deviceBearing)
        val requiredSamples = (config.calibrationTime * 1000 / sampleIntervalMs).toInt()
        while (angleHistory.size > requiredSamples) {
            angleHistory.removeAt(0)
        }

        if (angleHistory.size >= requiredSamples) {
            val min = angleHistory.minOrNull() ?: 0f
            val max = angleHistory.maxOrNull() ?: 0f
            val range = max - min
            if (range <= config.calibrationAngle) {
                // 校准通过，进入导航
                isCalibrating = false
                angleHistory.clear()
                return NavigationResult(
                    speed = 0f,
                    turn = 0f,
                    stop = false,
                    statusMessage = "校准通过，开始导航",
                    isNavigating = true,
                    isCalibrating = false,
                    currentTargetIndex = currentTargetIndex,
                    distanceToTarget = 0f,
                    targetBearing = 0f,
                    needUpdateUI = true
                )
            } else {
                // 继续校准，清空部分历史以便重新采样（保留最近的一些）
                angleHistory.clear()
                return NavigationResult(
                    speed = 0f,
                    turn = 0f,
                    stop = false,
                    statusMessage = "校准中 (波动 ${"%.1f".format(range)}°)",
                    isNavigating = true,
                    isCalibrating = true,
                    currentTargetIndex = currentTargetIndex,
                    distanceToTarget = 0f,
                    targetBearing = 0f,
                    needUpdateUI = true
                )
            }
        } else {
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = false,
                statusMessage = "校准中... 采样 ${angleHistory.size}/$requiredSamples",
                isNavigating = true,
                isCalibrating = true,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }
    }

    /**
     * 导航主逻辑
     */
    private fun performNavigation(
        location: Location,
        deviceBearing: Float
    ): NavigationResult {
        // 检查目标点是否存在
        if (waypoints.isEmpty() || currentTargetIndex >= waypoints.size) {
            stop()
            return NavigationResult(
                speed = 0f,
                turn = 0f,
                stop = true,
                statusMessage = "所有目标点已到达",
                isNavigating = false,
                isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f,
                targetBearing = 0f,
                needUpdateUI = true
            )
        }

        val target = waypoints[currentTargetIndex]
        val lat = location.latitude
        val lng = location.longitude

        // 计算到当前目标点的距离和方位
        val distToTarget = distanceBetween(lat, lng, target.latitude, target.longitude)
        val bearingToTarget = bearingBetween(lat, lng, target.latitude, target.longitude)

        // 判断是否应切换目标点
        if (shouldSwitchToNextTarget(location, currentTargetIndex)) {
            // 切换到下一个
            if (currentTargetIndex + 1 < waypoints.size) {
                currentTargetIndex++
                // 递归调用一次，重新计算新目标的控制
                return performNavigation(location, deviceBearing)
            } else {
                // 全部到达
                stop()
                return NavigationResult(
                    speed = 0f,
                    turn = 0f,
                    stop = true,
                    statusMessage = "所有目标点已到达",
                    isNavigating = false,
                    isCalibrating = false,
                    currentTargetIndex = -1,
                    distanceToTarget = 0f,
                    targetBearing = 0f,
                    needUpdateUI = true
                )
            }
        }

        // 计算引导目标点（考虑前瞻）
        val goal = computePathGoal(location)
        val targetBearing = if (goal != null) {
            bearingBetween(lat, lng, goal.latitude, goal.longitude)
        } else {
            bearingToTarget
        }

        // 计算航向偏差
        val currentBearing = if (deviceBearing != 0f) deviceBearing else location.bearing
        var turnDiff = targetBearing - currentBearing
        if (turnDiff > 180) turnDiff -= 360
        if (turnDiff < -180) turnDiff += 360

        // 转向控制：带死区的开关控制
        val turnValue = if (abs(turnDiff) > config.turnDeadZone) sign(turnDiff) else 0f
        val turn = turnValue * config.maxTurn

        // 速度：固定最大速度（可根据需要扩展自适应）
        val speed = config.maxSpeed

        return NavigationResult(
            speed = speed,
            turn = turn,
            stop = false,
            statusMessage = "导航中...",
            isNavigating = true,
            isCalibrating = false,
            currentTargetIndex = currentTargetIndex,
            distanceToTarget = distToTarget,
            targetBearing = targetBearing,
            needUpdateUI = true
        )
    }

    // ================== 路径规划辅助方法 ==================

    /**
     * 判断是否应切换到下一个目标点
     */
    private fun shouldSwitchToNextTarget(location: Location, idx: Int): Boolean {
        val target = waypoints[idx]
        val dist = distanceBetween(location.latitude, location.longitude,
            target.latitude, target.longitude)
        return dist < config.arrivalDistance
    }

    /**
     * 计算引导目标点（路径前瞻）
     */
    private fun computePathGoal(location: Location): LatLonPoint? {
        if (waypoints.isEmpty() || currentTargetIndex >= waypoints.size) return null
        val idx = currentTargetIndex
        val target = waypoints[idx]
        if (idx + 1 < waypoints.size) {
            return pathGoalOnSegment(location, target, waypoints[idx + 1])
        }
        return target
    }

    /**
     * 沿线段投影+前瞻
     */
    private fun pathGoalOnSegment(
        location: Location,
        segStart: LatLonPoint,
        segEnd: LatLonPoint
    ): LatLonPoint {
        val lookahead = config.pathLookahead.toDouble()
        val lat0 = location.latitude
        val lng0 = location.longitude

        val R = 6371000.0
        val cosLat = Math.cos(Math.toRadians(lat0))
        fun toX(lat: Double, lng: Double) = R * Math.toRadians(lng - lng0) * cosLat
        fun toY(lat: Double, lng: Double) = R * Math.toRadians(lat - lat0)
        fun toLat(y: Double) = lat0 + Math.toDegrees(y / R)
        fun toLng(x: Double) = lng0 + Math.toDegrees(x / (R * cosLat))

        val ax = toX(segStart.latitude, segStart.longitude)
        val ay = toY(segStart.latitude, segStart.longitude)
        val bx = toX(segEnd.latitude, segEnd.longitude)
        val by = toY(segEnd.latitude, segEnd.longitude)

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq < 1e-9) return segEnd

        var t = (-ax * dx - ay * dy) / segLenSq
        t = t.coerceIn(0.0, 1.0)

        val px = ax + t * dx
        val py = ay + t * dy
        val segLen = Math.sqrt(segLenSq)
        val goalDist = Math.min(lookahead, segLen)
        val ux = dx / segLen
        val uy = dy / segLen
        val gx = px + ux * goalDist
        val gy = py + uy * goalDist

        return LatLonPoint(toLat(gy), toLng(gx))
    }

    // ================== 工具函数 ==================

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
}