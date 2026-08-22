package com.psd.xypcar

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var menuMain: View
    private lateinit var pageBluetooth: View
    private lateinit var pageControl: View
    private lateinit var pageNavigation: View
    private lateinit var pageRemote: View
    private lateinit var pageVideo: View

    private lateinit var etDeviceName: EditText
    private lateinit var etMaxSpeed: EditText
    private lateinit var etMaxTurn: EditText

    private lateinit var etNavMaxSpeed: EditText
    private lateinit var etNavMaxTurn: EditText
    private lateinit var etArrivalDistance: EditText
    private lateinit var etCalibrationTime: EditText
    private lateinit var etCalibrationAngle: EditText

    private lateinit var etUsername: EditText
    private lateinit var etServerHost: EditText
    private lateinit var etServerPort: EditText

    private lateinit var switchVideoEnable: SwitchCompat

    private lateinit var spinnerJoystickMode: Spinner
    private lateinit var etTurnDeadZone: EditText
    private lateinit var etRollThreshold: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)

        menuMain = findViewById(R.id.menu_main)
        pageBluetooth = findViewById(R.id.page_bluetooth)
        pageControl = findViewById(R.id.page_control)
        pageNavigation = findViewById(R.id.page_navigation)
        pageRemote = findViewById(R.id.page_remote)
        pageVideo = findViewById(R.id.page_video)

        etDeviceName = findViewById(R.id.et_device_name)
        etMaxSpeed = findViewById(R.id.et_max_speed)
        etMaxTurn = findViewById(R.id.et_max_turn)

        etNavMaxSpeed = findViewById(R.id.et_nav_max_speed)
        etNavMaxTurn = findViewById(R.id.et_nav_max_turn)
        etArrivalDistance = findViewById(R.id.et_arrival_distance)
        etCalibrationTime = findViewById(R.id.et_calibration_time)
        etCalibrationAngle = findViewById(R.id.et_calibration_angle)

        pageRemote = findViewById(R.id.page_remote)
        etUsername = findViewById(R.id.et_username)
        etServerHost = findViewById(R.id.et_server_host)
        etServerPort = findViewById(R.id.et_server_port)

        switchVideoEnable = findViewById(R.id.switch_video_enable)

        spinnerJoystickMode = findViewById(R.id.spinner_joystick_mode)

        etTurnDeadZone = findViewById(R.id.et_turn_dead_zone)
        etRollThreshold = findViewById(R.id.et_roll_threshold)

        loadSettings()

        findViewById<Button>(R.id.btn_exit).setOnClickListener { finish() }

        findViewById<Button>(R.id.menu_bluetooth).setOnClickListener { showPage(pageBluetooth) }
        findViewById<Button>(R.id.menu_control).setOnClickListener { showPage(pageControl) }
        findViewById<Button>(R.id.menu_navigation).setOnClickListener { showPage(pageNavigation) }
        findViewById<Button>(R.id.menu_remote).setOnClickListener { showPage(pageRemote) }
        findViewById<Button>(R.id.menu_video).setOnClickListener { showPage(pageVideo) }

        findViewById<Button>(R.id.btn_back_main).setOnClickListener { showPage(menuMain) }
        findViewById<Button>(R.id.btn_back_main_control).setOnClickListener { showPage(menuMain) }
        findViewById<Button>(R.id.btn_back_main_nav).setOnClickListener { showPage(menuMain) }
        findViewById<Button>(R.id.btn_back_main_remote).setOnClickListener { showPage(menuMain) }
        findViewById<Button>(R.id.btn_back_main_video).setOnClickListener { showPage(menuMain) }

        findViewById<Button>(R.id.btn_save_bluetooth).setOnClickListener { saveBluetooth() }
        findViewById<Button>(R.id.btn_save_control).setOnClickListener { saveControl() }
        findViewById<Button>(R.id.btn_save_navigation).setOnClickListener { saveNavigation() }
        findViewById<Button>(R.id.btn_save_remote).setOnClickListener { saveRemote() }
        findViewById<Button>(R.id.btn_save_video).setOnClickListener { saveVideo() }
    }

    private fun showPage(view: View) {
        menuMain.visibility = View.GONE
        pageBluetooth.visibility = View.GONE
        pageControl.visibility = View.GONE
        pageNavigation.visibility = View.GONE
        pageRemote.visibility = View.GONE
        pageVideo.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    private fun loadSettings() {
        etDeviceName.setText(prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car")
        etMaxSpeed.setText(prefs.getFloat("max_speed", 2.2f).toString())
        etMaxTurn.setText(prefs.getFloat("max_turn", 50f).toString())

        etNavMaxSpeed.setText(prefs.getFloat("nav_max_speed", 1.5f).toString())
        etNavMaxTurn.setText(prefs.getFloat("nav_max_turn", 50f).toString())
        etArrivalDistance.setText(prefs.getFloat("arrival_distance", 10f).toString())
        etCalibrationTime.setText(prefs.getFloat("calibration_time", 2.0f).toString())
        etCalibrationAngle.setText(prefs.getFloat("calibration_angle", 5.0f).toString())

        etUsername.setText(prefs.getString("remote_username", "USER_001"))
        etServerHost.setText(prefs.getString("remote_host", "32.tcp.cpolar.top"))
        etServerPort.setText(prefs.getString("remote_port", "9999"))

        switchVideoEnable.isChecked = prefs.getBoolean("video_enabled", false)

        spinnerJoystickMode.setSelection(prefs.getInt("joystick_mode", 0))

        etTurnDeadZone.setText(prefs.getFloat("turn_dead_zone", 2f).toString())
        etRollThreshold.setText(prefs.getFloat("roll_threshold", 15f).toString())
    }

    private fun saveBluetooth() {
        val editor = prefs.edit()
        editor.putString("device_name", etDeviceName.text.toString().trim())
        editor.apply()
        Toast.makeText(this, "蓝牙设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun saveControl() {
        val editor = prefs.edit()
        try {
            editor.putFloat("max_speed", etMaxSpeed.text.toString().toFloat())
            editor.putFloat("max_turn", etMaxTurn.text.toString().toFloat())
            editor.putInt("joystick_mode", spinnerJoystickMode.selectedItemPosition)
            editor.apply()
            Toast.makeText(this, "控制参数已保存", Toast.LENGTH_SHORT).show()
        } catch (_: NumberFormatException) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveNavigation() {
        val editor = prefs.edit()
        try {
            editor.putFloat("nav_max_speed", etNavMaxSpeed.text.toString().toFloat())
            editor.putFloat("nav_max_turn", etNavMaxTurn.text.toString().toFloat())
            editor.putFloat("arrival_distance", etArrivalDistance.text.toString().toFloat())
            editor.putFloat("calibration_time", etCalibrationTime.text.toString().toFloat())
            editor.putFloat("calibration_angle", etCalibrationAngle.text.toString().toFloat())
            editor.putFloat("turn_dead_zone", etTurnDeadZone.text.toString().toFloatOrNull() ?: 2f)
            editor.putFloat("roll_threshold", etRollThreshold.text.toString().toFloatOrNull() ?: 15f)
            editor.apply()
            Toast.makeText(this, "导航设置已保存", Toast.LENGTH_SHORT).show()
        } catch (_: NumberFormatException) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRemote() {
        val editor = prefs.edit()
        editor.putString("remote_username", etUsername.text.toString().trim())
        editor.putString("remote_host", etServerHost.text.toString().trim())
        editor.putString("remote_port", etServerPort.text.toString().trim())
        editor.apply()
        Toast.makeText(this, "远程设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun saveVideo() {
        val editor = prefs.edit()
        editor.putBoolean("video_enabled", switchVideoEnable.isChecked)
        editor.apply()
        Toast.makeText(this, "图传设置已保存", Toast.LENGTH_SHORT).show()
    }
}