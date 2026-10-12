package com.example.autotoolkit.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.autotoolkit.R
import com.example.autotoolkit.service.AutoClickService
import com.example.autotoolkit.service.FloatingWindowService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnStartFloat = findViewById<Button>(R.id.btnStartFloat)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }

        btnStartFloat.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    btnOverlay.performClick()
                    return@setOnClickListener
                }
            }
            startForegroundService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val accessibilityOk = AutoClickService.instance != null
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        statusText.text = buildString {
            append("无障碍服务：")
            append(if (accessibilityOk) "已连接 ✓" else "未开启 ✗")
            append("\n悬浮窗权限：")
            append(if (overlayOk) "已授权 ✓" else "未授权 ✗")
        }
    }
}
