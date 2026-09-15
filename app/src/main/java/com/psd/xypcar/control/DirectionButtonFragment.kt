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

        btnForward.setOnTouchListener(createTouchListener("forward"))
        btnBackward.setOnTouchListener(createTouchListener("backward"))
        btnLeft.setOnTouchListener(createTouchListener("left"))
        btnRight.setOnTouchListener(createTouchListener("right"))

        btnStop.setOnClickListener {
            forwardPressed = false
            backwardPressed = false
            leftPressed = false
            rightPressed = false
            stopPressed = true
            sendControl(0f, 0f)
            resetButtonStates()
        }

        startSending()
    }

    private fun createTouchListener(tag: String): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    when (tag) {
                        "forward"  -> { forwardPressed = true; stopPressed = false }
                        "backward" -> { backwardPressed = true; stopPressed = false }
                        "left"     -> { leftPressed = true; stopPressed = false }
                        "right"    -> { rightPressed = true; stopPressed = false }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    when (tag) {
                        "forward"  -> forwardPressed = false
                        "backward" -> backwardPressed = false
                        "left"     -> leftPressed = false
                        "right"    -> rightPressed = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun computeControl(): Pair<Float, Float> {
        if (stopPressed) return Pair(0f, 0f)

        val speed = when {
            forwardPressed && backwardPressed -> 0f
            forwardPressed -> 1f
            backwardPressed -> -1f
            else -> 0f
        }

        val turn = when {
            leftPressed && rightPressed -> 0f
            leftPressed -> -1f
            rightPressed -> 1f
            else -> 0f
        }

        return Pair(speed, turn)
    }

    private fun resetButtonStates() {
        btnStop.isPressed = false
        btnForward.isPressed = false
        btnBackward.isPressed = false
        btnLeft.isPressed = false
        btnRight.isPressed = false
    }

    private fun startSending() {
        sendRunnable = object : Runnable {
            override fun run() {
                val (speed, turn) = computeControl()
                // 只发送控制，不再更新 value_display
                listener?.onControl(speed, turn)
                handler.postDelayed(this, sendInterval)
            }
        }
        handler.post(sendRunnable!!)
    }

    private fun sendControl(speed: Float, turn: Float) {
        listener?.onControl(speed, turn)
    }

    // ========== 新增：由 Activity 调用，用 BLE 收到的字符串更新 value_display ==========
    fun updateExternalDisplay(text: String) {
        if (!::valueDisplay.isInitialized) return
        activity?.runOnUiThread {
            valueDisplay.text = text
        }
    }
    // ============================================================================

    fun resetControls() {
        forwardPressed = false
        backwardPressed = false
        leftPressed = false
        rightPressed = false
        stopPressed = true
        listener?.onControl(0f, 0f)
        resetButtonStates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        sendRunnable = null
    }
}