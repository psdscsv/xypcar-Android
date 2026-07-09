package com.psd.xypcar

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var etDeviceName: EditText
    private lateinit var etMaxSpeed: EditText
    private lateinit var etMaxTurn: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("car_config", Context.MODE_PRIVATE)

        etDeviceName = findViewById(R.id.et_device_name)
        etMaxSpeed = findViewById(R.id.et_max_speed)
        etMaxTurn = findViewById(R.id.et_max_turn)

        // 加载当前配置
        loadSettings()

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        etDeviceName.setText(prefs.getString("device_name", "ESP32_Car") ?: "ESP32_Car")
        etMaxSpeed.setText(prefs.getFloat("max_speed", 2.2f).toString())
        etMaxTurn.setText(prefs.getFloat("max_turn", 50f).toString())
    }

    private fun saveSettings() {
        val editor = prefs.edit()
        editor.putString("device_name", etDeviceName.text.toString().trim())
        try {
            editor.putFloat("max_speed", etMaxSpeed.text.toString().toFloat())
            editor.putFloat("max_turn", etMaxTurn.text.toString().toFloat())
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
            return
        }
        editor.apply()
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish() // 自动返回主界面
    }
}