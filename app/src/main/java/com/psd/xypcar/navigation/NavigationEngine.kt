package com.psd.xypcar.navigation

import android.location.Location
import com.amap.api.services.core.LatLonPoint
import kotlin.math.abs
import kotlin.math.sin

data class NavigationConfig(
    var maxSpeed: Float = 1.5f,
    var maxTurn: Float = 50f,
    /** 到点判定半径（米），建议 1~2 米 */
    var arrivalDistance: Float = 1.5f,
    /** 转向死区角度（°），仅当横向误差也小于 pathDeadZone 时归零 */
    var turnDeadZone: Float = 0.5f,
    /** 翻滚角速度阈值 (°/s) */
    var rollThreshold: Float = 15f,
    /** 校准稳定时间（秒） */
    var calibrationTime: Float = 2.0f,
    /** 校准角度范围（度） */
    var calibrationAngle: Float = 5.0f,
    /** ★ 转向增益：航向误差比例系数，越大转向越激进 */
    var turnGain: Float = 2.0f
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
    val goalLat: Double = 0.0,
    val goalLng: Double = 0.0,
    val projectionLat: Double = 0.0,
    val projectionLng: Double = 0.0,
    val crossTrackError: Float = 0f,
    val needUpdateUI: Boolean = true
)

class NavigationEngine(private var config: NavigationConfig) {

    private var waypoints: List<LatLonPoint> = emptyList()
    private var currentSegmentIndex = 0
    private var currentTargetIndex = 1
    private var isNavigating = false
    private var isCalibrating = false

    private val angleHistory = mutableListOf<Float>()
    private var lastSampleTime = 0L
    private val sampleIntervalMs = 200L

    // ---------- Pure Pursuit 参数 ----------
    private val baseLookahead  = 1.2f
    private val lookaheadGain  = 0.4f
    private val minLookahead   = 0.8f

    // ---------- 转向速率限幅（防抖） ----------
    private val maxTurnRate    = 120f    // °/s 物理上限
    private val turnAccelLimit = 80f     // °/s² 加速度上限
    private var lastTurn = 0f

    // ---------- 硬编码辅助增益 ----------
    /** 横向误差增益 = turnGain × 该系数 */
    private val crossTrackGainRatio = 1.8f
    /** 横向误差归零门槛（米），也作为软死区的辅助判据 */
    private val pathDeadZone = 0.08f

    // ---------- 速度策略 ----------
    private val minSpeed        = 0.25f
    private val slowDownDist    = 2.5f
    private val stopSpeedThresh = 0.15f
    private val turningFloor    = 0.5f    // 转向保底速度
    private val turningBoost    = 0.35f   // 转向额外补偿

    // =====================================================================
    fun start(waypoints: List<LatLonPoint>) {
        if (waypoints.size < 2) { stop(); return }
        this.waypoints = waypoints
        currentSegmentIndex = 0
        currentTargetIndex = 1
        isNavigating = true
        isCalibrating = true
        angleHistory.clear()
        lastSampleTime = System.currentTimeMillis()
        lastTurn = 0f
    }

    fun stop() {
        isNavigating = false
        isCalibrating = false
        currentSegmentIndex = 0
        currentTargetIndex = 1
        angleHistory.clear()
        waypoints = emptyList()
        lastTurn = 0f
    }

    fun updateConfig(c: NavigationConfig) { config = c }

    fun update(
        location: Location?,
        deviceBearing: Float,
        rollVelocity: Float,
        isBleConnected: Boolean
    ): NavigationResult {
        if (!isNavigating) return idle("未导航", false)
        if (!isBleConnected) { stop(); return idle("BLE 断开，导航停止", true) }
        if (location == null) {
            return NavigationResult(0f, 0f, false, "定位中...",
                true, isCalibrating, currentTargetIndex, 0f, 0f)
        }
        if (abs(rollVelocity) > config.rollThreshold) {
            stop(); return idle("⚠️ 翻滚！紧急停止", true)
        }
        if (isCalibrating) return handleCalibration(deviceBearing)
        return performNavigation(location, deviceBearing)
    }

    private fun idle(msg: String, ui: Boolean) = NavigationResult(
        0f, 0f, true, msg, false, false, -1, 0f, 0f, needUpdateUI = ui)

    // =====================================================================
    private fun performNavigation(location: Location, deviceBearing: Float): NavigationResult {
        if (waypoints.size < 2) { stop(); return idle("路径点数不足", true) }

        // 1) 找投影点
        val proj = findProjection(location)
        currentSegmentIndex = proj.segmentIndex
        currentTargetIndex = proj.segmentIndex + 1

        val lastPoint = waypoints.last()
        val distToEnd = distanceBetween(location.latitude, location.longitude,
            lastPoint.latitude, lastPoint.longitude)
        val onLastSegment = proj.segmentIndex >= waypoints.size - 2

        // 2) 终点判定
        if (onLastSegment && distToEnd < config.arrivalDistance) {
            val res = NavigationResult(
                speed = 0f, turn = 0f, stop = true,
                statusMessage = "已到达终点",
                isNavigating = false, isCalibrating = false,
                currentTargetIndex = -1,
                distanceToTarget = distToEnd,
                targetBearing = bearingBetween(location.latitude, location.longitude,
                    lastPoint.latitude, lastPoint.longitude),
                goalLat = lastPoint.latitude,
                goalLng = lastPoint.longitude,
                projectionLat = proj.point.latitude,
                projectionLng = proj.point.longitude,
                crossTrackError = proj.signedDist,
                needUpdateUI = true
            )
            stop()
            return res
        }

        // 3) 目标速度
        var targetSpeed = computeSpeed(config.maxSpeed, proj.signedDist)

        // 4) 终点减速
        if (onLastSegment) {
            val effSlow = maxOf(slowDownDist, targetSpeed * 1.5f)
            if (distToEnd < effSlow) {
                val factor = (distToEnd / effSlow).coerceIn(0f, 1f)
                targetSpeed *= factor
                if (targetSpeed < stopSpeedThresh) targetSpeed = 0f
            }
        }

        // 5) 大弯检测 → 缩短前瞻
        val sharp = detectSharpTurn(proj.segmentIndex)
        val lookaheadMul = if (sharp) 0.6f else 1.0f
        val lookahead = ((baseLookahead + lookaheadGain * targetSpeed)
            .coerceAtLeast(minLookahead)) * lookaheadMul

        // 6) 前瞻点
        val goal = findLookaheadPoint(proj.segmentIndex, proj.point, lookahead)

        // 7) 转向计算 + 速率限幅
        val goalBearing = bearingBetween(location.latitude, location.longitude,
            goal.latitude, goal.longitude)
        val rawTurn = computeTurn(goalBearing, deviceBearing, proj.signedDist)
        val dtSec = 0.05f                        // navInterval = 50ms
        val maxDelta = turnAccelLimit * dtSec
        val delta = (rawTurn - lastTurn).coerceIn(-maxDelta, maxDelta)
        lastTurn += delta
        val turn = lastTurn

        // 8) 转向保底速度
        if (abs(turn) > 5f) {
            targetSpeed = maxOf(targetSpeed, turningFloor) +
                    turningBoost * (abs(turn) / maxTurnRate).coerceIn(0f, 1f)
        }

        // 9) UI 信息
        val endpoint = waypoints[currentTargetIndex]
        val distToEndpoint = distanceBetween(location.latitude, location.longitude,
            endpoint.latitude, endpoint.longitude)

        return NavigationResult(
            speed = targetSpeed,
            turn = turn,
            stop = false,
            statusMessage = "导航中",
            isNavigating = true,
            isCalibrating = false,
            currentTargetIndex = currentTargetIndex,
            distanceToTarget = distToEndpoint,
            targetBearing = bearingBetween(location.latitude, location.longitude,
                endpoint.latitude, endpoint.longitude),
            goalLat = goal.latitude,
            goalLng = goal.longitude,
            projectionLat = proj.point.latitude,
            projectionLng = proj.point.longitude,
            crossTrackError = proj.signedDist,
            needUpdateUI = true
        )
    }

    // =====================================================================
    // 投影
    // =====================================================================
    private data class Projection(
        val segmentIndex: Int,
        val point: LatLonPoint,
        val signedDist: Float,
        val t: Float
    )

    private fun findProjection(location: Location): Projection {
        var bestIdx = currentSegmentIndex.coerceIn(0, waypoints.size - 2)
        var bestDist = Float.MAX_VALUE
        var bestPoint = waypoints[0]
        var bestSigned = 0f
        var bestT = 0f

        var idx = bestIdx
        var guard = waypoints.size
        while (idx < waypoints.size - 1 && guard-- > 0) {
            val p1 = waypoints[idx]
            val p2 = waypoints[idx + 1]
            val (proj, t, signed) = projectOnSegment(location, p1, p2)
            val d = distanceBetween(location.latitude, location.longitude,
                proj.latitude, proj.longitude)
            if (d < bestDist) {
                bestDist = d; bestPoint = proj; bestSigned = signed
                bestIdx = idx; bestT = t
            }
            if (t >= 0.999f && idx < waypoints.size - 2) idx++ else break
        }
        return Projection(bestIdx, bestPoint, bestSigned, bestT)
    }

    private fun projectOnSegment(
        loc: Location, p1: LatLonPoint, p2: LatLonPoint
    ): Triple<LatLonPoint, Float, Float> {
        val lat0 = loc.latitude
        val lng0 = loc.longitude
        val R = 6371000.0
        val cosLat = Math.cos(Math.toRadians(lat0))

        val ax = R * Math.toRadians(p1.longitude - lng0) * cosLat
        val ay = R * Math.toRadians(p1.latitude  - lat0)
        val bx = R * Math.toRadians(p2.longitude - lng0) * cosLat
        val by = R * Math.toRadians(p2.latitude  - lat0)

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq < 1e-9) return Triple(p1, 0f, 0f)

        val t = (-ax * dx + -ay * dy) / segLenSq
        val tc = t.coerceIn(0.0, 1.0)
        val projX = ax + tc * dx
        val projY = ay + tc * dy

        val projLat = lat0 + Math.toDegrees(projY / R)
        val projLng = lng0 + Math.toDegrees(projX / (R * cosLat))

        // 叉积定号：路径方向 × 车辆相对投影
        val cross = dx * (-projY) - dy * (-projX)
        val signed = (cross / Math.sqrt(segLenSq)).toFloat()

        return Triple(LatLonPoint(projLat, projLng), t.toFloat(), signed)
    }

    // =====================================================================
    // 前瞻点
    // =====================================================================
    private fun findLookaheadPoint(
        segIdx: Int, projection: LatLonPoint, lookahead: Float
    ): LatLonPoint {
        var remain = lookahead
        var idx = segIdx
        var current = projection
        while (idx < waypoints.size - 1) {
            val p2 = waypoints[idx + 1]
            val segLen = distanceBetween(current.latitude, current.longitude,
                p2.latitude, p2.longitude)
            if (segLen >= remain) {
                val brg = bearingBetween(current.latitude, current.longitude,
                    p2.latitude, p2.longitude)
                return destinationPoint(current, brg, remain)
            }
            remain -= segLen
            idx++
            current = p2
        }
        return waypoints.last()
    }

    private fun destinationPoint(point: LatLonPoint, bearing: Float, distance: Float): LatLonPoint {
        val R = 6371000.0
        val lat1 = Math.toRadians(point.latitude)
        val lon1 = Math.toRadians(point.longitude)
        val br = Math.toRadians(bearing.toDouble())
        val d = distance.toDouble() / R
        val lat2 = Math.asin(sin(lat1) * Math.cos(d) + Math.cos(lat1) * sin(d) * Math.cos(br))
        val lon2 = lon1 + Math.atan2(
            sin(br) * sin(d) * Math.cos(lat1),
            Math.cos(d) - sin(lat1) * sin(lat2))
        return LatLonPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    // =====================================================================
    // 转向 & 速度
    // =====================================================================
    private fun computeTurn(
        goalBearing: Float,
        currentBearing: Float,
        crossTrackError: Float
    ): Float {
        var headingErr = goalBearing - currentBearing
        if (headingErr > 180) headingErr -= 360
        if (headingErr < -180) headingErr += 360

        // ★ 主增益来自用户设置
        val gain = config.turnGain
        val crossGain = gain * crossTrackGainRatio

        var turn = gain * headingErr + crossGain * crossTrackError

        // 软死区：只在航向误差与横向误差都极小时才归零
        if (abs(headingErr) < config.turnDeadZone &&
            abs(crossTrackError) < pathDeadZone) {
            turn = 0f
        }

        // 物理上限：min(用户 maxTurn, 内部 maxTurnRate)
        val cap = minOf(config.maxTurn, maxTurnRate)
        return turn.coerceIn(-cap, cap)
    }

    private fun computeSpeed(maxSpeed: Float, crossTrackError: Float): Float {
        val lateralFactor = (1f - abs(crossTrackError) / 5f).coerceIn(0.4f, 1f)
        return (maxSpeed * lateralFactor).coerceAtLeast(minSpeed)
    }

    // =====================================================================
    // 大弯检测
    // =====================================================================
    private fun detectSharpTurn(segIdx: Int): Boolean {
        if (segIdx <= 0 || segIdx >= waypoints.size - 2) return false
        val p0 = waypoints[segIdx - 1]
        val p1 = waypoints[segIdx]
        val p2 = waypoints[segIdx + 1]

        val b1 = bearingBetween(p0.latitude, p0.longitude, p1.latitude, p1.longitude)
        val b2 = bearingBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        var d = abs(b2 - b1)
        if (d > 180) d = 360 - d
        return d > 45f
    }

    // =====================================================================
    // 校准
    // =====================================================================
    private fun handleCalibration(bearing: Float): NavigationResult {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < sampleIntervalMs) {
            return NavigationResult(0f, 0f, false, "校准中...",
                true, true, currentTargetIndex, 0f, 0f)
        }
        lastSampleTime = now
        angleHistory.add(bearing)
        val required = (config.calibrationTime * 1000 / sampleIntervalMs).toInt()
        while (angleHistory.size > required) angleHistory.removeAt(0)

        if (angleHistory.size < required) {
            return NavigationResult(0f, 0f, false,
                "校准中... ${angleHistory.size}/$required",
                true, true, currentTargetIndex, 0f, 0f)
        }

        val range = (angleHistory.maxOrNull() ?: 0f) - (angleHistory.minOrNull() ?: 0f)
        if (range <= config.calibrationAngle) {
            isCalibrating = false
            angleHistory.clear()
            return NavigationResult(0f, 0f, false, "校准通过",
                true, false, currentTargetIndex, 0f, 0f)
        }
        angleHistory.clear()
        return NavigationResult(0f, 0f, false,
            "校准中 (波动 %.1f°)".format(range),
            true, true, currentTargetIndex, 0f, 0f)
    }

    // =====================================================================
    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[0]
    }
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = FloatArray(3)
        Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[1]
    }
}