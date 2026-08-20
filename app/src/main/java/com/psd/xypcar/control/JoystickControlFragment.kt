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

    // 回调接口
    interface OnControlListener {
        fun onControl(speed: Float, turn: Float)
    }

    private var listener: OnControlListener? = null
    private var mode = 0 // 0: 双摇杆, 1: 单摇杆

    // 摇杆控件
    private lateinit var leftJoystick: JoystickView
    private lateinit var rightJoystick: JoystickView
    private lateinit var valueDisplay: TextView

    // 当前值
    private var leftSpeed = 0f
    private var rightTurn = 0f

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (parentFragment is OnControlListener) {
            listener = parentFragment as OnControlListener
        } else if (context is OnControlListener) {
            listener = context
        }
        // 如果不实现回调，则默认不报错
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
        // 根据模式选择布局
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
            // 双摇杆模式
            rightJoystick = view.findViewById(R.id.right_joystick)
            setupDualMode()
        } else {
            // 单摇杆模式
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
                leftSpeed = -speed   // Y 轴控制速度
                rightTurn = turn     // X 轴控制转向
                sendControl()
            }
        })
        // 右摇杆不存在，无需设置
    }

    private fun sendControl() {
        // 调用回调
        listener?.onControl(leftSpeed, rightTurn)

        // 更新显示（可选）
        activity?.runOnUiThread {
            valueDisplay.text = String.format(
                java.util.Locale.US,
                "速度: %.1f m/s\n转向: %.0f °/s",
                leftSpeed * getMaxSpeed(),
                rightTurn * getMaxTurn()
            )
        }
    }

    // 获取最大速度和最大转向（从 SharedPreferences 读取）
    private fun getMaxSpeed(): Float {
        val prefs = requireContext().getSharedPreferences("car_config", Context.MODE_PRIVATE)
        return prefs.getFloat("max_speed", 2.2f)
    }

    private fun getMaxTurn(): Float {
        val prefs = requireContext().getSharedPreferences("car_config", Context.MODE_PRIVATE)
        return prefs.getFloat("max_turn", 50f)
    }

    // 外部调用复位
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