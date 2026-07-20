package com.psd.xypcar

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var menuMain: View
    private lateinit var pageBluetooth: View
    private lateinit var pageControl: View
    private lateinit var pageNavigation: View

    private lateinit var etDeviceName: EditText
    private lateinit var etMaxSpeed: EditText
    private lateinit var etMaxTurn: EditText

    // 导航新增
    private lateinit var etNavMaxSpeed: EditText
    private lateinit var etNavMaxTurn: EditText
    private lateinit var etArrivalDistance: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)

        menuMain = findViewById(R.id.menu_main)
        pageBluetooth = findViewById(R.id.page_bluetooth)
        pageControl = findViewById(R.id.page_control)
        pageNavigation = findViewById(R.id.page_navigation)

        etDeviceName = findViewById(R.id.et_device_name)
        etMaxSpeed = findViewById(R.id.et_max_speed)
        etMaxTurn = findViewById(R.id.et_max_turn)

        // 导航新增控件
        etNavMaxSpeed = findViewById(R.id.et_nav_max_speed)
        etNavMaxTurn = findViewById(R.id.et_nav_max_turn)
        etArrivalDistance = findViewById(R.id.et_arrival_distance)

        loadSettings()

        // 退出按钮
        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.menu_bluetooth).setOnClickListener {
            showPage(pageBluetooth)
        }
        findViewById<Button>(R.id.menu_control).setOnClickListener {
            showPage(pageControl)
        }
        findViewById<Button>(R.id.menu_navigation).setOnClickListener {
            showPage(pageNavigation)
        }

        findViewById<Button>(R.id.btn_back_main).setOnClickListener {
            showPage(menuMain)
        }
        findViewById<Button>(R.id.btn_back_main_control).setOnClickListener {
            showPage(menuMain)
        }
        findViewById<Button>(R.id.btn_back_main_nav).setOnClickListener {
            showPage(menuMain)
        }

        findViewById<Button>(R.id.btn_save_bluetooth).setOnClickListener {
            saveBluetooth()
        }
        findViewById<Button>(R.id.btn_save_control).setOnClickListener {
            saveControl()
        }
        findViewById<Button>(R.id.btn_save_navigation).setOnClickListener {
            saveNavigation()
        }
    }

    private fun showPage(view: View) {
        menuMain.visibility = View.GONE
        pageBluetooth.visibility = View.GONE
        pageControl.visibility = View.GONE
        pageNavigation.visibility = View.GONE
        view.visibility = View.VISIBLE
    }

    private fun loadSettings() {
        etDeviceName.setText(prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car")
        etMaxSpeed.setText(prefs.getFloat("max_speed", 2.2f).toString())
        etMaxTurn.setText(prefs.getFloat("max_turn", 50f).toString())

        // 加载导航参数
        etNavMaxSpeed.setText(prefs.getFloat("nav_max_speed", 1.5f).toString())
        etNavMaxTurn.setText(prefs.getFloat("nav_max_turn", 50f).toString())
        etArrivalDistance.setText(prefs.getFloat("arrival_distance", 10f).toString())
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
            editor.apply()
            Toast.makeText(this, "控制参数已保存", Toast.LENGTH_SHORT).show()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveNavigation() {
        val editor = prefs.edit()
        try {
            editor.putFloat("nav_max_speed", etNavMaxSpeed.text.toString().toFloat())
            editor.putFloat("nav_max_turn", etNavMaxTurn.text.toString().toFloat())
            editor.putFloat("arrival_distance", etArrivalDistance.text.toString().toFloat())
            editor.apply()
            Toast.makeText(this, "导航设置已保存", Toast.LENGTH_SHORT).show()
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
        }
    }
}