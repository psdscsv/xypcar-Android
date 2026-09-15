package com.psd.xypcar.control

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.psd.xypcar.R

/**
 * 摇杆控制 Fragment，支持双摇杆和单摇杆模式
 */
class JoystickControlFragment : Fragment() {

    interface OnControlListener {
        fun onControl(speed: Float, turn: Float)
    }

    private var listener: OnControlListener? = null
    private var mode = 0 // 0: 双摇杆, 1: 单摇杆

    private lateinit var leftJoystick: JoystickView
    private lateinit var rightJoystick: JoystickView
    private lateinit var valueDisplay: TextView

    private var leftSpeed = 0f
    private var rightTurn = 0f

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnControlListener) {
            listener = parentFragment as OnControlListener
        } else if (context is OnControlListener) {
            listener = context
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mode = it.getInt("mode", 0)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = if (mode == 0) {
            R.layout.fragment_joystick_dual
        } else {
            R.layout.fragment_joystick_single
        }
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        leftJoystick = view.findViewById(R.id.left_joystick)
        valueDisplay = view.findViewById(R.id.value_display)

        if (mode == 0) {
            rightJoystick = view.findViewById(R.id.right_joystick)
            setupDualMode()
        } else {
            setupSingleMode()
        }
    }

    private fun setupDualMode() {
        leftJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                leftSpeed = -speed
                sendControl()
            }
        })

        rightJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                rightTurn = turn
                sendControl()
            }
        })
    }

    private fun setupSingleMode() {
        leftJoystick.setOnJoystickMoveListener(object : JoystickView.OnJoystickMoveListener {
            override fun onMove(speed: Float, turn: Float) {
                leftSpeed = -speed
                rightTurn = turn
                sendControl()
            }
        })
    }

    private fun sendControl() {
        // 只发送控制，不再更新 value_display
        listener?.onControl(leftSpeed, rightTurn)
    }

    // ========== 新增：由 Activity 调用，用 BLE 收到的字符串更新 value_display ==========
    fun updateExternalDisplay(text: String) {
        if (!::valueDisplay.isInitialized) return
        activity?.runOnUiThread {
            valueDisplay.text = text
        }
    }
    // ============================================================================

    fun resetJoysticks() {
        leftJoystick.resetJoystick()
        if (::rightJoystick.isInitialized) {
            rightJoystick.resetJoystick()
        }
        leftSpeed = 0f
        rightTurn = 0f
        sendControl()
    }

    companion object {
        fun newInstance(mode: Int): JoystickControlFragment {
            val fragment = JoystickControlFragment()
            val args = Bundle()
            args.putInt("mode", mode)
            fragment.arguments = args
            return fragment
        }
    }
}