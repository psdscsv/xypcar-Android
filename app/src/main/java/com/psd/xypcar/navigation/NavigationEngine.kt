package com.psd.xypcar.navigation

import android.location.Location
import com.amap.api.services.core.LatLonPoint
import kotlin.math.abs
import kotlin.math.min

data class NavigationConfig(
    var maxSpeed: Float = 1.5f,
    var maxTurn: Float = 50f,
    var arrivalDistance: Float = 10f,
    var turnDeadZone: Float = 2f,
    var rollThreshold: Float = 15f,
    var calibrationTime: Float = 2.0f,
    var calibrationAngle: Float = 5.0f
)

data class NavigationResult(
    val speed: Float,
    val turn: Float,
    val stop: Boolean,
    val statusMessage: String,
    val isNavigating: Boolean,
    val isCalibrating: Boolean,
    val currentTargetIndex: Int,
    val distanceToTarget: Float,
    val targetBearing: Float,
    val goalLat: Double = 0.0,      // 前瞻点纬度
    val goalLng: Double = 0.0,      // 前瞻点经度
    val projectionLat: Double = 0.0,
    val projectionLng: Double = 0.0,
    val crossTrackError: Float = 0f,
    val needUpdateUI: Boolean = true
)

class NavigationEngine(
    private var config: NavigationConfig
) {
    private var currentSegmentIndex = -1
    private var currentTargetIndex = -1
    private var isNavigating = false
    private var isCalibrating = false
    private val angleHistory = mutableListOf<Float>()
    private var lastSampleTime = 0L
    private val sampleIntervalMs = 200L
    private var waypoints: List<LatLonPoint> = emptyList()

    // ---------- Pure Pursuit 参数 ----------
    private val baseLookahead = 3.0f   // 基础前瞻距离（米）
    private val lookaheadGain = 0.5f   // 速度增益（每 m/s 增加的前瞻距离）
    private val crossTrackGain = 1.8f  // 横向误差对转向的修正强度
    private val headingGain = 1.15f   // 航向误差对转向的修正强度
    private val minSpeed = 0.25f

    fun start(waypoints: List<LatLonPoint>) {
        if (waypoints.size < 2) {
            stop()
            return
        }
        this.waypoints = waypoints
        currentSegmentIndex = 0
        currentTargetIndex = 1
        isNavigating = true
        isCalibrating = true
        angleHistory.clear()
        lastSampleTime = System.currentTimeMillis()
    }

    fun stop() {
        isNavigating = false
        isCalibrating = false
        currentSegmentIndex = -1
        currentTargetIndex = -1
        angleHistory.clear()
        waypoints = emptyList()
    }

    fun updateConfig(newConfig: NavigationConfig) {
        config = newConfig
    }

    fun update(
        location: Location?,
        deviceBearing: Float,
        rollVelocity: Float,
        isBleConnected: Boolean
    ): NavigationResult {
        if (!isNavigating) {
            return NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "未导航",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = false
            )
        }

        if (!isBleConnected) {
            stop()
            return NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "BLE 断开，导航停止",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }

        if (location == null) {
            return NavigationResult(
                speed = 0f, turn = 0f, stop = false,
                statusMessage = "定位中...",
                isNavigating = true, isCalibrating = isCalibrating,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }

        if (abs(rollVelocity) > config.rollThreshold) {
            stop()
            return NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "⚠️ 翻滚！紧急停止",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }

        if (isCalibrating) {
            return handleCalibration(deviceBearing)
        }

        return performNavigation(location, deviceBearing)
    }

    private fun handleCalibration(deviceBearing: Float): NavigationResult {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < sampleIntervalMs) {
            return NavigationResult(
                speed = 0f, turn = 0f, stop = false,
                statusMessage = "校准中... 请保持稳定",
                isNavigating = true, isCalibrating = true,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }
        lastSampleTime = now

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
                isCalibrating = false
                angleHistory.clear()
                return NavigationResult(
                    speed = 0f, turn = 0f, stop = false,
                    statusMessage = "校准通过，开始导航",
                    isNavigating = true, isCalibrating = false,
                    currentTargetIndex = currentTargetIndex,
                    distanceToTarget = 0f, targetBearing = 0f,
                    needUpdateUI = true
                )
            } else {
                angleHistory.clear()
                return NavigationResult(
                    speed = 0f, turn = 0f, stop = false,
                    statusMessage = "校准中 (波动 ${"%.1f".format(range)}°)",
                    isNavigating = true, isCalibrating = true,
                    currentTargetIndex = currentTargetIndex,
                    distanceToTarget = 0f, targetBearing = 0f,
                    needUpdateUI = true
                )
            }
        } else {
            return NavigationResult(
                speed = 0f, turn = 0f, stop = false,
                statusMessage = "校准中... 采样 ${angleHistory.size}/$requiredSamples",
                isNavigating = true, isCalibrating = true,
                currentTargetIndex = currentTargetIndex,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }
    }

    // ================== Pure Pursuit 导航主循环 ==================
    private fun performNavigation(
        location: Location,
        deviceBearing: Float
    ): NavigationResult {
        if (waypoints.size < 2) {
            stop()
            return NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "路径点数不足",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = 0f, targetBearing = 0f,
                needUpdateUI = true
            )
        }

        var segIdx = currentSegmentIndex
        if (segIdx < 0 || segIdx >= waypoints.size - 1) {
            segIdx = 0
        }

        // 1. 计算当前位置到路径的投影，确定当前所在线段
        var bestSegIdx = segIdx
        var bestDist = Float.MAX_VALUE
        var bestProj = waypoints[0]
        var bestCrossTrack = 0f

        // 从当前段开始向后搜索，如果投影超出终点则前进到下一段
        var loopCount = waypoints.size
        while (loopCount-- > 0 && segIdx < waypoints.size - 1) {
            val p1 = waypoints[segIdx]
            val p2 = waypoints[segIdx + 1]
            val projection = projectOnSegmentWithDistance(location, p1, p2)
            val t = projection.rawT
            val proj = projection.point
            val dist = projection.distance
            if (dist < bestDist) {
                bestDist = dist
                bestProj = proj
                bestCrossTrack = projection.signedDistance
                bestSegIdx = segIdx
            }
            // 如果投影超出终点（t>1），且不是最后一段，则尝试下一段
            if (t >= 1.0f && segIdx < waypoints.size - 2) {
                segIdx++
            } else {
                break
            }
        }

        // 更新当前线段索引
        currentSegmentIndex = bestSegIdx
        currentTargetIndex = bestSegIdx + 1

        // 2. 先用最大速度估算前瞻距离，再根据误差动态限速。
        val initialLookahead = baseLookahead + lookaheadGain * config.maxSpeed
        val goal = findLookaheadPoint(bestSegIdx, bestProj, initialLookahead)
        val speed = computeSpeed(config.maxSpeed, bestCrossTrack,
            bearingBetween(location.latitude, location.longitude, goal.latitude, goal.longitude), deviceBearing)
        val lookahead = baseLookahead + lookaheadGain * speed
        val finalGoal = findLookaheadPoint(bestSegIdx, bestProj, lookahead)

        // 4. 计算目标方位角
        val targetBearing = bearingBetween(location.latitude, location.longitude,
            finalGoal.latitude, finalGoal.longitude)

        // 5. 转向控制（比例）
        val turn = computeTurn(targetBearing, deviceBearing, bestCrossTrack)

        // 6. 判断是否到达终点：如果当前在最后一段且距离终点小于 arrivalDistance
        val lastPoint = waypoints.last()
        val distToEnd = distanceBetween(location.latitude, location.longitude,
            lastPoint.latitude, lastPoint.longitude)
        if (bestSegIdx == waypoints.size - 2 && distToEnd < config.arrivalDistance) {
            stop()
            return NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "已到达终点",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = distToEnd,
                targetBearing = bearingBetween(location.latitude, location.longitude,
                    lastPoint.latitude, lastPoint.longitude),
                goalLat = finalGoal.latitude,
                goalLng = finalGoal.longitude,
                projectionLat = bestProj.latitude,
                projectionLng = bestProj.longitude,
                crossTrackError = bestCrossTrack,
                needUpdateUI = true
            )
        }

        // 7. 返回结果
        val endpoint = waypoints[currentTargetIndex] // 用于 UI 显示
        val distToEndpoint = distanceBetween(location.latitude, location.longitude,
            endpoint.latitude, endpoint.longitude)
        return NavigationResult(
            speed = speed,
            turn = turn,
            stop = false,
            statusMessage = "导航中",
            isNavigating = true,
            isCalibrating = false,
            currentTargetIndex = currentTargetIndex,
            distanceToTarget = distToEndpoint,
            targetBearing = bearingBetween(location.latitude, location.longitude,
                endpoint.latitude, endpoint.longitude),
            goalLat = finalGoal.latitude,
            goalLng = finalGoal.longitude,
            projectionLat = bestProj.latitude,
            projectionLng = bestProj.longitude,
            crossTrackError = bestCrossTrack,
            needUpdateUI = true
        )
    }

    /**
     * 从投影点沿路径方向前进指定距离，返回目标点。
     * 若剩余路径长度不足，则返回终点。
     */
    private fun findLookaheadPoint(segIdx: Int, projection: LatLonPoint, distance: Float): LatLonPoint {
        var remain = distance
        var idx = segIdx
        var currentPt = projection

        while (idx < waypoints.size - 1) {
            val p1 = waypoints[idx]
            val p2 = waypoints[idx + 1]
            // 计算当前点到 p2 的距离
            val segLen = distanceBetween(currentPt.latitude, currentPt.longitude,
                p2.latitude, p2.longitude)
            if (segLen >= remain) {
                // 在这段内找到目标点
                val bearing = bearingBetween(currentPt.latitude, currentPt.longitude,
                    p2.latitude, p2.longitude)
                return destinationPoint(currentPt, bearing, remain)
            } else {
                // 消耗完这一段，继续下一段
                remain -= segLen
                idx++
                currentPt = p2
            }
        }
        // 超出终点，返回终点
        return waypoints.last()
    }

    /**
     * 从给定点沿方位角前进距离，返回新点
     */
    private fun destinationPoint(point: LatLonPoint, bearing: Float, distance: Float): LatLonPoint {
        val R = 6371000.0
        val lat1 = Math.toRadians(point.latitude)
        val lon1 = Math.toRadians(point.longitude)
        val br = Math.toRadians(bearing.toDouble())
        val d = distance.toDouble() / R

        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(br))
        val lon2 = lon1 + Math.atan2(Math.sin(br) * Math.sin(d) * Math.cos(lat1),
            Math.cos(d) - Math.sin(lat1) * Math.sin(lat2))

        return LatLonPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /**
     * 转向计算（比例控制）
     */
    private fun computeTurn(targetBearing: Float, currentBearing: Float, crossTrackError: Float): Float {
        var diff = targetBearing - currentBearing
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        // 比例系数（可调），建议 1.2~2.0，值越大转向越激进
        // 航向误差 + 有符号横向误差：车辆偏离路径时向路径中心线回正。
        var turn = headingGain * diff + crossTrackGain * crossTrackError
        turn = turn.coerceIn(-config.maxTurn, config.maxTurn)

        // 转向死区
        if (abs(diff) < config.turnDeadZone) {
            turn = 0f
        }
        return turn
    }

    private fun computeSpeed(maxSpeed: Float, crossTrackError: Float,
                             targetBearing: Float, currentBearing: Float): Float {
        var headingError = targetBearing - currentBearing
        if (headingError > 180f) headingError -= 360f
        if (headingError < -180f) headingError += 360f
        val headingFactor = (1f - abs(headingError) / 120f).coerceIn(0.25f, 1f)
        val lateralFactor = (1f - abs(crossTrackError) / 8f).coerceIn(0.3f, 1f)
        val floor = minOf(minSpeed, maxSpeed)
        return (maxSpeed * headingFactor * lateralFactor).coerceIn(floor, maxSpeed)
    }

    // ================== 几何工具 ==================

    private data class Projection(
        val rawT: Float,
        val point: LatLonPoint,
        val distance: Float,
        val signedDistance: Float
    )

    private fun projectOnSegmentWithDistance(
        location: Location,
        p1: LatLonPoint,
        p2: LatLonPoint
    ): Projection {
        val lat0 = location.latitude
        val lng0 = location.longitude
        val R = 6371000.0
        val cosLat = Math.cos(Math.toRadians(lat0))

        fun toX(lat: Double, lng: Double) = R * Math.toRadians(lng - lng0) * cosLat
        fun toY(lat: Double, lng: Double) = R * Math.toRadians(lat - lat0)
        fun toLat(y: Double) = lat0 + Math.toDegrees(y / R)
        fun toLng(x: Double) = lng0 + Math.toDegrees(x / (R * cosLat))

        val ax = toX(p1.latitude, p1.longitude)
        val ay = toY(p1.latitude, p1.longitude)
        val bx = toX(p2.latitude, p2.longitude)
        val by = toY(p2.latitude, p2.longitude)

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq < 1e-9) {
            return Projection(0f, p1, distanceBetween(lat0, lng0, p1.latitude, p1.longitude), 0f)
        }

        val t = ((-ax) * dx + (-ay) * dy) / segLenSq
        val tClamped = t.coerceIn(0.0, 1.0)

        val projX = ax + tClamped * dx
        val projY = ay + tClamped * dy
        val proj = LatLonPoint(toLat(projY), toLng(projX))

        val dist = hypot(projX, projY)
        // 东-北坐标系中，路径左侧为正，右侧为负。
        val signed = (dx * (-projY) - dy * (-projX)) / Math.sqrt(segLenSq)
        return Projection(t.toFloat(), proj, dist.toFloat(), signed.toFloat())
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

    private fun hypot(x: Double, y: Double) = Math.sqrt(x * x + y * y)
}
