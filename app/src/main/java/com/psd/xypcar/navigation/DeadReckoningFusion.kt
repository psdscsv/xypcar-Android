package com.psd.xypcar.navigation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 融合定位器：差速里程计 + 手机航向 + GPS 锚点。
 *
 * 与旧版关键区别：
 *  1) 内部用增量式弧线积分，不再从锚点重投影 → 消除滞后/跳变
 *  2) 编码器差速 dTheta 和手机航向做互补滤波 → 差速信息被正确利用
 *  3) 编码器读数不变时位置立即冻结 → 停止后不再"漂"
 *
 * 保留原公共接口（getLat/getLng/isReady/onGps/onEncoderDistance/onBearing/...），
 * AutoDriveActivity 无需大改。
 */
class DeadReckoningFusion(
    wheelBase: Double = 0.15
) {
    // ---------- 可调参数（保持旧名字，AutoDriveActivity 会 set） ----------
    var gpsCorrectionGain: Float = 0.35f
    var gpsAccuracyThreshold: Float = 30f
    var gpsOutlierThreshold: Float = 20f

    /** 手机航向对里程计航向的修正强度，0.03~0.15 比较合适 */
    var headingCorrectionGain: Double = 0.10

    // ---------- 内部状态 ----------
    private val odom = DifferentialOdometry(wheelBase)

    private var anchorLat = 0.0
    private var anchorLng = 0.0
    private var anchorX   = 0.0
    private var anchorY   = 0.0
    private var anchorSet = false

    private var phoneHeadingRad = 0.0
    private var hasPhoneHeading = false

    private var estLat = 0.0
    private var estLng = 0.0

    private var lastEncAvg = 0.0   // 供 getEncoderAvg() 兼容

    // ---------- 输入 ----------

    @Synchronized
    fun onEncoderDistance(leftM: Double, rightM: Double) {
        lastEncAvg = (leftM + rightM) * 0.5
        odom.update(leftM, rightM)
        recompute()
    }

    @Synchronized
    fun onBearing(bearingDeg: Float) {
        phoneHeadingRad = Math.toRadians(bearingDeg.toDouble())
        if (!hasPhoneHeading) {
            odom.setHeading(phoneHeadingRad)
            hasPhoneHeading = true
        } else {
            // 互补滤波：编码器主导短期，手机罗盘校正长期漂移
            val diff = normalize(phoneHeadingRad - odom.headingRad)
            odom.setHeading(odom.headingRad + headingCorrectionGain * diff)
        }
        recompute()
    }

    @Synchronized
    fun onGps(gpsLat: Double, gpsLng: Double, accuracyM: Float): Boolean {
        if (!anchorSet) {
            anchorLat = gpsLat; anchorLng = gpsLng
            anchorX = odom.x;    anchorY = odom.y
            anchorSet = true; recompute(); return true
        }
        if (accuracyM > gpsAccuracyThreshold) return false

        val (drLat, drLng) = localToGps(odom.x, odom.y)
        val rLatM = (gpsLat - drLat) * 111320.0
        val rLngM = (gpsLng - drLng) * 111320.0 * cos(Math.toRadians(anchorLat))
        val residualM = Math.hypot(rLatM, rLngM)

        if (residualM > gpsOutlierThreshold) {
            anchorLat += gpsCorrectionGain * 0.2f * (gpsLat - drLat)
            anchorLng += gpsCorrectionGain * 0.2f * (gpsLng - drLng)
            recompute(); return false
        }
        anchorLat += gpsCorrectionGain * (gpsLat - drLat)
        anchorLng += gpsCorrectionGain * (gpsLng - drLng)
        // 关键：把当前里程计位置重置为锚点，避免误差反复累积
        anchorX = odom.x
        anchorY = odom.y
        recompute()
        return true
    }

    @Synchronized
    fun forceAnchor(lat: Double, lng: Double) {
        anchorLat = lat; anchorLng = lng
        anchorX = odom.x; anchorY = odom.y
        anchorSet = true
        recompute()
    }

    // ---------- 输出 ----------
    @Synchronized fun getLat(): Double = estLat
    @Synchronized fun getLng(): Double = estLng
    @Synchronized fun isReady(): Boolean = anchorSet
    @Synchronized fun getEncoderAvg(): Double = lastEncAvg

    /** 融合后的航向（度，北=0 顺时针），比手机罗盘更平滑 */
    @Synchronized fun getHeadingDeg(): Float {
        val deg = Math.toDegrees(odom.headingRad)
        return if (deg < 0) (deg + 360).toFloat() else deg.toFloat()
    }

    /** 融合后的线速度估计 m/s，可直接用于终点减速逻辑 */
    @Synchronized fun getLinearSpeed(): Double = odom.linearSpeed

    /** 融合后的角速度估计 rad/s */
    @Synchronized fun getAngularSpeed(): Double = odom.angularSpeed

    @Synchronized
    fun debugInfo(): String =
        "x=%.2f y=%.2f hdg=%.1f° v=%.2f m/s".format(
            odom.x, odom.y, Math.toDegrees(odom.headingRad), odom.linearSpeed)

    @Synchronized
    fun reset() {
        odom.reset()
        anchorSet = false
        hasPhoneHeading = false
        estLat = 0.0; estLng = 0.0
    }

    // ---------- 内部 ----------
    private fun recompute() {
        if (!anchorSet) return
        val (lat, lng) = localToGps(odom.x, odom.y)
        estLat = lat; estLng = lng
    }

    private fun localToGps(x: Double, y: Double): Pair<Double, Double> {
        val dx = x - anchorX
        val dy = y - anchorY
        val R = 6371000.0
        val dLat = Math.toDegrees(dy / R)
        val cosLat = cos(Math.toRadians(anchorLat))
        val dLng = if (abs(cosLat) < 1e-6) 0.0
        else Math.toDegrees(dx / (R * cosLat))
        return Pair(anchorLat + dLat, anchorLng + dLng)
    }

    private fun normalize(a: Double): Double {
        var v = a
        while (v >  Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }
}