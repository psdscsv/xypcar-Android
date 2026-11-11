package com.psd.xypcar.navigation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 差速驱动里程计。
 *
 * 关键点：
 *  - 用 (dL, dR) 做增量式弧线积分，而不是「平均距离 + 外部航向」
 *  - 原地转向 (dL + dR = 0, dR - dL ≠ 0) 也能正确更新航向
 *  - 用中点法 (midHeading) 减小离散化误差
 */
class DifferentialOdometry(
    /** 左右轮距（米）。可用卷尺量两轮中心距，例如 0.15m。 */
    var wheelBase: Double = 0.15
) {
    /** 局部坐标：x = 东(m)，y = 北(m)，原点为启动时位置 */
    var x: Double = 0.0; private set
    var y: Double = 0.0; private set

    /** 航向弧度，0=北，顺时针为正 */
    var headingRad: Double = 0.0; private set

    /** 瞬时线速度估计 m/s */
    var linearSpeed: Double = 0.0; private set

    /** 瞬时角速度估计 rad/s */
    var angularSpeed: Double = 0.0; private set

    private var prevLeft = 0.0
    private var prevRight = 0.0
    private var hasPrev = false
    private var lastTimeMs = 0L

    @Synchronized
    fun update(leftM: Double, rightM: Double) {
        val now = System.currentTimeMillis()
        if (!hasPrev) {
            prevLeft = leftM; prevRight = rightM
            hasPrev = true; lastTimeMs = now
            return
        }
        val dL = leftM - prevLeft
        val dR = rightM - prevRight
        prevLeft = leftM; prevRight = rightM

        val dCenter = (dL + dR) * 0.5
        val dTheta  = if (wheelBase > 1e-6) (dR - dL) / wheelBase else 0.0

        // 弧线积分（中点法）——即使原地转向也正确
        val midHeading = headingRad + dTheta * 0.5
        x += dCenter * sin(midHeading)
        y += dCenter * cos(midHeading)
        headingRad = normalize(headingRad + dTheta)

        val dt = (now - lastTimeMs) / 1000.0
        if (dt in 0.001..1.0) {
            linearSpeed  = dCenter / dt
            angularSpeed = dTheta / dt
        }
        lastTimeMs = now
    }

    @Synchronized
    fun setHeading(rad: Double) { headingRad = normalize(rad) }

    @Synchronized
    fun reset() {
        x = 0.0; y = 0.0; headingRad = 0.0
        hasPrev = false; linearSpeed = 0.0; angularSpeed = 0.0
    }

    private fun normalize(a: Double): Double {
        var v = a
        while (v >  Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }
}