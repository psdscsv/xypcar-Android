package com.psd.xypcar.control

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.psd.xypcar.R

class DirectionButtonFragment : Fragment() {

    interface OnControlListener {
        fun onControl(speed: Float, turn: Float)
    }

    private var listener: OnControlListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sendRunnable: Runnable? = null
    private val sendInterval = 50L

    // 按钮状态（按下为 true）
    private var forwardPressed = false
    private var backwardPressed = false
    private var leftPressed = false
    private var rightPressed = false
    private var stopPressed = false

    private lateinit var btnForward: Button
    private lateinit var btnBackward: Button
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var btnStop: Button
    private lateinit var valueDisplay: TextView

    private fun getMaxSpeed(): Float {
        val prefs = requireContext().getSharedPreferences("car_config", Context.MODE_PRIVATE)
        return prefs.getFloat("max_speed", 2.2f)
    }

    private fun getMaxTurn(): Float {
        val prefs = requireContext().getSharedPreferences("car_config", Context.MODE_PRIVATE)
        return prefs.getFloat("max_turn", 50f)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnControlListener) {
            listener = parentFragment as OnControlListener
        } else if (context is OnControlListener) {
            listener = context
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_direction_buttons, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnForward = view.findViewById(R.id.btn_forward)
        btnBackward = view.findViewById(R.id.btn_backward)
        btnLeft = view.findViewById(R.id.btn_left)
        btnRight = view.findViewById(R.id.btn_right)
        btnStop = view.findViewById(R.id.btn_stop)
        valueDisplay = view.findViewById(R.id.value_display)

        // 为每个方向按钮设置触摸监听（支持多点触控）
        btnForward.setOnTouchListener(createTouchListener("forward"))
        btnBackward.setOnTouchListener(createTouchListener("backward"))
        btnLeft.setOnTouchListener(createTouchListener("left"))
        btnRight.setOnTouchListener(createTouchListener("right"))

        // 停止按钮：点击立即停止并清除所有状态
        btnStop.setOnClickListener {
            // 重置所有状态
            forwardPressed = false
            backwardPressed = false
            leftPressed = false
            rightPressed = false
            stopPressed = true
            // 立即发送停止指令（不经过循环，直接发送）
            sendControl(0f, 0f)
            // 更新显示
            updateDisplay(0f, 0f)
            // 取消所有按钮的按下状态（视觉上）
            resetButtonStates()
        }

        // 启动循环发送
        startSending()
    }

    private fun createTouchListener(tag: String): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    when (tag) {
                        "forward" -> { forwardPressed = true; stopPressed = false }
                        "backward" -> { backwardPressed = true; stopPressed = false }
                        "left" -> { leftPressed = true; stopPressed = false }
                        "right" -> { rightPressed = true; stopPressed = false }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (tag) {
                        "forward" -> forwardPressed = false
                        "backward" -> backwardPressed = false
                        "left" -> leftPressed = false
                        "right" -> rightPressed = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    // 计算当前合成指令
    private fun computeControl(): Pair<Float, Float> {
        // 如果停止按钮被按下，强制归零
        if (stopPressed) {
            return Pair(0f, 0f)
        }

        // 速度：前进和后退不能同时有效，若同时按下则互相抵消（速度为0）
        val speed = when {
            forwardPressed && backwardPressed -> 0f
            forwardPressed -> 1f
            backwardPressed -> -1f
            else -> 0f
        }

        // 转向：左转和右转不能同时有效，若同时按下则互相抵消（转向为0）
        val turn = when {
            leftPressed && rightPressed -> 0f
            leftPressed -> -1f
            rightPressed -> 1f
            else -> 0f
        }

        return Pair(speed, turn)
    }

    private fun resetButtonStates() {
        // 恢复按钮视觉（无需额外操作，因为按下状态由系统管理）
        // 但我们可以强制刷新按钮状态（可选）
        btnStop.isPressed = false
        btnForward.isPressed = false
        btnBackward.isPressed = false
        btnLeft.isPressed = false
        btnRight.isPressed = false
        // 清除停止标志（但保持停止状态直到下次按下其他按钮）
        // 注意：停止按钮是点击后立即停止，但之后按下其他按钮会重新激活
    }

    // 循环发送任务
    private fun startSending() {
        sendRunnable = object : Runnable {
            override fun run() {
                val (speed, turn) = computeControl()
                // 发送归一化值
                listener?.onControl(speed, turn)
                // 显示实际值（乘以系数）
                updateDisplay(speed * getMaxSpeed(), turn * getMaxTurn())
                handler.postDelayed(this, sendInterval)
            }
        }
        handler.post(sendRunnable!!)
    }

    private fun sendControl(speed: Float, turn: Float) {
        listener?.onControl(speed, turn)
    }

    private fun updateDisplay(speed: Float, turn: Float) {
        valueDisplay.text = String.format(
            java.util.Locale.US,
            "速度: %.1f m/s  转向: %.0f °/s",
            speed, turn
        )
    }

    fun resetControls() {
        // 重置所有状态
        forwardPressed = false
        backwardPressed = false
        leftPressed = false
        rightPressed = false
        stopPressed = true
        // 发送停止指令
        listener?.onControl(0f, 0f)
        updateDisplay(0f, 0f)
        resetButtonStates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        sendRunnable = null
    }
}