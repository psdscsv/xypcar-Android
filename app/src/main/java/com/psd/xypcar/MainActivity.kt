package com.psd.xypcar

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_remote).setOnClickListener {
            startActivity(Intent(this, RemoteControlActivity::class.java))
        }

        findViewById<Button>(R.id.btn_auto_drive).setOnClickListener {
            startActivity(Intent(this, AutoDriveActivity::class.java))
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_connect).setOnClickListener {
            startActivity(Intent(this, RemoteRelayActivity::class.java))
        }
    }
}